"""Preferred sources: official / uncensored versions replace the same chapter from a lower-ranked source.

Rank 0 is a normal source, 1 official, 2 uncensored, 3 official uncensored. A chapter that arrives from a
higher-ranked source than the file already in Kavita replaces it; the old file goes to the shared trash
(restorable on the Doubles tab). A chapter from a lower-ranked source than the file Kavita has is dropped
the same way. Equal ranks keep the old behaviour (both files, shown as a duplicate).
"""
from __future__ import annotations

import logging
import re
import shutil
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .comicinfo import chapter_key, chapter_label_fast
from .settings import Setting, register

log = logging.getLogger(__name__)

RANK_LABELS = {0: "Normal", 1: "Official", 2: "Uncensored", 3: "Official uncensored"}
_OFFICIAL = re.compile(r"\bofficial\b", re.I)
_UNCENSORED = re.compile(r"\buncensored\b", re.I)
_IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp")

register(
    Setting("src.enabled", "Replace chapters with official versions", "Preferred sources", "bool", "SRC_ENABLED", True,
            hint="Official / uncensored chapters replace the same chapter from a normal source", attr="src_enabled"),
    Setting("src.auto_detect", "Recognise (Official) / (Uncensored) in the title", "Preferred sources", "bool",
            "SRC_AUTO_DETECT", True, attr="src_auto_detect"),
    Setting("src.min_page_pct", "Keep both files below this page share (%)", "Preferred sources", "int",
            "SRC_MIN_PAGE_PCT", 60, hint="If the preferred file has fewer pages than this share of the old one, nothing is replaced",
            attr="src_min_page_pct"),
)


def detect_rank(title: str | None) -> int:
    """1 for Official, 2 for Uncensored, 3 for both, in the Suwayomi title."""
    t = title or ""
    return (1 if _OFFICIAL.search(t) else 0) + (2 if _UNCENSORED.search(t) else 0)


def count_pages(path: Path) -> int:
    try:
        with zipfile.ZipFile(path) as zf:
            return sum(1 for n in zf.namelist() if n.lower().endswith(_IMAGE_EXT))
    except (zipfile.BadZipFile, OSError):
        return 0


@dataclass
class Plan:
    replace: list[tuple[Path, Path, str]] = field(default_factory=list)   # (incoming, old file in Kavita, label)
    drop: list[tuple[Path, Path, str]] = field(default_factory=list)      # (incoming, better file in Kavita, label)
    flagged: list[tuple[str, str]] = field(default_factory=list)          # (label, why both files are kept)

    @property
    def replaced_labels(self) -> set[str]:
        return {label for _, _, label in self.replace}

    @property
    def dropped(self) -> set[Path]:
        return {incoming for incoming, _, _ in self.drop}

    def summary(self) -> dict:
        return {"replace": len(self.replace), "drop": len(self.drop), "flagged": len(self.flagged),
                "replace_chapters": sorted({l for _, _, l in self.replace}, key=_sort),
                "flagged_chapters": [f"{l}: {why}" for l, why in self.flagged]}


def _sort(label: str):
    try:
        return (0, float(label), label)
    except ValueError:
        return (1, 0.0, label.lower())


def make_plan(cfg, history, labels: dict[Path, str], destination: Path, rank: int) -> Plan:
    plan = Plan()
    if not cfg.src_enabled or not destination.is_dir():
        return plan
    incoming_names = {f.name for f in labels}
    existing: dict[str, list[Path]] = {}
    for f in destination.iterdir():
        if f.is_file() and f.suffix.lower() in cfg.extensions and f.name not in incoming_names:
            existing.setdefault(chapter_key(chapter_label_fast(f)), []).append(f)
    if not existing:
        return plan
    ranks = history.source_ranks(str(destination))
    for incoming, label in labels.items():
        for old in existing.get(chapter_key(label), []):
            old_rank = ranks.get(old.name, 0)
            if rank > old_rank:
                new_pages, old_pages = count_pages(incoming), count_pages(old)
                if old_pages and new_pages * 100 < old_pages * cfg.src_min_page_pct:
                    plan.flagged.append((label, f"{new_pages} pages against {old_pages}, kept both"))
                else:
                    plan.replace.append((incoming, old, label))
            elif old_rank > rank:
                plan.drop.append((incoming, old, label))
    return plan


def to_trash(ctx_db, data_dir: Path, source: Path, series_name: str | None, series_id: int | None,
             library_id: int | None, original_path: Path, reason: str) -> dict:
    """Move a file into the shared trash and register it, so the Doubles tab can restore it."""
    batch = data_dir / "double-chapters" / "trash" / time.strftime("%Y%m%dT%H%M%S")
    batch.mkdir(parents=True, exist_ok=True)
    dest = batch / source.name
    i = 1
    while dest.exists():
        dest = batch / f"{source.stem}__{i}{source.suffix}"
        i += 1
    size = source.stat().st_size
    shutil.move(str(source), dest)
    entry = {"id": str(uuid.uuid4()), "originalPath": str(original_path), "trashPath": str(dest),
             "seriesId": series_id, "seriesName": series_name, "libraryId": library_id, "chapterId": None,
             "size": size, "deletedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "reason": reason}
    trash = ctx_db.kv_get("dc", "trash", {}) or {}
    trash[entry["id"]] = entry
    ctx_db.kv_set("dc", "trash", trash)
    return entry
