"""Dashboard login: accounts, password hashes, signed cover links, login rate limiting.

Accounts come from two places:
  * web.auth.users in the config (username -> password hash), made with `hash-password`;
  * the sign-up page, when web.auth.allow_signup is on (stored in history.db).
Config accounts win when a name exists in both.
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path

from werkzeug.security import check_password_hash, generate_password_hash

from .config import Config

log = logging.getLogger(__name__)

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,32}$")
MIN_PASSWORD = 10

# A fixed hash to compare against when a username doesn't exist, so a login for an
# unknown user takes as long as one with a wrong password.
_DUMMY_HASH = generate_password_hash("move-manga-dummy-password")


def hash_password(password: str) -> str:
    return generate_password_hash(password)


def load_secret_key(cfg: Config) -> str:
    """web.auth.secret_key, or a random key created once and kept in <data>/secret_key."""
    if cfg.auth_secret_key:
        return cfg.auth_secret_key
    path = cfg.data_dir / "secret_key"
    try:
        if path.exists():
            key = path.read_text().strip()
            if key:
                return key
        path.parent.mkdir(parents=True, exist_ok=True)
        key = secrets.token_hex(32)
        path.write_text(key)
        try:
            path.chmod(0o600)
        except OSError:
            pass
        return key
    except OSError as exc:
        # Still works, but sessions and signed cover links reset on every restart.
        log.warning("Could not store the secret key in %s (%s); using a temporary one", path, exc)
        return secrets.token_hex(32)


class ThumbSigner:
    """Signs /thumb/<id>.png links so ntfy (which has no login) can still load covers."""

    def __init__(self, secret: str):
        self._key = hashlib.sha256(("thumb:" + secret).encode()).digest()

    def sign(self, manga_id: int) -> str:
        return hmac.new(self._key, str(int(manga_id)).encode(), hashlib.sha256).hexdigest()[:24]

    def verify(self, manga_id: int, signature: str | None) -> bool:
        return bool(signature) and hmac.compare_digest(self.sign(manga_id), signature)

    def path(self, manga_id: int) -> str:
        return f"thumb/{int(manga_id)}.png?s={self.sign(manga_id)}"


class UserStore:
    """Config accounts plus accounts created through the sign-up page (stored in Postgres)."""

    def __init__(self, db, config_users: dict[str, str]):
        self.db = db
        self.config_users = {k.casefold(): (k, v) for k, v in config_users.items()}

    def _lookup(self, username: str) -> tuple[str, str] | None:
        if username.casefold() in self.config_users:
            return self.config_users[username.casefold()]
        with self.db.connect() as c:
            row = c.execute("SELECT username, password_hash FROM users WHERE lower(username) = lower(%s)",
                            (username,)).fetchone()
        return (row["username"], row["password_hash"]) if row else None

    def exists(self, username: str) -> bool:
        return self._lookup(username) is not None

    def authenticate(self, username: str, password: str) -> str | None:
        """The account's username when the password is right, else None."""
        found = self._lookup(username) if username else None
        if found is None:
            check_password_hash(_DUMMY_HASH, password or "")
            return None
        name, pw_hash = found
        try:
            ok = check_password_hash(pw_hash, password or "")
        except (ValueError, TypeError):
            log.warning("The password hash for %r is not valid; make a new one with hash-password", name)
            ok = False
        return name if ok else None

    def create(self, username: str, password: str) -> None:
        with self.db.connect() as c:
            c.execute("INSERT INTO users (username, password_hash) VALUES (%s,%s)",
                      (username, hash_password(password)))

    def count(self) -> int:
        with self.db.connect() as c:
            signed_up = c.execute("SELECT COUNT(*) AS n FROM users").fetchone()["n"]
        return len(self.config_users) + int(signed_up)


class LoginLimiter:
    """At most `attempts` failed logins per client (and per username) per `window` seconds."""

    def __init__(self, attempts: int = 8, window: int = 900, clock=time.monotonic):
        self.attempts = attempts
        self.window = window
        self.clock = clock
        self._fails: dict[str, deque] = defaultdict(deque)
        self._lock = threading.Lock()

    def _prune(self, key: str) -> deque:
        q = self._fails[key]
        cutoff = self.clock() - self.window
        while q and q[0] < cutoff:
            q.popleft()
        return q

    def blocked(self, *keys: str) -> bool:
        with self._lock:
            return any(len(self._prune(k)) >= self.attempts for k in keys if k)

    def fail(self, *keys: str) -> None:
        with self._lock:
            now = self.clock()
            for k in keys:
                if k:
                    self._prune(k).append(now)

    def reset(self, *keys: str) -> None:
        with self._lock:
            for k in keys:
                self._fails.pop(k, None)


def validate_signup(username: str, password: str, confirm: str) -> str | None:
    """Problem with a sign-up form, or None."""
    if not USERNAME_RE.match(username or ""):
        return "Pick a username of 3 to 32 letters, digits, dots, dashes or underscores."
    if len(password or "") < MIN_PASSWORD:
        return f"Use a password of at least {MIN_PASSWORD} characters."
    if password != confirm:
        return "The two passwords don't match."
    return None
