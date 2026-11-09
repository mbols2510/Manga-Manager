"""Double Chapters tab: finds Kavita chapters that have more than one file, lets you keep one and
trash the rest, and sends a weekly ntfy digest.

State lives in Postgres (kv namespace "dc"): `series` (current scan result), `trash` (trashed files)
and `meta` (last scan, last digest week + snapshot).
"""
from __future__ import annotations

import logging
import hashlib
import os
import re
import shutil
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import requests
from flask import Blueprint, Response, current_app, jsonify, render_template, request, session
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer

from .. import __version__, notify
from ..image_cache import ImageBusy, ImageCache
from ..settings import WEEKDAYS, Setting, register
from .double_bulk import BulkConflict, BulkProcessor

log = logging.getLogger("double_chapters")

NS = "dc"
TOPIC_KEY = "dc.topic"
ADD_MANY_THRESHOLD = 10   # series count that bumps the digest to "warning" priority

register(
    Setting(TOPIC_KEY, "Topic: double chapters", "Double chapters", "text", "DC_NTFY_TOPIC", "Double_Chapters"),
    Setting("dc.library_ids", "Kavita library IDs", "Double chapters", "text", "KAVITA_LIBRARY_IDS", "",
            hint="Comma separated. Empty = scan every library."),
    Setting("dc.scan_interval_minutes", "Scan every (minutes)", "Double chapters", "int", "SCAN_INTERVAL_MINUTES", 15),
    Setting("dc.manga_root", "Manga folder in this container", "Double chapters", "text", "DC_MANGA_ROOT", "/manga",
            hint="Container path, normally /manga. MANGA_ROOT in .env is the host-side Compose mount."),
    Setting("dc.kavita_manga_root", "Manga folder as Kavita sees it", "Double chapters", "text", "KAVITA_MANGA_ROOT", "",
            hint="Only when Kavita mounts the share at a different path; empty = same as above"),
    Setting("dc.trash_retention_days", "Delete trashed files after (days)", "Double chapters", "int",
            "TRASH_RETENTION_DAYS", 7, hint="0 keeps them until you empty the trash"),
    Setting("dc.require_different_size", "Only flag files of different sizes", "Double chapters", "bool",
            "REQUIRE_DIFFERENT_SIZE", False),
    Setting("dc.notify_enabled", "Send the weekly digest", "Double chapters", "bool", "DC_NOTIFY_ENABLED", True),
    Setting("dc.notify_weekday", "Digest day", "Double chapters", "select", "DC_NOTIFY_WEEKDAY", "0", choices=WEEKDAYS),
    Setting("dc.notify_hour", "Digest hour (0-23)", "Double chapters", "int", "DC_NOTIFY_HOUR", 9,
            hint="Container time zone"),
    Setting("dc.notify_always", "Also send when nothing is found", "Double chapters", "bool",
            "NTFY_ALWAYS_NOTIFY", True),
)

# Matches "official" but NOT "unofficial".
_OFFICIAL_RE = re.compile(r"(?<!un)official", re.IGNORECASE)
LOOSE_VOLUME_MARKERS = {"-100000", "-100000.0"}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _get(d: dict, *keys: str, default=None):
    """Case/spelling tolerant dict lookup (Kavita has shuffled field names between versions)."""
    for k in keys:
        if k in d:
            return d[k]
        for real in d:
            if real.lower() == k.lower():
                return d[real]
    return default


# --------------------------------------------------------------------------- Kavita
class KavitaError(RuntimeError):
    pass


