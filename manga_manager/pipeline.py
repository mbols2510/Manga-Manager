"""One processing run: move finished downloads into Kavita, scan, notify, fix covers."""
from __future__ import annotations

import logging
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .comicinfo import (chapter_key, chapter_label, chapter_label_fast, chapter_sort_key, normalize_title,
                        read_comicinfo, set_series_name)
from .config import Config, LibraryConfig
from .covers import CoverStore
from .history import History
from .kavita import KavitaClient, KavitaIndex, KavitaSeries
from .notifier import Notifier
from .suwayomi import ActiveDownloads, SuwayomiClient, SuwayomiIndex, SuwayomiManga

log = logging.getLogger(__name__)


@dataclass
class Processed:
    title: str
    suwayomi: SuwayomiManga
    library: LibraryConfig
    chapters: list[str]
    destination: Path
    is_new: bool
    kavita_series: KavitaSeries | None = None
    history_id: int | None = None
    replaced: list[str] = field(default_factory=list)   # same file name was already in Kavita (overwritten)
    duplicates: list[str] = field(default_factory=list) # chapter already in Kavita under another file name (#10)
    display_title: str = ""                            # Kavita's name (alias) when known

    def __post_init__(self):
        self.display_title = self.display_title or self.title


@dataclass
class RunResult:
    processed: list[Processed] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    waiting: list[str] = field(default_factory=list)   # manga still downloading, moved on a later run
    covers_set: int = 0
    errors: list[str] = field(default_factory=list)

    def summary(self) -> str:
        new = sum(p.is_new for p in self.processed)
        waiting = f", {len(self.waiting)} still downloading" if self.waiting else ""
        return (f"{len(self.processed)} manga moved ({new} new, {len(self.processed) - new} updated){waiting}, "
                f"{self.covers_set} cover(s) set, {len(self.skipped)} skipped, {len(self.errors)} error(s)")


