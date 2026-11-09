"""Preview and apply the Double Chapters default choice across the current scan."""
from __future__ import annotations

import copy
import hashlib
import json
import logging
import os
import threading
from pathlib import Path

log = logging.getLogger(__name__)


class BulkConflict(ValueError):
    """Another operation is running, or the reviewed plan has changed."""


class BulkProcessor:
    def __init__(self, tool):
        self.tool = tool
        self.running = False
        self.result = {"running": False, "done": 0, "total": 0, "moved": 0,
                       "failed": 0, "errors": [], "warnings": [], "phase": "Ready"}

    def chapter_plan(self, series, chapter, keep_file_id=None):
        """Refresh file metadata; refuse a chapter unless every file is safe and present."""
        chapter = copy.deepcopy(chapter)
        files = chapter.get("files", [])
        if len(files) < 2:
            raise ValueError("Fewer than two files remain")
        paths, ids = set(), set()
        for f in files:
            local = self.tool.kavita_path_to_local(f["filePath"])
            if not self.tool.is_safe_path(local):
                raise ValueError("A file is outside the manga folder")
            real = os.path.realpath(local)
            if real in paths or f["id"] in ids:
                raise ValueError("Repeated file path or ID; review manually")
            paths.add(real)
            ids.add(f["id"])
            if not Path(local).is_file():
                raise ValueError("A file is missing or unavailable; rescan first")
            stat = os.stat(local)
            f["bytes"] = stat.st_size
            # Detect replacement, editing or a remount between review and execution.
            f["signature"] = [stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns]
        chapter = self.tool.enrich_chapter(chapter)
        files = chapter["files"]
        keep = next((f for f in files if f.get("isOfficial")), None)
        reason = "Official (newest official when several exist)"
        if keep is None:
            keep = next((f for f in files if f.get("isNewest")), files[0])
            reason = "Newest available file"
        if keep_file_id is not None and keep_file_id != keep["id"]:
            keep = next((f for f in files if f["id"] == keep_file_id), None)
            if keep is None:
                raise ValueError("Selected file does not belong to this chapter")
            reason = "Selected in preview"
        def file_info(f):
            return {"id": f["id"], "filePath": f["filePath"], "bytes": f["bytes"],
                    "pages": f.get("pages"), "signature": f["signature"],
                    "modifiedAt": f.get("modifiedAt"), "isOfficial": f.get("isOfficial", False),
                    "isNewest": f.get("isNewest", False), "isLargest": f.get("isLargest", False)}
        return {"seriesId": series["id"], "seriesName": series.get("name") or "Untitled",
                "libraryId": series.get("libraryId"), "chapterId": chapter["id"],
                "chapterLabel": chapter.get("title") or f"Chapter {chapter.get('range') or chapter.get('number') or '?'}",
                "keep": file_info(keep), "trash": [file_info(f) for f in files if f["id"] != keep["id"]],
                "reason": reason}

    def preview(self):
        with self.tool.lock:
            if self.running or self.tool.scanning:
                raise BulkConflict("A scan or bulk operation is running. Wait for it to finish.")
            return self._preview()

    def _preview(self):
        plans, skipped = [], []
        series = sorted(self.tool.load("series", {}).values(), key=lambda s: str(s["id"]))
        for s in series:
            for c in sorted(s.get("chapters", []), key=lambda c: str(c["id"])):
                try:
                    plans.append(self.chapter_plan(s, c))
                except (OSError, ValueError, KeyError, TypeError) as exc:
                    skipped.append({"seriesName": s.get("name"), "chapterId": c.get("id"), "reason": str(exc)})
        # A shared path between chapters could otherwise trash a file kept by another chapter.
        uses = {}
        for s in series:
            for f in (f for c in s.get("chapters", []) for f in c.get("files", [])):
                try:
                    key = os.path.realpath(self.tool.kavita_path_to_local(f["filePath"]))
                except (KeyError, TypeError, ValueError):
                    continue
                uses[key] = uses.get(key, 0) + 1
        safe = []
        for plan in plans:
            if any(uses[os.path.realpath(self.tool.kavita_path_to_local(f["filePath"]))] > 1
                   for f in [plan["keep"], *plan["trash"]]):
                skipped.append({"seriesName": plan["seriesName"], "chapterId": plan["chapterId"],
                                "reason": "A file is shared by multiple chapters; review manually"})
            else:
                safe.append(plan)
        data = {"chapters": safe, "skipped": skipped,
                "seriesCount": len({p["seriesId"] for p in safe}), "chapterCount": len(safe),
                "fileCount": sum(len(p["trash"]) for p in safe),
                "bytes": sum(f["bytes"] for p in safe for f in p["trash"]),
                "trashRetentionDays": int(self.tool.s("dc.trash_retention_days") or 0),
                "mangaRoot": self.tool.manga_root, "kavitaRoot": self.tool.kavita_root,
                "lastScan": self.tool.meta().get("lastScan")}
        data["fingerprint"] = self.fingerprint(data)
        return data

    @staticmethod
    def fingerprint(data):
        return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def start(self, fingerprint, selections=None):
        with self.tool.lock:
            if self.running or self.tool.scanning:
                raise BulkConflict("A scan or bulk operation is already running.")
            plan = self._preview()
            if plan["fingerprint"] != fingerprint:
                raise BulkConflict("The files or scan changed. Open a new preview before applying.")
            if not plan["chapters"]:
                raise BulkConflict("There are no safe duplicate chapters to process.")
            if selections is not None:
                if not isinstance(selections, list) or len(selections) != len(plan["chapters"]):
                    raise ValueError("Review a choice for every chapter")
                choices = {}
                for choice in selections:
                    if not isinstance(choice, dict) or any(type(choice.get(k)) is not int
                            for k in ("seriesId", "chapterId", "keepFileId")):
                        raise ValueError("Invalid chapter choice")
                    key = (choice["seriesId"], choice["chapterId"])
                    if key in choices:
                        raise ValueError("Repeated chapter choice")
                    choices[key] = choice["keepFileId"]
                expected = {(p["seriesId"], p["chapterId"]) for p in plan["chapters"]}
                if set(choices) != expected:
                    raise ValueError("Choices do not match the reviewed chapters")
                current = self.tool.load("series", {})
                plan["chapters"] = [self.chapter_plan(current[str(p["seriesId"])], next(
                    c for c in current[str(p["seriesId"])]["chapters"] if c["id"] == p["chapterId"]),
                    choices[(p["seriesId"], p["chapterId"])]) for p in plan["chapters"]]
            self.running = True
            self.result = {"running": True, "done": 0, "total": len(plan["chapters"]),
                           "moved": 0, "failed": 0, "errors": [], "warnings": [], "phase": "Processing"}
            try:
                threading.Thread(target=self._run, args=(plan["chapters"],),
                                 name="dc-process-all", daemon=True).start()
            except Exception:
                self.running = False
                self.result["running"] = False
                raise
            return dict(self.result)

    def _run(self, plans):
        scheduler = self.tool.ctx.extras.get("scheduler")
        try:
            if scheduler is None:
                self._process(plans)
                ran = True
            else:
                ran, _ = scheduler.run_exclusive(lambda: self._process(plans))
            if not ran:
                self.result["errors"].append("Move Manga is moving files. No bulk changes were made. Try again after it finishes.")
        except Exception as exc:
            log.exception("Could not reserve the manga file-operation lock")
            self.result["errors"].append(f"Could not start bulk processing: {exc}")
        finally:
            with self.tool.lock:
                self.running = False
                self.result["running"] = False
                self.result["phase"] = "Finished"

    def _process(self, plans):
        affected = {}
        try:
            for plan in plans:
                before_trash = set(self.tool.load("trash", {}))
                try:
                    with self.tool.lock:
                        series = self.tool.load("series", {}).get(str(plan["seriesId"]))
                        if not series:
                            raise ValueError("Series changed after the preview")
                        chapter = next((c for c in series["chapters"] if c["id"] == plan["chapterId"]), None)
                        if chapter is None or self.chapter_plan(series, chapter, plan["keep"]["id"]) != plan:
                            raise ValueError("Chapter files changed after the preview; skipped")
                        result = self.tool.remediate_chapter(plan["seriesId"], plan["chapterId"],
                                                           plan["keep"]["id"], _bulk=True)
                        self.result["moved"] += len(result["moved"])
                    affected.setdefault(plan["seriesId"], {"libraryId": plan["libraryId"], "chapters": []})[
                        "chapters"].append({"chapterId": plan["chapterId"], "expectedPages": plan["keep"]["pages"]})
                except Exception as exc:
                    log.exception("Bulk processing failed for %s chapter %s", plan["seriesId"], plan["chapterId"])
                    self.result["failed"] += 1
                    self.result["errors"].append(f"{plan['seriesName']} · {plan['chapterLabel']}: {exc}")
                    moved = set(self.tool.load("trash", {})) - before_trash
                    if moved:
                        self.result["moved"] += len(moved)
                        affected.setdefault(plan["seriesId"], {"libraryId": plan["libraryId"], "chapters": []})
                finally:
                    self.result["done"] += 1
            self.result["phase"] = "Rescanning Kavita"
            for sid, batch in affected.items():
                try:
                    if not self.tool.client.scan_series(sid, batch["libraryId"]):
                        raise RuntimeError("Kavita did not accept the rescan")
                    self.tool.reconcile_read_status(sid, batch["chapters"])
                except Exception as exc:
                    self.result["warnings"].append(f"Series {sid}: files processed, but Kavita refresh failed: {exc}")
        except Exception as exc:
            log.exception("Bulk processing could not finish")
            self.result["errors"].append(f"Processing stopped: {exc}. Rescan before retrying.")

    def status(self):
        # Do not hold the file-operation lock while polling progress.
        return copy.deepcopy(self.result)
