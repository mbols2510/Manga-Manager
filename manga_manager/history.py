"""Postgres store holding every processed update (feeds the dashboard)."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from psycopg.types.json import Jsonb

from .db import Database


def _iso(value) -> str | None:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds") if isinstance(value, datetime) else value


def _row(r: dict) -> dict:
    out = dict(r)
    for k in ("created_at", "started_at", "finished_at", "uploaded_at"):
        if k in out:
            out[k] = _iso(out[k])
    return out


class History:
    def __init__(self, db: Database):
        self.db = db

    # -- writes --------------------------------------------------------------
    def add_update(self, *, title: str, category: str, is_new: bool, chapters: list[str], source: str = "",
                   suwayomi_id: int | None = None, kavita_series_id: int | None = None,
                   kavita_library_id: int | None = None, replaced: list[str] | None = None,
                   duplicates: list[str] | None = None, destination: str | None = None) -> int:
        with self.db.connect() as c:
            row = c.execute(
                "INSERT INTO updates (title,category,is_new,chapters,source,suwayomi_id,kavita_series_id,"
                "kavita_library_id,replaced,duplicates,destination) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                "RETURNING id",
                (title, category, is_new, Jsonb(chapters), source, suwayomi_id, kavita_series_id,
                 kavita_library_id, Jsonb(replaced or []), Jsonb(duplicates or []), destination)).fetchone()
        return int(row["id"])

    def set_kavita_series(self, update_id: int, series_id: int) -> None:
        with self.db.connect() as c:
            c.execute("UPDATE updates SET kavita_series_id=%s WHERE id=%s", (series_id, update_id))

    def updates_for_titles(self, titles: list[str]) -> list[dict]:
        """Stored updates under any of these titles (case-insensitive), for reprocessing an alias (#16)."""
        titles = [t.lower() for t in titles if t]
        if not titles:
            return []
        with self.db.connect() as c:
            rows = c.execute("SELECT id, title, kavita_library_id, destination FROM updates "
                             "WHERE lower(title) = ANY(%s)", (titles,)).fetchall()
        return [dict(r) for r in rows]

    def retarget_updates(self, ids: list[int], *, title: str, destination: str, series_id: int | None) -> None:
        """After the chapters were moved into the alias's series: point those updates at it (#16)."""
        if not ids:
            return
        with self.db.connect() as c:
            c.execute("UPDATE updates SET title=%s, destination=%s, "
                      "kavita_series_id=COALESCE(%s, kavita_series_id) WHERE id = ANY(%s)",
                      (title, destination, series_id, ids))

    def cover_uploaded(self, series_id: int, suwayomi_id: int, image_mtime: float) -> bool:
        with self.db.connect() as c:
            row = c.execute("SELECT suwayomi_id, image_mtime FROM cover_uploads WHERE series_id=%s",
                            (series_id,)).fetchone()
        return bool(row) and row["suwayomi_id"] == suwayomi_id and row["image_mtime"] >= image_mtime

    def record_cover_upload(self, series_id: int, suwayomi_id: int, image_mtime: float) -> None:
        with self.db.connect() as c:
            c.execute("INSERT INTO cover_uploads (series_id, suwayomi_id, image_mtime) VALUES (%s,%s,%s) "
                      "ON CONFLICT (series_id) DO UPDATE SET suwayomi_id=excluded.suwayomi_id, "
                      "image_mtime=excluded.image_mtime, uploaded_at=now()", (series_id, suwayomi_id, image_mtime))

    def start_run(self) -> int:
        with self.db.connect() as c:
            return int(c.execute("INSERT INTO runs (status) VALUES ('running') RETURNING id").fetchone()["id"])

    def finish_run(self, run_id: int, status: str, moved: int, covers: int, message: str = "") -> None:
        with self.db.connect() as c:
            c.execute("UPDATE runs SET finished_at=now(),status=%s,moved=%s,covers=%s,message=%s WHERE id=%s",
                      (status, moved, covers, message[:2000], run_id))
            c.execute("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY id DESC LIMIT 500)")

    def prune_updates(self, keep_days: int) -> int:
        """Delete dashboard updates older than keep_days (#14). 0 keeps everything."""
        if keep_days <= 0:
            return 0
        cutoff = datetime.now(timezone.utc) - timedelta(days=keep_days)
        with self.db.connect() as c:
            return c.execute("DELETE FROM updates WHERE created_at < %s", (cutoff,)).rowcount

    # -- aliases managed on the dashboard (#12) -------------------------------
    def aliases(self) -> dict[str, str]:
        with self.db.connect() as c:
            return {r["suwayomi_title"]: r["kavita_name"] for r in c.execute(
                "SELECT suwayomi_title, kavita_name FROM aliases ORDER BY lower(suwayomi_title)")}

    def alias_ranks(self) -> dict[str, int]:
        """casefolded Suwayomi title -> source type (0 normal, 1 official, 2 uncensored, 3 both)."""
        with self.db.connect() as c:
            return {r["suwayomi_title"].casefold(): int(r["rank"]) for r in c.execute(
                "SELECT suwayomi_title, rank FROM aliases WHERE rank > 0")}

    def source_ranks(self, series_dir: str) -> dict[str, int]:
        with self.db.connect() as c:
            return {r["file_name"]: int(r["rank"]) for r in c.execute(
                "SELECT file_name, rank FROM chapter_sources WHERE series_dir=%s", (series_dir,))}

    def record_sources(self, series_dir: str, entries: list[tuple[str, str, int, int | None]]) -> None:
        """entries: (file_name, chapter_key, rank, suwayomi_id)."""
        if not entries:
            return
        with self.db.connect() as c:
            for name, key, rank, sid in entries:
                c.execute("INSERT INTO chapter_sources (series_dir,file_name,chapter_key,rank,suwayomi_id) "
                          "VALUES (%s,%s,%s,%s,%s) ON CONFLICT (series_dir,file_name) DO UPDATE SET "
                          "chapter_key=excluded.chapter_key, rank=excluded.rank, suwayomi_id=excluded.suwayomi_id, "
                          "created_at=now()", (series_dir, name, key, rank, sid))

    def forget_sources(self, series_dir: str, names: list[str]) -> None:
        if names:
            with self.db.connect() as c:
                c.execute("DELETE FROM chapter_sources WHERE series_dir=%s AND file_name = ANY(%s)", (series_dir, names))

    def set_alias(self, suwayomi_title: str, kavita_name: str, user: str | None = None,
                  previous: str | None = None, rank: int = 0) -> None:
        """Add or change an alias; with previous, the alias that was stored under that title is replaced."""
        with self.db.connect() as c:
            if previous is not None and previous.casefold() != suwayomi_title.casefold():
                c.execute("DELETE FROM aliases WHERE suwayomi_title = %s", (previous,))
            c.execute("DELETE FROM aliases WHERE lower(suwayomi_title) = lower(%s)", (suwayomi_title,))
            c.execute("INSERT INTO aliases (suwayomi_title, kavita_name, updated_by, rank) VALUES (%s,%s,%s,%s)",
                      (suwayomi_title, kavita_name, user, int(rank)))

    def add_auto_alias(self, suwayomi_title: str, rank: int = 0) -> bool:
        """Add an alias with an empty Kavita source (#8); never touches an existing one (any letter case).
        Returns True when a row was added, so repeated runs don't duplicate."""
        with self.db.connect() as c:
            return c.execute("INSERT INTO aliases (suwayomi_title, kavita_name, updated_by, rank) "
                             "VALUES (%s,'','auto',%s) ON CONFLICT DO NOTHING", (suwayomi_title, int(rank))).rowcount > 0

    def delete_alias(self, suwayomi_title: str) -> bool:
        with self.db.connect() as c:
            return c.execute("DELETE FROM aliases WHERE lower(suwayomi_title) = lower(%s)", (suwayomi_title,)).rowcount > 0

    # -- reads ---------------------------------------------------------------
    def list_updates(self, *, limit: int = 60, offset: int = 0, category: str | None = None,
                     kind: str | None = None, query: str | None = None,
                     also_titles: list[str] | None = None) -> tuple[list[dict], int]:
        where, args = [], []
        if category:
            where.append("category = %s")
            args.append(category)
        if kind == "new":
            where.append("is_new")
        elif kind == "updated":
            where.append("NOT is_new")
        if query:
            titles = [t.lower() for t in (also_titles or [])]
            where.append("(title ILIKE %s OR lower(title) = ANY(%s))")
            args.extend([f"%{query}%", titles])
        clause = f"WHERE {' AND '.join(where)}" if where else ""
        with self.db.connect() as c:
            total = c.execute(f"SELECT COUNT(*) AS n FROM updates {clause}", args).fetchone()["n"]
            rows = c.execute(f"SELECT * FROM updates {clause} ORDER BY created_at DESC, id DESC LIMIT %s OFFSET %s",
                             [*args, limit, offset]).fetchall()
        return [_row(r) for r in rows], int(total)

    def stats(self) -> dict:
        with self.db.connect() as c:
            row = c.execute("SELECT COUNT(*) AS updates, COALESCE(SUM(is_new::int),0) AS new_series, "
                            "COUNT(DISTINCT title) AS titles FROM updates").fetchone()
            chapters = c.execute("SELECT COALESCE(SUM(jsonb_array_length(chapters)),0) AS n FROM updates").fetchone()["n"]
            cats = [r["category"] for r in c.execute("SELECT DISTINCT category FROM updates ORDER BY category")]
            last = c.execute("SELECT * FROM runs ORDER BY id DESC LIMIT 1").fetchone()
        return {"updates": row["updates"], "new_series": int(row["new_series"]), "titles": row["titles"],
                "chapters": int(chapters), "categories": cats, "last_run": _row(last) if last else None}