class Pipeline:
    def __init__(self, cfg: Config, suwayomi: SuwayomiClient, kavita: KavitaClient, covers: CoverStore,
                 history: History, notifier: Notifier, sleep=time.sleep):
        self.cfg = cfg
        self.suwayomi = suwayomi
        self.kavita = kavita
        self.covers = covers
        self.history = history
        self.notifier = notifier
        self.sleep = sleep

    # ------------------------------------------------------------------ run
    def run(self) -> RunResult:
        result = RunResult()
        self.refresh_aliases()
        self.prune_history()
        suwa_index = SuwayomiIndex(self.suwayomi.library())
        kavita_index = KavitaIndex(self.kavita.all_series())
        log.info("Loaded %d Suwayomi library entries and %d Kavita series",
                 len(suwa_index.mangas), len(kavita_index.series))
        downloading = self._active_downloads()

        for manga_dir in self._manga_folders():
            try:
                item = self._process_folder(manga_dir, suwa_index, kavita_index, result, downloading)
                if item:
                    result.processed.append(item)
            except Exception as exc:  # keep going with the other manga
                log.exception("Failed to process %s", manga_dir)
                result.errors.append(f"{manga_dir.name}: {exc}")

        if result.processed:
            self._trigger_scans(result.processed)
            kavita_index = self._wait_for_new_series(result.processed, kavita_index)
            self._notify(result.processed)

        if self.cfg.covers_enabled:
            result.covers_set = self._fix_covers(kavita_index, suwa_index, result)
        self._export_thumbnails(suwa_index)
        return result

    # ------------------------------------------------------------ discovery
    def _manga_folders(self) -> list[Path]:
        root = self.cfg.downloads
        if not root.is_dir():
            raise FileNotFoundError(f"Download folder {root} does not exist (check the volume mount)")
        folders = []
        for source_dir in sorted(p for p in root.iterdir() if p.is_dir()):
            folders.extend(sorted(p for p in source_dir.iterdir() if p.is_dir()))
        return folders

    def _active_downloads(self) -> ActiveDownloads | None:
        """Manga Suwayomi is still downloading (#8), or None when the queue isn't used or can't be read."""
        if not (self.cfg.wait_for_whole_manga and self.cfg.check_download_queue):
            return None
        try:
            active = self.suwayomi.active_downloads()
        except Exception as exc:  # fall back to the folder timer alone
            log.warning("Could not read Suwayomi's download queue (%s); only waiting for folders to be "
                        "untouched for %ss", exc, self.cfg.settle_seconds)
            return None
        if active.ids or active.titles:
            log.info("Suwayomi is still downloading chapters for %d manga", len(active.chapters))
        return active

    @staticmethod
    def _last_activity(manga_dir: Path) -> float:
        """Newest modification time of anything in the folder: chapters, temp files, page folders."""
        newest = manga_dir.stat().st_mtime
        for p in manga_dir.rglob("*"):
            try:
                newest = max(newest, p.stat().st_mtime)
            except OSError:  # removed while we looked (Suwayomi finishing a chapter)
                continue
        return newest

    def _still_downloading(self, manga_dir: Path) -> bool:
        """True while anything in the manga folder changed less than settle_seconds ago (#8).

        Only used with wait_for_whole_manga, so a long download is moved in one go instead of
        in batches as chapters finish.
        """
        if not self.cfg.wait_for_whole_manga:
            return False
        try:
            last = self._last_activity(manga_dir)
        except OSError:
            return True  # folder vanished or unreadable right now; look again next run
        age = time.time() - last
        if age < self.cfg.settle_seconds:
            log.info("Waiting for %s: still downloading (last change %ds ago, waiting for %ss)",
                     manga_dir.name, max(0, round(age)), self.cfg.settle_seconds)
            return True
        return False

    def _ready_files(self, manga_dir: Path) -> list[Path]:
        """Chapter archives that finished downloading (untouched for settle_seconds)."""
        cutoff = time.time() - self.cfg.settle_seconds
        files = []
        for f in manga_dir.iterdir():
            if f.is_file() and f.suffix.lower() in self.cfg.extensions:
                if f.stat().st_mtime <= cutoff:
                    files.append(f)
                else:
                    log.debug("Skipping %s, still being written", f.name)
        return sorted(files, key=lambda p: p.name)

    def refresh_aliases(self) -> None:
        """Pick up aliases edited on the dashboard (#12), also in a separate run-once process."""
        try:
            self.cfg.dashboard_aliases = self.history.aliases()
        except Exception as exc:  # never stop a run over this
            log.warning("Could not read the dashboard aliases: %s", exc)

    def prune_history(self) -> None:
        """Remove dashboard updates older than web.keep_days (#14)."""
        if self.cfg.dry_run or self.cfg.web_keep_days <= 0:
            return
        removed = self.history.prune_updates(self.cfg.web_keep_days)
        if removed:
            log.info("Removed %d dashboard update(s) older than %d days", removed, self.cfg.web_keep_days)

    # ------------------------------------------------------------ one manga
    def _process_folder(self, manga_dir: Path, suwa_index: SuwayomiIndex, kavita_index: KavitaIndex,
                        result: RunResult, downloading: ActiveDownloads | None = None) -> Processed | None:
        if self._still_downloading(manga_dir):
            result.waiting.append(manga_dir.name)
            return None
        files = self._ready_files(manga_dir)
        if not files:
            return None

        source_name = manga_dir.parent.name
        info = read_comicinfo(files[0])
        suwa = suwa_index.find(info.series, manga_dir.name, source=source_name)
        if suwa is None:
            msg = f"{manga_dir.name}: not found in the Suwayomi library (ComicInfo series: {info.series!r})"
            log.warning(msg)
            result.skipped.append(msg)
            return None

        if downloading is not None and downloading.contains(suwa):
            left = downloading.left(suwa)
            log.info("Waiting for %s: Suwayomi still has %s chapter(s) queued or downloading", suwa.title,
                     left or "some")
            result.waiting.append(suwa.title)
            return None

        library = self.cfg.library_for_category(suwa.categories)
        if library is None:
            msg = f"{suwa.title}: categories {suwa.categories or '[none]'} don't match any configured library"
            log.warning(msg)
            result.skipped.append(msg)
            return None

        alias = self.cfg.kavita_name_for(suwa.title)
        existing = kavita_index.find(library.kavita_library_id, *filter(None, [alias, suwa.title, info.series]),
                                     folder_name=manga_dir.name)
        # Reuse the folder Kavita already knows, so a series never ends up split over two folders.
        dest_name = existing.folder_name if existing and existing.folder_name else manga_dir.name
        destination = library.path / dest_name
        labels = {f: chapter_label(f) for f in files}
        chapters = sorted(set(labels.values()), key=chapter_sort_key)
        replaced, duplicates = self._existing_chapters(labels, destination)
        display = self.cfg.display_title(suwa.title, existing.name if existing else None)

        log.info("%s -> %s (%s, %s, %d chapter(s)%s%s)", suwa.title, destination, library.label,
                 "existing" if existing else "new", len(files),
                 f", {len(replaced)} replacing existing" if replaced else "",
                 f", {len(duplicates)} duplicate(s) of existing chapters" if duplicates else "")
        self._move_files(files, destination)
        self._cleanup_empty(manga_dir)
        if existing and self.cfg.align_series_name:
            self._align_series_name(files, destination, existing.name)

        self.covers.get_png(suwa.id)
        if self.cfg.covers_write_file:
            self.covers.write_cover_file(suwa.id, destination, self.cfg.covers_file_name)

        item = Processed(title=suwa.title, suwayomi=suwa, library=library, chapters=chapters,
                         destination=destination, is_new=existing is None, kavita_series=existing,
                         replaced=replaced, duplicates=duplicates, display_title=display)
        if not self.cfg.dry_run:
            item.history_id = self.history.add_update(
                title=display, category=library.label, is_new=item.is_new, chapters=chapters,
                source=suwa.source or source_name, suwayomi_id=suwa.id,
                kavita_series_id=existing.id if existing else None, kavita_library_id=library.kavita_library_id,
                replaced=replaced, duplicates=duplicates, destination=str(destination),
            )
        return item

    # ------------------------------------------------------------ reprocess (#16)
    def reprocess_alias(self, suwayomi_title: str, apply: bool = False) -> dict:
        """Move chapters that were filed under a Suwayomi name into the Kavita series its alias points to.

        When Suwayomi renames a series, its chapters can end up in a folder of their own, which Kavita
        shows as a second series. After the alias is added this moves them over (and rescans Kavita),
        so nobody has to put the files back in the Suwayomi folder by hand.
        With apply=False nothing changes: the result only lists what would move.
        """
        self.refresh_aliases()
        kav_name = self.cfg.kavita_name_for(suwayomi_title)
        out = {"applied": False, "moves": [], "message": ""}
        if not kav_name:
            out["message"] = f"There is no alias for “{suwayomi_title}”."
            return out
        index = KavitaIndex(self.kavita.all_series())
        rows = self.history.updates_for_titles([suwayomi_title, kav_name])
        wanted = normalize_title(suwayomi_title)
        for library in self.cfg.libraries:
            series = index.find(library.kavita_library_id, kav_name)
            if series and series.folder_name:
                break
        else:
            out["message"] = (f"“{kav_name}” isn't in Kavita yet, so there is nothing to move the chapters into. "
                              "Scan the library in Kavita first, or rename the folder.")
            return out

        target = library.path / series.folder_name
        sources: dict[Path, list[int]] = {}
        for r in rows:                                   # where the dashboard remembers moving them to
            if r["destination"]:
                sources.setdefault(Path(r["destination"]), []).append(r["id"])
        if library.path.is_dir() and wanted:             # and folders named like the Suwayomi title
            for d in library.path.iterdir():
                if d.is_dir() and normalize_title(d.name) == wanted:
                    sources.setdefault(d, [])
        for source, ids in sorted(sources.items()):
            if source == target or library.path not in source.parents:
                continue
            files = sorted(f for f in source.iterdir()
                           if f.is_file() and f.suffix.lower() in self.cfg.extensions) if source.is_dir() else []
            labels = {f: chapter_label(f) for f in files}
            replaced, _ = self._existing_chapters(labels, target)
            if files or ids:
                out["moves"].append({"from": source.name, "to": target.name, "files": len(files),
                                     "replaced": len(replaced)})
            if not apply:
                continue
            if files:
                log.info("Reprocessing alias %r: %d file(s) %s -> %s", suwayomi_title, len(files), source, target)
                self._move_files(files, target)
                if self.cfg.align_series_name:
                    self._align_series_name(files, target, series.name)
                self._remove_leftover_cover(source)
                self._cleanup_empty(source)
            if not self.cfg.dry_run:
                self.history.retarget_updates(ids, title=self.cfg.display_title(suwayomi_title, series.name),
                                              destination=str(target), series_id=series.id)
        moved = sum(m["files"] for m in out["moves"])
        if not out["moves"]:
            out["message"] = f"Nothing to move: no other folder holds chapters of “{suwayomi_title}”."
        elif apply:
            out["applied"] = True
            out["message"] = f"Moved {moved} chapter file(s) into “{series.name}”."
            if moved and not self.cfg.dry_run:
                try:
                    self.kavita.scan_library(library.kavita_library_id)
                except Exception as exc:
                    log.warning("Kavita scan after reprocessing %r failed: %s", suwayomi_title, exc)
                    out["message"] += " The Kavita scan failed, so scan the library there."
        else:
            out["message"] = f"{moved} chapter file(s) would move into “{series.name}”."
        return out

    def _remove_leftover_cover(self, folder: Path) -> None:
        """The cover file Move Manga wrote must not keep an otherwise empty folder alive."""
        if self.cfg.dry_run:
            return
        cover = next((f for f in folder.glob(f"{self.cfg.covers_file_name}.*")), None) if folder.is_dir() else None
        if cover and all(f == cover for f in folder.iterdir()):
            cover.unlink(missing_ok=True)

    def _existing_chapters(self, labels: dict[Path, str], destination: Path) -> tuple[list[str], list[str]]:
        """Chapters of this batch that Kavita already has, as (replaced, duplicates) (#10).

        replaced:   a file with the same name is already there and gets overwritten.
        duplicates: Kavita has that chapter number under another file name (another scanlation
                    group, or the source renamed the chapter); both files are kept.
        A chapter that is both (several files in the batch) counts as replaced.
        Everything else in the batch is new.
        """
        if not destination.is_dir():
            return [], []
        incoming = {f.name for f in labels}
        existing_names, existing_keys = set(), set()
        for f in destination.iterdir():
            if f.is_file() and f.suffix.lower() in self.cfg.extensions:
                existing_names.add(f.name)
                if f.name not in incoming:
                    existing_keys.add(chapter_key(chapter_label_fast(f)))
        replaced = {label for f, label in labels.items() if f.name in existing_names}
        duplicates = {label for f, label in labels.items()
                      if label not in replaced and chapter_key(label) in existing_keys}
        return sorted(replaced, key=chapter_sort_key), sorted(duplicates, key=chapter_sort_key)

    def _move_files(self, files: list[Path], destination: Path) -> None:
        if self.cfg.dry_run:
            for f in files:
                log.info("[dry-run] would move %s -> %s", f.name, destination)
            return
        destination.mkdir(parents=True, exist_ok=True)
        for f in files:
            target = destination / f.name
            if target.exists():
                log.info("Replacing existing %s", target.name)
                target.unlink()
            shutil.move(str(f), str(target))

    def _align_series_name(self, files: list[Path], destination: Path, kavita_name: str) -> None:
        """Make new chapters carry the series name Kavita already uses.

        Kavita groups chapters by the <Series> in ComicInfo.xml. When a source renamed a
        manga (e.g. dropped "(Official)"), new chapters would otherwise become a second
        series in the same folder.
        """
        changed = 0
        for f in files:
            target = destination / f.name
            if (read_comicinfo(target if not self.cfg.dry_run else f).series or "") == kavita_name:
                continue
            if self.cfg.dry_run:
                log.info("[dry-run] would set series name of %s to %r", f.name, kavita_name)
                continue
            changed += set_series_name(target, kavita_name)
        if changed:
            log.info("Set series name to %r in %d chapter(s) so they join the existing Kavita series",
                     kavita_name, changed)

    def _cleanup_empty(self, manga_dir: Path) -> None:
        if self.cfg.dry_run:
            return
        try:
            if not any(manga_dir.iterdir()):
                manga_dir.rmdir()
        except OSError as exc:
            log.debug("Could not remove %s: %s", manga_dir, exc)

    # ------------------------------------------------------------ kavita
    def _trigger_scans(self, processed: list[Processed]) -> None:
        """Ask Kavita to import the moved chapters, one scan request at a time.

        Kavita misbehaves when several scans start at once, so every request waits
        kavita.scan_delay seconds after the previous one (no wait after the last).
        """
        library_scans = sorted({p.library.kavita_library_id for p in processed if p.is_new})
        scans: list[tuple[str, Callable[[], None]]] = [
            (f"library {lib_id} (new series)", lambda lib_id=lib_id: self.kavita.scan_library(lib_id))
            for lib_id in library_scans
        ]
        seen = set()
        for p in processed:
            s = p.kavita_series
            if p.is_new or s is None or s.id in seen:
                continue
            seen.add(s.id)
            if s.library_id in library_scans:
                log.debug("%s is covered by the library %s scan", p.title, s.library_id)
                continue
            scans.append((f"series {s.id} ({p.title})",
                          lambda s=s: self.kavita.scan_series(s.library_id, s.id)))

        if self.cfg.dry_run:
            for what, _ in scans:
                log.info("[dry-run] would scan Kavita %s", what)
            return

        delay = self.cfg.scan_delay
        if len(scans) > 1 and delay:
            log.info("Starting %d Kavita scans, %ss apart (about %d min)", len(scans), delay,
                     round((len(scans) - 1) * delay / 60))
        for i, (what, start) in enumerate(scans):
            if i and delay:
                self.sleep(delay)
            log.info("Scanning Kavita %s", what)
            try:
                start()
            except Exception as exc:  # one failed scan shouldn't stop the others
                log.warning("Kavita scan of %s failed: %s", what, exc)

    def _wait_for_new_series(self, processed: list[Processed], index: KavitaIndex) -> KavitaIndex:
        pending = [p for p in processed if p.is_new]
        if not pending or self.cfg.dry_run:
            return index
        poll = 15
        attempts = max(1, -(-self.cfg.new_series_timeout // poll))  # ceil
        for _ in range(attempts):
            self.sleep(poll)
            index = KavitaIndex(self.kavita.all_series())
            for p in list(pending):
                found = index.find(p.library.kavita_library_id,
                                   *filter(None, [self.cfg.kavita_name_for(p.title), p.title]),
                                   folder_name=p.destination.name)
                if found:
                    p.kavita_series = found
                    if p.history_id:
                        self.history.set_kavita_series(p.history_id, found.id)
                    pending.remove(p)
            if not pending:
                break
        for p in pending:
            log.warning("%s did not show up in Kavita within %ss; it will be picked up on a later run",
                        p.title, self.cfg.new_series_timeout)
        return index

    # ------------------------------------------------------------ notify
    def _notify(self, processed: list[Processed]) -> None:
        for p in processed:
            self.notifier.send(title=p.display_title, is_new=p.is_new, category=p.library.label,
                               chapters=p.chapters, suwayomi_id=p.suwayomi.id, replaced=p.replaced,
                               duplicates=p.duplicates)

    # ------------------------------------------------------------ covers
    def _fix_covers(self, index: KavitaIndex, suwa_index: SuwayomiIndex, result: RunResult) -> int:
        """Give every unlocked series in the configured libraries its Suwayomi cover."""
        configured = {l.kavita_library_id for l in self.cfg.libraries}
        count = unmatched = 0
        for series in index.series:
            if series.library_id not in configured:
                continue
            library = self.cfg.library_by_id(series.library_id)

            needs_upload = not series.cover_locked
            needs_file = self.cfg.covers_write_file and library is not None and bool(series.folder_name)
            if not needs_upload and not needs_file:
                continue

            suwa = suwa_index.find(self.cfg.suwayomi_title_for(series.name), series.name, series.localized_name,
                                   series.original_name, series.folder_name)
            if suwa is None:
                unmatched += 1
                log.debug("No Suwayomi match for Kavita series %s (%s)", series.id, series.name)
                continue
            png = self.covers.get_png(suwa.id)
            if png is None:
                log.warning("No thumbnail available for %s (Suwayomi id %s)", series.name, suwa.id)
                continue

            if needs_file:
                self.covers.write_cover_file(suwa.id, library.path / series.folder_name, self.cfg.covers_file_name)
            if not needs_upload:
                continue
            mtime = png.stat().st_mtime
            if not self.cfg.covers_lock and self.history.cover_uploaded(series.id, suwa.id, mtime):
                continue  # unlocked covers: only re-upload when the thumbnail changed
            if self.cfg.dry_run:
                log.info("[dry-run] would set cover of %s from Suwayomi id %s", series.name, suwa.id)
                count += 1
                continue
            try:
                method = self.kavita.upload_series_cover(series.id, png.read_bytes(), lock=self.cfg.covers_lock)
                log.info("Cover set for %s (Kavita %s, via %s upload)", series.name, series.id, method)
                self.history.record_cover_upload(series.id, suwa.id, mtime)
                count += 1
            except Exception as exc:
                log.warning("Could not set cover for %s: %s", series.name, exc)
                result.errors.append(f"cover {series.name}: {exc}")
        if unmatched:
            log.info("%d unlocked Kavita series have no Suwayomi counterpart (left untouched)", unmatched)
        return count

    def _export_thumbnails(self, suwa_index: SuwayomiIndex) -> None:
        if not self.cfg.thumbnail_export:
            return
        for manga in suwa_index.mangas:
            if self.covers.get_png(manga.id):
                self.covers.export(manga.id)
