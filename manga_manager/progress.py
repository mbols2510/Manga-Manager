"""Carry Kavita read progress over when an official chapter replaces an existing one (#6).

The replacement usually has a different page count, so Kavita would show the chapter as half read.
Like the Double Chapters tool, the new chapter gets the read state of the one it replaces:
fully read stays fully read, partly read is mapped by proportion, unread is left alone.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

log = logging.getLogger(__name__)

POLL_ATTEMPTS = 12
POLL_SECONDS = 5


@dataclass
class Carry:
    library_id: int
    series_id: int
    new_name: str        # file name of the incoming chapter
    pages_read: int      # read progress of the replaced chapter
    old_pages: int       # its page count


def _names(chapter: dict) -> set[str]:
    out = set()
    for f in chapter.get("files") or []:
        path = f.get("filePath") or f.get("path") or ""
        out.add(path.replace("\\", "/").rsplit("/", 1)[-1].lower())
    return out


def find_chapter(chapters: list[dict], file_name: str) -> dict | None:
    wanted = file_name.lower()
    return next((c for c in chapters if wanted in _names(c)), None)


def snapshot(kavita, library_id: int, series_id: int, replacements: list[tuple[str, str]]) -> list[Carry]:
    """Read progress of the chapters about to be replaced. `replacements` is [(old file name, new file name)].
    Never raises: a Kavita problem only means no progress is carried over."""
    try:
        chapters = kavita.series_chapters(series_id)
    except Exception as exc:  # noqa: BLE001
        log.warning("Could not read Kavita read progress of series %s: %s", series_id, exc)
        return []
    carries = []
    for old_name, new_name in replacements:
        chapter = find_chapter(chapters, old_name)
        read = int((chapter or {}).get("pagesRead") or 0)
        if chapter and read > 0:
            carries.append(Carry(library_id, series_id, new_name, read, int(chapter.get("pages") or 0)))
    return carries


def target_page(read: int, old_pages: int, new_pages: int) -> int | None:
    """Pages to count as read in the new chapter, or None for 'mark as fully read'."""
    if new_pages <= 1:
        return None
    if old_pages > 0 and read >= old_pages:
        return None
    if old_pages > 0:
        read = round(read * new_pages / old_pages)
    return max(1, min(read, new_pages - 1))


def restore(kavita, carries: list[Carry], sleep, attempts: int = POLL_ATTEMPTS, delay: int = POLL_SECONDS) -> int:
    """After the Kavita scan, give the new chapters the progress of the ones they replaced. Returns how many."""
    pending = list(carries)
    done = 0
    for attempt in range(attempts):
        if not pending:
            break
        if attempt:
            sleep(delay)
        listed: dict[int, list[dict] | None] = {}
        for c in list(pending):
            if c.series_id not in listed:
                try:
                    listed[c.series_id] = kavita.series_chapters(c.series_id)
                except Exception as exc:  # noqa: BLE001
                    log.warning("Could not read chapters of Kavita series %s: %s", c.series_id, exc)
                    listed[c.series_id] = None
            chapter = find_chapter(listed[c.series_id] or [], c.new_name)
            new_pages = int((chapter or {}).get("pages") or 0)
            if not chapter or new_pages <= 0:
                continue  # not scanned yet
            pending.remove(c)
            page = target_page(c.pages_read, c.old_pages, new_pages)
            current = int(chapter.get("pagesRead") or 0)
            try:
                if page is None:
                    if current != new_pages:
                        kavita.mark_chapter_read(c.series_id, chapter["id"])
                        done += 1
                elif current != page:
                    kavita.set_chapter_progress(c.library_id, c.series_id, chapter.get("volumeId") or 0,
                                                chapter["id"], page)
                    done += 1
            except Exception as exc:  # noqa: BLE001
                log.warning("Could not carry read progress over to %s: %s", c.new_name, exc)
    for c in pending:
        log.warning("Kavita has not picked up %s yet; its read progress was not carried over", c.new_name)
    return done
