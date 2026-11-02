"""One-time import of the data the four standalone apps kept (SQLite / JSON files) into Postgres."""
from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path

from psycopg.types.json import Jsonb

from .context import AppContext

log = logging.getLogger(__name__)


def import_move_manga(ctx: AppContext, db_path: str) -> str:
    path = Path(db_path)
    if not path.is_file():
        return f"Move Manga: {path} not found, skipped"
    src = sqlite3.connect(path)
    src.row_factory = sqlite3.Row
    counts = {"updates": 0, "aliases": 0, "users": 0, "covers": 0}
    with ctx.db.connect() as c:
        have = c.execute("SELECT COUNT(*) AS n FROM updates").fetchone()["n"]
        if have:
            return f"Move Manga: updates table already has {have} rows, skipped (import only runs on an empty database)"
        cols = {r[1] for r in src.execute("PRAGMA table_info(updates)")}
        for r in src.execute("SELECT * FROM updates ORDER BY id"):
            r = dict(r)
            c.execute(
                "INSERT INTO updates (created_at,title,category,is_new,chapters,source,suwayomi_id,kavita_series_id,"
                "kavita_library_id,replaced,duplicates,destination) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (r["created_at"], r["title"], r["category"], bool(r["is_new"]), Jsonb(json.loads(r["chapters"] or "[]")),
                 r.get("source"), r.get("suwayomi_id"), r.get("kavita_series_id"), r.get("kavita_library_id"),
                 Jsonb(json.loads(r.get("replaced") or "[]")), Jsonb(json.loads(r.get("duplicates") or "[]")),
                 r.get("destination") if "destination" in cols else None))
            counts["updates"] += 1
        for r in src.execute("SELECT * FROM aliases"):
            c.execute("INSERT INTO aliases (suwayomi_title,kavita_name,updated_at,updated_by) VALUES (%s,%s,%s,%s) "
                      "ON CONFLICT DO NOTHING", (r["suwayomi_title"], r["kavita_name"], r["updated_at"], r["updated_by"]))
            counts["aliases"] += 1
        try:
            for r in src.execute("SELECT * FROM users"):
                c.execute("INSERT INTO users (username,password_hash,created_at) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING",
                          (r["username"], r["password_hash"], r["created_at"]))
                counts["users"] += 1
        except sqlite3.OperationalError:
            pass
        try:
            for r in src.execute("SELECT * FROM cover_uploads"):
                c.execute("INSERT INTO cover_uploads (series_id,suwayomi_id,image_mtime,uploaded_at) VALUES (%s,%s,%s,%s) "
                          "ON CONFLICT DO NOTHING", (r["series_id"], r["suwayomi_id"], r["image_mtime"], r["uploaded_at"]))
                counts["covers"] += 1
        except sqlite3.OperationalError:
            pass
    return "Move Manga: imported " + ", ".join(f"{n} {k}" for k, n in counts.items())


def run_import(ctx: AppContext, move_manga_db: str | None, double_chapters_dir: str | None,
               collections_dir: str | None) -> str:
    lines = []
    if move_manga_db:
        lines.append(import_move_manga(ctx, move_manga_db))
    if double_chapters_dir:
        from .tools import double_chapters
        lines.append(double_chapters.import_legacy(ctx, double_chapters_dir))
    if collections_dir:
        from .tools import collections
        lines.append(collections.import_legacy(ctx, collections_dir))
    return "\n".join(lines) or "Nothing to import: pass --move-manga, --double-chapters and/or --collections."
