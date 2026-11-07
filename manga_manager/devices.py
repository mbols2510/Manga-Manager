"""Per-device bearer tokens for the Android companion. Only a SHA-256 hash is stored."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class DeviceStore:
    def __init__(self, db):
        self.db = db

    def create(self, name: str, user: str) -> tuple[int, str]:
        """Returns (id, plaintext token). The token is never retrievable again."""
        token = secrets.token_urlsafe(32)
        with self.db.connect() as c:
            row = c.execute(
                "INSERT INTO device_tokens (name, user_name, token_hash) VALUES (%s, %s, %s) RETURNING id",
                (name, user, _hash(token))).fetchone()
        return row["id"], token

    def authenticate(self, token: str) -> dict | None:
        if not token:
            return None
        digest = _hash(token)
        with self.db.connect() as c:
            row = c.execute(
                "SELECT id, name, user_name, token_hash FROM device_tokens "
                "WHERE token_hash = %s AND revoked_at IS NULL", (digest,)).fetchone()
            if row is None or not hmac.compare_digest(row["token_hash"], digest):
                return None
            c.execute("UPDATE device_tokens SET last_seen = now() WHERE id = %s", (row["id"],))
        return {"id": row["id"], "name": row["name"], "user": row["user_name"]}

    def list(self) -> list[dict]:
        with self.db.connect() as c:
            return c.execute(
                "SELECT id, name, user_name AS user, created_at, last_seen, revoked_at "
                "FROM device_tokens ORDER BY id").fetchall()

    def revoke(self, device_id: int) -> bool:
        with self.db.connect() as c:
            cur = c.execute(
                "UPDATE device_tokens SET revoked_at = now() WHERE id = %s AND revoked_at IS NULL",
                (device_id,))
            return cur.rowcount > 0

    @staticmethod
    def pairing_code(url: str, token: str) -> str:
        payload = json.dumps({"v": 1, "url": url, "token": token}, separators=(",", ":"))
        return "mm1." + base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")