class KavitaClient:
    """The parts of Kavita's API this tab needs (sync, token cached for ~2h)."""

    def __init__(self, base_url: str, api_key: str):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self._token: str | None = None
        self._token_ts = 0.0
        self._http = requests.Session()

    def authenticate(self, force: bool = False) -> str:
        if self._token and not force and time.time() - self._token_ts < 7200:
            return self._token
        if not self.base_url or not self.api_key:
            raise KavitaError("Kavita URL / API key are not set (Settings → Kavita)")
        resp = self._http.post(f"{self.base_url}/api/Plugin/authenticate",
                               params={"apiKey": self.api_key, "pluginName": "MangaManagerDoubleChapters"}, timeout=60)
        if resp.status_code != 200:
            raise KavitaError(f"Kavita authentication failed ({resp.status_code}): {resp.text[:300]}")
        token = _get(resp.json(), "token")
        if not token:
            raise KavitaError("Kavita authentication response did not contain a token")
        self._token, self._token_ts = token, time.time()
        return token

    def _request(self, method: str, path: str, retry_auth: bool = True, **kw) -> requests.Response:
        headers = {"Authorization": f"Bearer {self.authenticate()}", **(kw.pop("headers", None) or {})}
        try:
            resp = self._http.request(method, f"{self.base_url}{path}", headers=headers, timeout=kw.pop("timeout", 60), **kw)
        except requests.RequestException as exc:
            raise KavitaError(f"Kavita request failed: {exc}") from exc
        if resp.status_code == 401 and retry_auth:
            self.authenticate(force=True)
            return self._request(method, path, retry_auth=False, **kw)
        return resp

    def get_all_series(self, library_ids: list[int] | None = None) -> list[dict]:
        resp = self._request("POST", "/api/Series/all-v2",
                             json={"combination": 0, "sortOptions": {"sortField": 1}, "limitTo": 0})
        if resp.status_code != 200:
            raise KavitaError(f"Failed to list series ({resp.status_code}): {resp.text[:300]}")
        series = resp.json()
        if library_ids:
            series = [s for s in series if _get(s, "libraryId") in library_ids]
        return series

    def get_libraries(self) -> dict[int, str]:
        resp = self._request("GET", "/api/Library/libraries")
        if resp.status_code != 200:
            return {}
        return {_get(l, "id"): _get(l, "name", default="") for l in resp.json() if _get(l, "id") is not None}

    def get_series_volumes(self, series_id: int) -> list[dict]:
        resp = self._request("GET", "/api/Series/volumes", params={"seriesId": series_id})
        if resp.status_code != 200:
            raise KavitaError(f"Failed to get volumes for series {series_id} ({resp.status_code}): {resp.text[:300]}")
        return resp.json()

    def get_cover(self, series_id: int) -> tuple[bytes, str]:
        resp = self._request("GET", "/api/image/series-cover", params={"seriesId": series_id, "apiKey": self.api_key}, timeout=(5, 15))
        if resp.status_code != 200:
            raise KavitaError(f"Failed to fetch cover for series {series_id} ({resp.status_code})")
        return resp.content, resp.headers.get("content-type", "image/png")

    def get_chapter(self, chapter_id: int) -> dict:
        resp = self._request("GET", "/api/Series/Chapter", params={"chapterId": chapter_id})
        if resp.status_code != 200:
            raise KavitaError(f"Failed to get chapter {chapter_id} ({resp.status_code}): {resp.text[:300]}")
        return resp.json()

    def mark_chapter_read(self, series_id: int, chapter_id: int) -> bool:
        resp = self._request("POST", "/api/reader/mark-chapter-read",
                             params={"seriesId": series_id, "chapterId": chapter_id, "generateReadingSession": "false"},
                             json={"seriesId": series_id, "chapterId": chapter_id})
        return resp.status_code in (200, 204)

    def scan_series(self, series_id: int, library_id: int | None) -> bool:
        """Rescan one series; older Kavita versions fall back to a library scan."""
        resp = self._request("POST", "/api/Series/scan", json={"seriesId": series_id})
        if resp.status_code in (200, 204):
            return True
        if library_id is None:
            log.warning("Per-series scan failed (%s) for series %s and no libraryId was known", resp.status_code, series_id)
            return False
        log.warning("Per-series scan failed (%s), falling back to full library scan", resp.status_code)
        return self.scan_library(library_id)

    def scan_library(self, library_id: int) -> bool:
        resp = self._request("POST", "/api/Library/scan", params={"libraryId": library_id, "force": "false"})
        return resp.status_code in (200, 204)


def flatten_chapters(volumes: list[dict]) -> list[dict]:
    """Flat list of chapter dicts (with their files) across all volumes of a series."""
    chapters = []
    for vol in volumes:
        vol_name = str(_get(vol, "name", default="") or "")
        vol_number = _get(vol, "minNumber", "number", default=None)
        loose = vol_name.strip() in LOOSE_VOLUME_MARKERS or str(vol_number) in LOOSE_VOLUME_MARKERS
        for chap in _get(vol, "chapters", default=[]) or []:
            files = _get(chap, "files", default=[]) or []
            chapters.append({
                "id": _get(chap, "id"),
                "number": _get(chap, "number", default="") or "",
                "range": _get(chap, "range", default="") or "",
                "title": _get(chap, "title", "titleName", default="") or "",
                "isSpecial": bool(_get(chap, "isSpecial", default=False)),
                "sortOrder": _get(chap, "sortOrder", default=0),
                "volumeId": _get(vol, "id"),
                "volumeName": vol_name,
                "isLooseVolume": loose,
                "files": [{"id": _get(f, "id"), "filePath": _get(f, "filePath"), "bytes": _get(f, "bytes", default=0),
                           "pages": _get(f, "pages", default=0), "format": _get(f, "format")} for f in files],
            })
    return chapters


