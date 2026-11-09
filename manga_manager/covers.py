"""Cover handling: find Suwayomi thumbnails, convert them to real PNGs and cache them.

The cache (<data>/thumbnails/<suwayomi id>.png) feeds the dashboard, the ntfy
icon, the optional thumbnail export folder, the optional cover.png file and the
Kavita cover upload.
"""
from __future__ import annotations

import io
import logging
import os
import shutil
import threading
from pathlib import Path

from PIL import Image, UnidentifiedImageError

from .suwayomi import SuwayomiClient

log = logging.getLogger(__name__)


class CoverStore:
    def __init__(self, cache_dir: Path, suwayomi_thumbnails: Path, suwayomi: SuwayomiClient | None,
                 export_dir: Path | None = None, dry_run: bool = False):
        self.cache_dir = cache_dir
        self.suwayomi_thumbnails = suwayomi_thumbnails
        self.suwayomi = suwayomi
        self.export_dir = export_dir
        self.dry_run = dry_run
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._locks = [threading.Lock() for _ in range(32)]

    def cached_path(self, manga_id: int) -> Path:
        return self.cache_dir / f"{manga_id}.png"

    def _source_file(self, manga_id: int) -> Path | None:
        if not self.suwayomi_thumbnails.is_dir():
            return None
        for candidate in self.suwayomi_thumbnails.glob(f"{manga_id}.*"):
            if candidate.is_file() and candidate.stem == str(manga_id):
                return candidate
        return None

    @staticmethod
    def _to_png(data: bytes) -> bytes | None:
        try:
            with Image.open(io.BytesIO(data)) as img:
                img.load()
                if img.mode not in ("RGB", "RGBA"):
                    img = img.convert("RGBA" if "A" in img.getbands() else "RGB")
                out = io.BytesIO()
                img.save(out, format="PNG", optimize=True)
                return out.getvalue()
        except (UnidentifiedImageError, OSError) as exc:
            log.warning("Thumbnail is not a readable image: %s", exc)
            return None

    def get_png(self, manga_id: int, refresh: bool = False) -> Path | None:
        # Spotlight and cards can ask for the same missing cover simultaneously.
        with self._locks[manga_id % len(self._locks)]:
            return self._get_png(manga_id, refresh)

    def _get_png(self, manga_id: int, refresh: bool = False) -> Path | None:
        """Return the cached PNG for a Suwayomi manga, creating/refreshing it when needed.

        Sources, in order: the thumbnail file Suwayomi stored on disk, then Suwayomi's
        /api/v1/manga/<id>/thumbnail endpoint. Old versions of this project only renamed
        the file to .png; here the image is really converted, so Kavita and browsers
        always get a valid PNG.
        """
        target = self.cached_path(manga_id)
        source = self._source_file(manga_id)

        if target.exists() and not refresh:
            if source is None or source.stat().st_mtime <= target.stat().st_mtime:
                return target

        raw = None
        if source is not None:
            try:
                raw = source.read_bytes()
            except OSError as exc:
                log.warning("Could not read %s: %s", source, exc)
        if raw is None and self.suwayomi is not None:
            raw = self.suwayomi.download_thumbnail(manga_id)
        if raw is None:
            return target if target.exists() else None

        png = self._to_png(raw)
        if png is None:
            return target if target.exists() else None

        tmp = target.with_suffix(".tmp")
        tmp.write_bytes(png)
        os.replace(tmp, target)
        log.debug("Cached thumbnail for Suwayomi manga %s", manga_id)
        return target

    def export(self, manga_id: int) -> None:
        """Copy the PNG to the optional web export folder."""
        if not self.export_dir:
            return
        src = self.cached_path(manga_id)
        if not src.exists():
            return
        dest = self.export_dir / f"{manga_id}.png"
        if dest.exists() and dest.stat().st_mtime >= src.stat().st_mtime:
            return
        if self.dry_run:
            log.info("[dry-run] would export thumbnail %s -> %s", src.name, dest)
            return
        try:
            self.export_dir.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dest)
            log.info("Exported thumbnail %s", dest)
        except OSError as exc:
            log.warning("Could not export thumbnail to %s: %s", dest, exc)

    def write_cover_file(self, manga_id: int, series_folder: Path, file_name: str = "cover.png") -> bool:
        """Place cover.png in the series folder (only if none of cover.* exists yet)."""
        src = self.get_png(manga_id)
        if src is None or not series_folder.is_dir():
            return False
        existing = [p for p in series_folder.iterdir()
                    if p.is_file() and p.stem.lower() == Path(file_name).stem.lower()]
        if existing:
            return False
        dest = series_folder / file_name
        if self.dry_run:
            log.info("[dry-run] would write %s", dest)
            return True
        try:
            shutil.copyfile(src, dest)
            log.info("Wrote %s", dest)
            return True
        except OSError as exc:
            log.warning("Could not write %s: %s", dest, exc)
            return False
