"""PostgreSQL access shared by every tab.

One small connection pool, one place for the schema. Tools call `db.connect()` and get a
connection whose rows are dicts; the schema below is created on start (idempotent).
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from contextlib import contextmanager
from typing import Any, Iterator

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS updates (
    id                BIGSERIAL PRIMARY KEY,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    title             TEXT    NOT NULL,
    category          TEXT    NOT NULL,
    is_new            BOOLEAN NOT NULL,
    chapters          JSONB   NOT NULL DEFAULT '[]',
    source            TEXT,
    suwayomi_id       INTEGER,
    kavita_series_id  INTEGER,
    kavita_library_id INTEGER,
    replaced          JSONB   NOT NULL DEFAULT '[]',
    duplicates        JSONB   NOT NULL DEFAULT '[]',
    destination       TEXT
);
CREATE INDEX IF NOT EXISTS idx_updates_created ON updates (created_at DESC);
CREATE TABLE IF NOT EXISTS cover_uploads (
    series_id   INTEGER PRIMARY KEY,
    suwayomi_id INTEGER NOT NULL,
    image_mtime DOUBLE PRECISION NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS runs (
    id          BIGSERIAL PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status      TEXT,
    moved       INTEGER DEFAULT 0,
    covers      INTEGER DEFAULT 0,
    message     TEXT
);
CREATE TABLE IF NOT EXISTS aliases (
    suwayomi_title TEXT PRIMARY KEY,
    kavita_name    TEXT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_aliases_ci ON aliases (lower(suwayomi_title));
-- Source type of a Suwayomi title: 0 normal, 1 official, 2 uncensored, 3 official uncensored.
ALTER TABLE aliases ADD COLUMN IF NOT EXISTS rank INTEGER NOT NULL DEFAULT 0;
-- Which source each chapter file in a Kavita folder came from (to replace lower-ranked ones with official).
CREATE TABLE IF NOT EXISTS chapter_sources (
    series_dir  TEXT NOT NULL,
    file_name   TEXT NOT NULL,
    chapter_key TEXT NOT NULL,
    rank        INTEGER NOT NULL DEFAULT 0,
    suwayomi_id INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (series_dir, file_name)
);
CREATE TABLE IF NOT EXISTS users (
    username      TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_ci ON users (lower(username));
CREATE TABLE IF NOT EXISTS settings (
    key        TEXT PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Generic key/value store for tool state (double-chapter scan results, collection rules, ...).
CREATE TABLE IF NOT EXISTS kv (
    namespace  TEXT NOT NULL,
    key        TEXT NOT NULL,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace, key)
);
-- Every tool's notable events, shown in the activity feed.
CREATE TABLE IF NOT EXISTS activity (
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tool       TEXT NOT NULL,
    kind       TEXT NOT NULL,
    message    TEXT NOT NULL,
    data       JSONB
);
CREATE INDEX IF NOT EXISTS idx_activity_created ON activity (created_at DESC);
"""


class Database:
    """Thin wrapper around a psycopg connection pool."""

    def __init__(self, url: str, min_size: int = 1, max_size: int = 8):
        if not url:
            raise ValueError("DATABASE_URL is not set")
        self.url = url
        self._pool = ConnectionPool(url, min_size=min_size, max_size=max_size, open=False,
                                    kwargs={"row_factory": dict_row, "autocommit": True})
        self._pool.open()
        self._pool.wait(timeout=30)

    @classmethod
    def wait_for(cls, url: str, timeout: int = 60) -> "Database":
        """Connect, retrying while Postgres is still starting (compose start-up order)."""
        deadline = time.monotonic() + timeout
        while True:
            try:
                return cls(url)
            except Exception as exc:  # noqa: BLE001
                if time.monotonic() > deadline:
                    raise
                log.info("Waiting for the database (%s)", exc.__class__.__name__)
                time.sleep(2)

    def init_schema(self) -> None:
        with self.connect() as c:
            c.execute("SELECT pg_advisory_lock(727001)")
            try:
                c.execute(SCHEMA)
            finally:
                c.execute("SELECT pg_advisory_unlock(727001)")

    @contextmanager
    def connect(self) -> Iterator[psycopg.Connection]:
        with self._pool.connection() as conn:
            yield conn

    def close(self) -> None:
        self._pool.close()

    # -- small helpers ---------------------------------------------------------
    def ping(self) -> bool:
        with self.connect() as c:
            return c.execute("SELECT 1 AS ok").fetchone()["ok"] == 1

    def kv_get(self, namespace: str, key: str, default: Any = None) -> Any:
        with self.connect() as c:
            row = c.execute("SELECT value FROM kv WHERE namespace=%s AND key=%s", (namespace, key)).fetchone()
        return row["value"] if row else default

    def kv_set(self, namespace: str, key: str, value: Any) -> None:
        with self.connect() as c:
            c.execute("INSERT INTO kv (namespace, key, value) VALUES (%s,%s,%s) "
                      "ON CONFLICT (namespace, key) DO UPDATE SET value=excluded.value, updated_at=now()",
                      (namespace, key, Jsonb(value)))

    def kv_delete(self, namespace: str, key: str) -> None:
        with self.connect() as c:
            c.execute("DELETE FROM kv WHERE namespace=%s AND key=%s", (namespace, key))

    def kv_all(self, namespace: str) -> dict[str, Any]:
        with self.connect() as c:
            return {r["key"]: r["value"] for r in c.execute(
                "SELECT key, value FROM kv WHERE namespace=%s", (namespace,))}

    def log_activity(self, tool: str, kind: str, message: str, data: Any = None) -> None:
        try:
            with self.connect() as c:
                c.execute("INSERT INTO activity (tool, kind, message, data) VALUES (%s,%s,%s,%s)",
                          (tool, kind, message[:500], Jsonb(data) if data is not None else None))
                c.execute("DELETE FROM activity WHERE created_at < now() - interval '90 days'")
        except Exception:  # noqa: BLE001 - the feed is never worth breaking a run
            log.exception("Could not write activity")

    def settings(self) -> dict[str, Any]:
        with self.connect() as c:
            return {r["key"]: r["value"] for r in c.execute("SELECT key, value FROM settings")}

    def set_setting(self, key: str, value: Any) -> None:
        with self.connect() as c:
            c.execute("INSERT INTO settings (key, value) VALUES (%s,%s) "
                      "ON CONFLICT (key) DO UPDATE SET value=excluded.value, updated_at=now()",
                      (key, Jsonb(value)))

    def delete_setting(self, key: str) -> None:
        with self.connect() as c:
            c.execute("DELETE FROM settings WHERE key=%s", (key,))


def database_url_from_env() -> str:
    """DATABASE_URL, or one assembled from POSTGRES_* variables."""
    url = os.environ.get("DATABASE_URL", "").strip()
    if url:
        return url
    host = os.environ.get("POSTGRES_HOST", "")
    if not host:
        return ""
    from urllib.parse import quote
    user = quote(os.environ.get("POSTGRES_USER", "manga"), safe="")
    password = quote(os.environ.get("POSTGRES_PASSWORD", ""), safe="")
    name = os.environ.get("POSTGRES_DB", "manga_manager")
    port = os.environ.get("POSTGRES_PORT", "5432")
    return f"postgresql://{user}:{password}@{host}:{port}/{name}"