# --------------------------------------------------------------------------- digest
def _chapter_value(c: dict) -> float | None:
    for key in ("sortOrder", "number"):
        try:
            return float(c.get(key))
        except (TypeError, ValueError):
            continue
    return None


def _fmt_num(v: float) -> str:
    return str(int(v)) if float(v).is_integer() else f"{v:g}"


def compress_chapters(chapters: list[dict]) -> str:
    """`ch 12–14, 20`: runs of consecutive whole chapters become ranges, the rest stay single."""
    nums = sorted({_chapter_value(c) for c in chapters if _chapter_value(c) is not None})
    named = sorted({str(c.get("title") or c.get("range") or c.get("number") or "").strip()
                    for c in chapters if _chapter_value(c) is None} - {""})
    parts: list[str] = []
    i = 0
    while i < len(nums):
        j = i
        while j + 1 < len(nums) and nums[j].is_integer() and nums[j + 1].is_integer() and nums[j + 1] - nums[j] == 1:
            j += 1
        parts.append(_fmt_num(nums[i]) if i == j else f"{_fmt_num(nums[i])}–{_fmt_num(nums[j])}")
        i = j + 1
    parts += named
    if not parts:
        return f"{len(chapters)} chapter(s)"
    return "ch " + ", ".join(parts) if nums else ", ".join(parts)


def snapshot_keys(series: list[dict]) -> list[str]:
    return sorted(f"{s.get('id')}:{c.get('id')}" for s in series for c in s.get("chapters", []))


def build_digest(series: list[dict], previous_snapshot: list[str] | None, dashboard_url: str = "",
                 library_names: dict[Any, str] | None = None, action_token: str = "") -> dict:
    """Format the weekly ntfy digest. Pure: returns title, message, tags, priority and actions."""
    library_names = library_names or {}
    series = sorted(series, key=lambda s: (s.get("name") or "").lower())
    chapter_total = sum(len(s.get("chapters", [])) for s in series)
    current = set(snapshot_keys(series))

    delta = ""
    if previous_snapshot is not None:
        prev = set(previous_snapshot)
        added, fixed = len(current - prev), len(prev - current)
        delta = "No change since last week" if not (added or fixed) else f"+{added} new · −{fixed} fixed since last week"

    if not series:
        title = "✅ Double chapters · all clean"
        message = "No chapters with more than one file. The library looks tidy."
        if delta:
            message += f"\n\n{delta}"
        tags, priority = ["white_check_mark"], 2
    else:
        count = len(series)
        title = f"📚 Weekly double chapters · {count} series"
        header = f"**{count} series · {chapter_total} chapter{'s' if chapter_total != 1 else ''}** with more than one file\n"
        if delta:
            header += f"\n{delta}\n"
        header += "\n"
        groups: dict[Any, list[dict]] = {}
        for s in series:
            groups.setdefault(s.get("libraryId"), []).append(s)
        lines: list[str] = []
        for lib_id, members in sorted(groups.items(), key=lambda kv: str(library_names.get(kv[0], kv[0]) or "")):
            if len(groups) > 1 or lib_id in library_names:
                label = library_names.get(lib_id) or (f"Library {lib_id}" if lib_id is not None else "Other")
                lines.append(f"\n**{label}** ({len(members)})" if lines else f"**{label}** ({len(members)})")
            for s in members:
                lines.append(f"- {s.get('name')} · {compress_chapters(s['chapters'])}")
        message = notify.truncate_lines(header, lines, "…and {n} more lines")
        tags, priority = (["warning"], 4) if count >= ADD_MANY_THRESHOLD else (["books"], 3)

    actions: list[dict] = []
    base = (dashboard_url or "").rstrip("/")
    if base:
        actions = [
            {"action": "view", "label": "Open dashboard", "url": f"{base}/double-chapters"},
            {"action": "http", "label": "Rescan", "url": f"{base}/api/double-chapters/scan", "method": "POST", "clear": True,
             **({"headers": {"X-Action-Token": action_token}} if action_token else {})},
        ]
    return {"title": title, "message": message, "tags": tags, "priority": priority, "actions": actions}


def iso_week(now: datetime) -> str:
    y, w, _ = now.isocalendar()
    return f"{y}-W{w:02d}"


def digest_due(now: datetime, weekday: int, hour: int, last_week: str | None) -> bool:
    """Due once per ISO week (Mon-Sun) at weekday/hour; a missed one is sent at the next check."""
    return last_week != iso_week(now) and (now.weekday(), now.hour) >= (weekday, hour)


