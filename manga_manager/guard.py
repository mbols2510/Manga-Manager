"""Connection checks shared by the Settings page and the job gate.

Scheduled work (moving files, scans, digests) only runs while every configured connection answers:
database, Kavita, Suwayomi, ntfy and the NAS. A connection that is not configured is ignored.
Results are cached briefly so a burst of jobs does not hammer the services.
"""
from __future__ import annotations

import logging
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from .settings import Setting, register

log = logging.getLogger(__name__)

register(
    Setting("nas.host", "NAS address", "NAS", "text", "NAS_HOST", hint="IP or hostname of the NAS that holds the manga folders. Empty = don't check."),
    Setting("nas.ports", "NAS ports to try", "NAS", "text", "NAS_PORTS", "445,5000,5001,2049",
            hint="The NAS counts as online when any of these TCP ports answers"),
    Setting("guard.enabled", "Pause scheduled tasks while a connection is offline", "NAS", "bool", "GUARD_ENABLED", True),
)

TTL = 20          # seconds a result is reused
TIMEOUT = 5       # seconds per connection


class _Skip(Exception):
    pass


class ConnectionGuard:
    def __init__(self, ctx):
        self.ctx = ctx
        self._lock = threading.Lock()
        self._cache: tuple[float, list[dict]] | None = None
        self._last_down: frozenset[str] = frozenset()

    # ------------------------------------------------------------ individual checks
    def _db(self):
        self.ctx.db.ping()
        return "connected"

    def _kavita(self):
        cfg = self.ctx.cfg
        if not (cfg.kavita_url and cfg.kavita_api_key):
            raise _Skip("Kavita URL / API key not set")
        r = requests.get(cfg.kavita_url + "/api/health", timeout=TIMEOUT)
        if r.status_code >= 500:
            raise RuntimeError(f"HTTP {r.status_code}")
        return "reachable"

    def _suwayomi(self):
        cfg = self.ctx.cfg
        if not cfg.suwayomi_url:
            raise _Skip("Suwayomi URL not set")
        auth = (cfg.suwayomi_username, cfg.suwayomi_password) if cfg.suwayomi_username else None
        r = requests.get(cfg.suwayomi_url + "/api/graphql", timeout=TIMEOUT, auth=auth)
        if r.status_code >= 500:
            raise RuntimeError(f"HTTP {r.status_code}")
        return "reachable"

    def _ntfy(self):
        cfg = self.ctx.cfg
        if not cfg.ntfy_url:
            raise _Skip("ntfy URL not set")
        requests.get(cfg.ntfy_url + "/v1/health", timeout=TIMEOUT).raise_for_status()
        return "reachable"

    def _nas(self):
        host = str(self.ctx.settings.get("nas.host") or "").strip()
        if not host:
            raise _Skip("NAS address not set")
        ports = []
        for p in str(self.ctx.settings.get("nas.ports") or "").replace(";", ",").split(","):
            if p.strip().isdigit():
                ports.append(int(p))
        if not ports:
            raise _Skip("no NAS ports set")
        errors = []
        for port in ports:
            try:
                with socket.create_connection((host, port), timeout=2):
                    return f"online ({host}:{port})"
            except OSError as exc:
                errors.append(f"{port}: {exc.__class__.__name__}")
        raise RuntimeError(f"{host} does not answer ({', '.join(errors)})")

    # ------------------------------------------------------------ aggregate
    def _run_one(self, name, fn):
        started = time.monotonic()
        try:
            return {"name": name, "state": "ok", "detail": fn(), "ms": int((time.monotonic() - started) * 1000)}
        except _Skip as exc:
            return {"name": name, "state": "unset", "detail": str(exc), "ms": 0}
        except Exception as exc:  # noqa: BLE001
            return {"name": name, "state": "fail", "detail": str(exc)[:300],
                    "ms": int((time.monotonic() - started) * 1000)}

    def checks(self, force: bool = False) -> list[dict]:
        with self._lock:
            if not force and self._cache and time.monotonic() - self._cache[0] < TTL:
                return self._cache[1]
        items = [("Database", self._db), ("NAS", self._nas), ("Kavita", self._kavita),
                 ("Suwayomi", self._suwayomi), ("ntfy", self._ntfy)]
        with ThreadPoolExecutor(max_workers=len(items)) as pool:
            results = list(pool.map(lambda kv: self._run_one(*kv), items))
        with self._lock:
            self._cache = (time.monotonic(), results)
        self._note_transition(results)
        return results

    def _note_transition(self, results: list[dict]) -> None:
        down = frozenset(r["name"] for r in results if r["state"] == "fail")
        if down == self._last_down:
            return
        previous, self._last_down = self._last_down, down
        try:
            if down - previous:
                msg = "Offline: " + ", ".join(sorted(down)) + ". Scheduled tasks are paused."
                log.warning(msg)
                self.ctx.db.log_activity("system", "offline", msg)
            elif previous - down and not down:
                log.info("All connections are back; scheduled tasks resume")
                self.ctx.db.log_activity("system", "online", "All connections are back. Scheduled tasks resume.")
        except Exception:  # noqa: BLE001 - the database itself may be the offline one
            pass

    def reason(self) -> str | None:
        """Why scheduled work must wait, or None when it may run."""
        if not self.ctx.settings.get("guard.enabled"):
            return None
        down = [r["name"] for r in self.checks() if r["state"] == "fail"]
        return ("Offline: " + ", ".join(down)) if down else None