# --------------------------------------------------------------------------- tool
class DoubleChapters:
    def __init__(self, ctx):
        self.ctx = ctx
        self.db = ctx.db
        self.lock = threading.RLock()
        self.bulk = BulkProcessor(self)
        self.covers = ImageCache(Path(ctx.cfg.data_dir) / "double-chapters" / "covers")
        self.scanning = False
        self.scan_done = 0
        self.scan_total = 0
        self.last_error: str | None = None
        self._client: KavitaClient | None = None
        self._client_key: tuple | None = None

    # -- settings / paths ---------------------------------------------------------
    def s(self, key: str, default=None):
        return self.ctx.settings.get(key, default)

    @property
    def client(self) -> KavitaClient:
        key = (self.ctx.cfg.kavita_url, self.ctx.cfg.kavita_api_key)
        if self._client is None or key != self._client_key:
            self._client, self._client_key = KavitaClient(*key), key
        return self._client

    @property
    def manga_root(self) -> str:
        root = str(self.s("dc.manga_root") or "/manga")
        # Older versions saved Compose's host-side MANGA_ROOT as a container setting.
        # Recover only that exact legacy collision, and only when the standard mount exists.
        host_root = os.environ.get("MANGA_ROOT", "")
        if (os.environ.get("DC_MANGA_ROOT", "") in ("", "/manga") and root == host_root and root != "/manga"
                and os.path.isdir("/manga") and not os.path.exists(root)):
            return "/manga"
        return root

    @property
    def kavita_root(self) -> str:
        return str(self.s("dc.kavita_manga_root") or self.manga_root)

    @property
    def trash_dir(self) -> Path:
        return Path(self.ctx.cfg.data_dir) / "double-chapters" / "trash"

    def library_ids(self) -> list[int]:
        return [int(x) for x in str(self.s("dc.library_ids") or "").split(",") if x.strip().isdigit()]

    def kavita_path_to_local(self, path: str) -> str:
        if not isinstance(path, str) or not path:
            raise ValueError("File path is missing")
        path = os.path.normpath(path)
        kroot, lroot = os.path.normpath(self.kavita_root), os.path.normpath(self.manga_root)
        if path == kroot or path.startswith(kroot.rstrip(os.sep) + os.sep):
            return os.path.normpath(os.path.join(lroot, os.path.relpath(path, kroot)))
        return path

    def is_safe_path(self, local_path: str) -> bool:
        root, target = os.path.realpath(self.manga_root), os.path.realpath(local_path)
        try:
            return os.path.commonpath([root, target]) == root
        except ValueError:
            return False

    def is_in_trash(self, path: str) -> bool:
        root, target = os.path.realpath(self.trash_dir), os.path.realpath(path)
        return target.startswith(root + os.sep)

    # -- state ------------------------------------------------------------------
    def load(self, key: str, default):
        return self.db.kv_get(NS, key, default)

    def save(self, key: str, value) -> None:
        self.db.kv_set(NS, key, value)

    def meta(self) -> dict:
        return self.load("meta", {})

    def update_meta(self, **kw) -> None:
        with self.lock:
            meta = self.meta()
            meta.update(kw)
            self.save("meta", meta)

    # -- scanning ---------------------------------------------------------------
    def enrich_chapter(self, chapter: dict) -> dict:
        """Add on-disk info to each file and flag the newest / largest / official one."""
        for f in chapter["files"]:
            local = self.kavita_path_to_local(f["filePath"])
            f["localPath"] = local
            try:
                f["modifiedAt"] = datetime.fromtimestamp(os.stat(local).st_mtime, tz=timezone.utc).isoformat()
            except OSError:
                f["modifiedAt"] = None
            f["isOfficial"] = bool(_OFFICIAL_RE.search(os.path.basename(f["filePath"])))
        files = chapter["files"]
        if files:
            newest = max((f for f in files if f["modifiedAt"]), key=lambda f: f["modifiedAt"], default=None)
            largest = max(files, key=lambda f: f["bytes"] or 0)
            for f in files:
                f["isNewest"] = newest is not None and f["id"] == newest["id"]
                f["isLargest"] = f["id"] == largest["id"]
            files.sort(key=lambda f: f["modifiedAt"] or "", reverse=True)
            files.sort(key=lambda f: f["isOfficial"], reverse=True)
        return chapter

    def _duplicates(self, volumes: list[dict]) -> list[dict]:
        dups = [c for c in flatten_chapters(volumes) if len(c["files"]) > 1]
        if self.s("dc.require_different_size"):
            dups = [c for c in dups if len({f["bytes"] for f in c["files"]}) > 1]
        return [self.enrich_chapter(c) for c in dups]

    def run_scan(self) -> None:
        with self.lock:
            if self.scanning or self.bulk.running:
                return
            self.scanning, self.last_error = True, None
        self.scan_done = self.scan_total = 0
        try:
            series_list = self.client.get_all_series(self.library_ids() or None)
            self.scan_total = len(series_list)
            results: dict[str, dict] = {}
            for s in series_list:
                sid = s.get("id")
                if sid is None:
                    self.scan_done += 1
                    continue
                try:
                    dups = self._duplicates(self.client.get_series_volumes(sid))
                except KavitaError as exc:
                    log.warning("Skipping series %s (%s): %s", sid, s.get("name"), exc)
                    self.scan_done += 1
                    continue
                if dups:
                    results[str(sid)] = {"id": sid, "name": s.get("name"), "libraryId": s.get("libraryId"), "chapters": dups}
                self.scan_done += 1
            with self.lock:
                self.save("series", results)
                self.update_meta(lastScan=now_iso())
            self.db.log_activity("double_chapters", "scan", f"Scan found {len(results)} series with double chapters")
        except Exception as exc:  # noqa: BLE001
            log.exception("Scan failed")
            self.last_error = str(exc)
        finally:
            self.scanning = False

    def rescan_single_series(self, series_id: int) -> None:
        try:
            dups = self._duplicates(self.client.get_series_volumes(series_id))
        except KavitaError as exc:
            log.warning("Could not refresh series %s: %s", series_id, exc)
            return
        with self.lock:
            series = self.load("series", {})
            key = str(series_id)
            if dups:
                series.setdefault(key, {}).update({"id": series_id, "chapters": dups})
            else:
                series.pop(key, None)
            self.save("series", series)

    # -- remediation ------------------------------------------------------------
    def remediate_chapter(self, series_id: int, chapter_id: int, keep_file_id: int, *, _bulk=False) -> dict:
        """Move every file but `keep_file_id` to the trash. Kavita is rescanned by the caller, once per batch."""
        with self.lock:
            if self.scanning or (self.bulk.running and not _bulk):
                raise BulkConflict("A scan or bulk operation is running. Wait for it to finish.")
            all_series = self.load("series", {})
            trash = self.load("trash", {})
            key = str(series_id)
            series = all_series.get(key)
            if not series:
                raise ValueError("Series not currently flagged as having duplicates")
            chapter = next((c for c in series["chapters"] if c["id"] == chapter_id), None)
            if not chapter:
                raise ValueError("Chapter not found in current scan results")
            if not any(f["id"] == keep_file_id for f in chapter["files"]):
                raise ValueError("keepFileId does not match any file on this chapter")

            kept = next(f for f in chapter["files"] if f["id"] == keep_file_id)
            keep_path = self.kavita_path_to_local(kept["filePath"])
            if not self.is_safe_path(keep_path) or not os.path.isfile(keep_path):
                raise ValueError("The file to keep is missing or outside the manga folder. Rescan first.")

            moved = []
            remaining = [kept]
            for f in chapter["files"]:
                if f["id"] == keep_file_id:
                    continue
                local = self.kavita_path_to_local(f["filePath"])
                if not self.is_safe_path(local):
                    log.error("Refusing to touch path outside the manga folder: %s", local)
                    remaining.append(f)
                    continue
                if not os.path.exists(local):
                    log.warning("File no longer exists on disk, skipping: %s", local)
                    continue
                batch = self.trash_dir / time.strftime("%Y%m%dT%H%M%S")
                batch.mkdir(parents=True, exist_ok=True)
                dest = batch / os.path.basename(local)
                i = 1
                while dest.exists():
                    dest = batch / f"{dest.stem.rsplit('__', 1)[0]}__{i}{dest.suffix}"
                    i += 1
                shutil.move(local, dest)
                entry = {"id": str(uuid.uuid4()), "originalPath": local, "trashPath": str(dest), "seriesId": series_id,
                         "seriesName": series.get("name"), "libraryId": series.get("libraryId"), "chapterId": chapter_id,
                         "size": f["bytes"], "deletedAt": now_iso()}
                trash[entry["id"]] = entry
                # Persist every successful move, even if a later file in this batch fails.
                self.save("trash", trash)
                moved.append(entry)

            if len(remaining) > 1:
                chapter["files"] = remaining
            else:
                series["chapters"] = [c for c in series["chapters"] if c["id"] != chapter_id]
            if not series["chapters"]:
                all_series.pop(key, None)
            self.save("series", all_series)
            self.save("trash", trash)
        if moved:
            self.db.log_activity("double_chapters", "trash", f"Trashed {len(moved)} file(s) from {series.get('name')}")
        return {"moved": moved, "libraryId": series.get("libraryId")}

    def reconcile_read_status(self, series_id: int, chapters: list[dict]) -> None:
        """After a rescan, re-mark remediated chapters as read when the new page count broke their progress."""
        for item in chapters:
            chapter_id, expected = item.get("chapterId"), item.get("expectedPages")
            if chapter_id is None:
                continue
            chapter = None
            for _ in range(10):
                try:
                    candidate = self.client.get_chapter(chapter_id)
                except KavitaError as exc:
                    log.warning("Could not fetch chapter %s to reconcile read status: %s", chapter_id, exc)
                    break
                if expected is None or candidate.get("pages") == expected:
                    chapter = candidate
                    break
                time.sleep(1.5)
            if chapter is None:
                continue
            pages, read = chapter.get("pages"), chapter.get("pagesRead")
            if read and pages and 0 < read != pages:
                try:
                    self.client.mark_chapter_read(series_id, chapter_id)
                except KavitaError as exc:
                    log.warning("Failed to mark chapter %s as read: %s", chapter_id, exc)

    # -- trash ------------------------------------------------------------------
    def restore_trash(self, trash_id: str) -> dict:
        with self.lock:
            trash = self.load("trash", {})
            entry = trash.get(trash_id)
            if not entry:
                raise ValueError("Unknown trash entry")
            if not (self.is_safe_path(entry["originalPath"]) and self.is_in_trash(entry["trashPath"])):
                raise ValueError("Trash entry points outside the manga or trash folder")
            if os.path.exists(entry["originalPath"]):
                raise ValueError("A file already exists at the original location")
            os.makedirs(os.path.dirname(entry["originalPath"]), exist_ok=True)
            shutil.move(entry["trashPath"], entry["originalPath"])
            del trash[trash_id]
            self.save("trash", trash)
        try:
            self.client.scan_series(entry["seriesId"], entry.get("libraryId"))
        except Exception:  # noqa: BLE001
            pass
        self.rescan_single_series(entry["seriesId"])
        return entry

    def purge_trash_many(self, ids: list[str]) -> None:
        with self.lock:
            trash = self.load("trash", {})
            for tid in ids:
                entry = trash.pop(tid, None)
                if not entry:
                    continue
                try:
                    if not self.is_in_trash(entry["trashPath"]):
                        log.warning("Not purging %s: outside the trash folder", entry["trashPath"])
                        continue
                    if os.path.exists(entry["trashPath"]):
                        os.remove(entry["trashPath"])
                except OSError as exc:
                    log.warning("Could not purge %s: %s", entry["trashPath"], exc)
            self.save("trash", trash)

    def purge_expired_trash(self) -> list[str]:
        days = int(self.s("dc.trash_retention_days") or 0)
        if days <= 0:
            return []
        cutoff = time.time() - days * 86400
        expired = []
        for tid, entry in self.load("trash", {}).items():
            try:
                if datetime.fromisoformat(entry["deletedAt"]).timestamp() <= cutoff:
                    expired.append(tid)
            except (KeyError, ValueError):
                continue
        if expired:
            log.info("Auto-purging %d trashed file(s) older than %d days", len(expired), days)
            self.purge_trash_many(expired)
        return expired

    # -- digest -----------------------------------------------------------------
    def library_names(self) -> dict[int, str]:
        try:
            return self.client.get_libraries()
        except Exception:  # noqa: BLE001
            return {}

    def digest(self, previous: list[str] | None = None) -> dict:
        series = list(self.load("series", {}).values())
        return build_digest(series, previous, self.ctx.dashboard_url(), self.library_names(),
                            self.ctx.extras.get("action_token", ""))

    def send_digest(self, force: bool = False) -> bool:
        """Send the digest. `force` (the test button) neither respects the 'nothing found' rule nor moves the week/snapshot."""
        series = list(self.load("series", {}).values())
        if not series and not (force or self.s("dc.notify_always")):
            return False
        meta = self.meta()
        digest = build_digest(series, meta.get("lastDigestSnapshot"), self.ctx.dashboard_url(), self.library_names(),
                               self.ctx.extras.get("action_token", ""))
        base = self.ctx.dashboard_url()
        self.ctx.ntfy_publish(str(self.s(TOPIC_KEY) or "Double_Chapters"), digest["title"], digest["message"],
                              tags=digest["tags"], priority=digest["priority"], actions=digest["actions"],
                              click=f"{base}/double-chapters" if base else "")
        self.db.log_activity("double_chapters", "notify", digest["title"])
        return True

    def notify_tick(self) -> None:
        """Hourly-ish check: send this week's digest once it is due and a scan has finished."""
        if not (self.s("dc.notify_enabled") and self.ctx.cfg.ntfy_enabled):
            return
        meta = self.meta()
        now = datetime.now().astimezone()
        try:
            weekday, hour = int(self.s("dc.notify_weekday")), int(self.s("dc.notify_hour"))
        except (TypeError, ValueError):
            weekday, hour = 0, 9
        if not (digest_due(now, weekday, hour, meta.get("lastDigestWeek")) and meta.get("lastScan") and not self.scanning):
            return
        self.send_digest()
        self.update_meta(lastDigestWeek=iso_week(now), lastDigestSnapshot=snapshot_keys(list(self.load("series", {}).values())))
        log.info("Weekly double-chapters digest handled for %s", iso_week(now))

    # -- views ------------------------------------------------------------------
    def status(self) -> dict:
        meta = self.meta()
        return {
            "lastScan": meta.get("lastScan"), "scanning": self.scanning, "scanDone": self.scan_done,
            "scanTotal": self.scan_total, "lastError": self.last_error,
            "seriesWithDuplicates": len(self.load("series", {})),
            "scanIntervalMinutes": int(self.s("dc.scan_interval_minutes") or 15),
            "trashRetentionDays": int(self.s("dc.trash_retention_days") or 0),
            "ntfyConfigured": bool(self.ctx.cfg.ntfy_enabled and self.ctx.cfg.ntfy_url),
            "digestEnabled": bool(self.s("dc.notify_enabled")),
            "digestWeekday": dict(WEEKDAYS).get(str(self.s("dc.notify_weekday")), "Monday"),
            "digestHour": int(self.s("dc.notify_hour") or 0),
            "lastDigestWeek": meta.get("lastDigestWeek"),
            "kavitaConfigured": bool(self.ctx.cfg.kavita_url and self.ctx.cfg.kavita_api_key),
            "bulk": self.bulk.status(),
        }

    def trash_list(self) -> list[dict]:
        days = int(self.s("dc.trash_retention_days") or 0)
        out = []
        for e in sorted(self.load("trash", {}).values(), key=lambda e: e["deletedAt"], reverse=True):
            e = dict(e)
            e["expiresAt"] = (datetime.fromisoformat(e["deletedAt"]) + timedelta(days=days)).isoformat() if days > 0 else None
            out.append(e)
        return out


# --------------------------------------------------------------------------- legacy import
def import_legacy(ctx, directory) -> str:
    """Import an old Kavita-Double-Chapters /config folder (state.json + trash/) into Postgres."""
    import json
    directory = Path(directory)
    state_file = directory / "state.json"
    if not state_file.exists():
        return f"Double chapters: no state.json in {directory}, nothing imported"
    data = json.loads(state_file.read_text())
    tool = DoubleChapters(ctx)
    trash_dir = tool.trash_dir
    trash: dict[str, dict] = {}
    copied = 0
    for tid, entry in (data.get("trash") or {}).items():
        entry = dict(entry)
        rel = entry.get("trashPath", "").split("/trash/", 1)[-1]
        src = directory / "trash" / rel
        if src.exists():
            dest = trash_dir / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            entry["trashPath"] = str(dest)
            copied += 1
        trash[tid] = entry
    tool.save("series", data.get("series") or {})
    tool.save("trash", trash)
    if data.get("lastScan"):
        tool.update_meta(lastScan=data["lastScan"])
    return (f"Double chapters: imported {len(data.get('series') or {})} series and {len(trash)} trashed file(s) "
           f"({copied} file(s) copied)")


# --------------------------------------------------------------------------- blueprint
def init(ctx) -> Blueprint:
    tool = DoubleChapters(ctx)
    bp = Blueprint("double_chapters", __name__)

    ctx.jobs.add("dc-scan", tool.run_scan, lambda: max(1, int(tool.s("dc.scan_interval_minutes") or 15)) * 60)
    ctx.jobs.add("dc-trash", tool.purge_expired_trash, 3600, run_on_start=True)
    ctx.jobs.add("dc-digest", tool.notify_tick, 600, run_on_start=True)
    ctx.extras["double_chapters"] = tool

    def err(message: str, code: int = 400):
        return jsonify({"error": message}), code

    @bp.before_request
    def protect_bulk_work():
        if request.method in ("POST", "PUT", "PATCH", "DELETE") and tool.bulk.running:
            return err("Bulk processing is running. Wait for it to finish.", 409)

    def bulk_signer():
        return URLSafeTimedSerializer(current_app.secret_key, salt="double-chapters-bulk-preview")

    @bp.post("/api/double-chapters/bulk-preview")
    def bulk_preview():
        try:
            plan = tool.bulk.preview()
        except BulkConflict as exc:
            return err(str(exc), 409)
        plan["token"] = bulk_signer().dumps({"fingerprint": plan.pop("fingerprint"), "user": session.get("user")})
        return jsonify(plan)

    @bp.post("/api/double-chapters/bulk-apply")
    def bulk_apply():
        body = request.get_json(silent=True)
        if not isinstance(body, dict) or not isinstance(body.get("token"), str):
            return err("Review a preview before applying.")
        try:
            data = bulk_signer().loads(body["token"], max_age=900)
            if not isinstance(data, dict) or data.get("user") != session.get("user"):
                return err("This preview belongs to another session.", 403)
            return jsonify(tool.bulk.start(data.get("fingerprint"), body.get("selections"))), 202
        except SignatureExpired:
            return err("The preview expired. Open a new preview.", 409)
        except BadSignature:
            return err("Invalid preview. Open a new preview.")
        except BulkConflict as exc:
            return err(str(exc), 409)
        except ValueError as exc:
            return err(str(exc))

    @bp.get("/api/double-chapters/bulk-status")
    def bulk_status():
        return jsonify(tool.bulk.status())

    @bp.get("/double-chapters")
    def page():
        return render_template("double_chapters.html", page_title="Double chapters", active="double",
                               user=session.get("user") if ctx.cfg.auth_enabled else None, version=__version__)

    @bp.get("/api/double-chapters/status")
    def status():
        return jsonify(tool.status())

    @bp.post("/api/double-chapters/scan")
    def scan():
        return jsonify({"started": ctx.jobs.trigger("dc-scan")})

    @bp.post("/api/double-chapters/notify-test")
    def notify_test():
        try:
            tool.send_digest(force=True)
        except notify.NtfyError as exc:
            return err(str(exc), 502)
        return jsonify({"sent": True})

    @bp.get("/api/double-chapters/digest-preview")
    def digest_preview():
        return jsonify(tool.digest(tool.meta().get("lastDigestSnapshot")))

    @bp.get("/api/double-chapters/series")
    def list_series():
        out = [{"id": s["id"], "name": s["name"], "libraryId": s.get("libraryId"), "chapterCount": len(s["chapters"])}
               for s in tool.load("series", {}).values()]
        out.sort(key=lambda x: (x["name"] or "").lower())
        return jsonify(out)

    @bp.get("/api/double-chapters/series/<int:series_id>")
    def series_detail(series_id: int):
        s = tool.load("series", {}).get(str(series_id))
        if not s:
            return err("Series has no duplicate chapters (or was not scanned yet)", 404)
        return jsonify(s)

    @bp.get("/api/double-chapters/cover/<int:series_id>")
    def cover(series_id: int):
        try:
            key = f"{ctx.cfg.kavita_url}:{ctx.cfg.kavita_api_key}:{series_id}"
            data, content_type = tool.covers.get(key, lambda: tool.client.get_cover(series_id))
        except ImageBusy as exc:
            return jsonify({"error": str(exc)}), 503, {"Retry-After": "2", "Cache-Control": "no-store"}
        except KavitaError as exc:
            return err(str(exc), 502)
        response = Response(data, mimetype=content_type, headers={"Cache-Control": "private, max-age=3600"})
        response.set_etag(hashlib.sha256(data).hexdigest())
        return response.make_conditional(request)

    @bp.post("/api/double-chapters/series/<int:series_id>/chapters/<int:chapter_id>/remediate")
    def remediate(series_id: int, chapter_id: int):
        body = request.get_json(silent=True) or {}
        try:
            return jsonify(tool.remediate_chapter(series_id, chapter_id, int(body.get("keepFileId", -1))))
        except BulkConflict as exc:
            return err(str(exc), 409)
        except (ValueError, TypeError) as exc:
            return err(str(exc))

    @bp.post("/api/double-chapters/series/<int:series_id>/kavita-scan")
    def kavita_scan(series_id: int):
        body = request.get_json(silent=True) or {}
        try:
            tool.client.scan_series(series_id, body.get("libraryId"))
        except KavitaError as exc:
            return err(str(exc), 502)
        if body.get("chapters"):
            try:
                tool.reconcile_read_status(series_id, body["chapters"])
            except Exception:  # noqa: BLE001 - never fail the request over this
                log.exception("Read-status reconciliation failed for series %s", series_id)
        return jsonify({"scanned": True})

    @bp.get("/api/double-chapters/trash")
    def trash():
        return jsonify(tool.trash_list())

    @bp.post("/api/double-chapters/trash/<trash_id>/restore")
    def trash_restore(trash_id: str):
        try:
            return jsonify(tool.restore_trash(trash_id))
        except ValueError as exc:
            return err(str(exc))

    @bp.post("/api/double-chapters/trash/<trash_id>/purge")
    def trash_purge(trash_id: str):
        tool.purge_trash_many([trash_id])
        return jsonify({"purged": trash_id})

    @bp.post("/api/double-chapters/trash/purge-all")
    def trash_purge_all():
        tool.purge_trash_many(list(tool.load("trash", {}).keys()))
        return jsonify({"purged": "all"})

    return bp
