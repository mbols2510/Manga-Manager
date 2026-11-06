"""ntfy notifications (same JSON fields as v1, so the MangaUpdates template keeps working)."""
from __future__ import annotations

import logging
import time
from typing import Callable

import requests

log = logging.getLogger(__name__)


class Notifier:
    def __init__(self, url: str, topic: str, template: str = "", token: str = "", delay: float = 10,
                 dashboard_url: str = "", attach_icon: bool = True, enabled: bool = True, dry_run: bool = False,
                 attach_cover: bool = True, chapter_status: bool = True,
                 thumb_path: Callable[[int], str] | None = None,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic):
        self.url = url.rstrip("/")
        self.topic = topic
        self.template = template
        self.token = token
        self.delay = delay
        self.dashboard_url = dashboard_url.rstrip("/")
        self.attach_icon = attach_icon
        self.enabled = enabled and bool(url)
        self.dry_run = dry_run
        self.attach_cover = attach_cover
        self.chapter_status = chapter_status
        # Relative cover link; signed when the dashboard has a login (see auth.ThumbSigner).
        self.thumb_path = thumb_path or (lambda manga_id: f"thumb/{manga_id}.png")
        self._sleep = sleep
        self._clock = clock
        self._last_sent: float | None = None

    def _pace(self) -> None:
        """Keep `delay` seconds between two notifications (#11).

        The wait happens before a notification, so the first one goes out straight away
        and nothing waits after the last one.
        """
        if self.delay and self._last_sent is not None:
            left = self.delay - (self._clock() - self._last_sent)
            if left > 0:
                self._sleep(left)

    def chapter_text(self, chapters: list[str], replaced: list[str] | None = None,
                     duplicates: list[str] | None = None) -> str:
        replaced_set, duplicate_set = set(replaced or []), set(duplicates or [])
        lines = []
        for c in chapters:
            status = ""
            if self.chapter_status:
                status = (" (Replaced)" if c in replaced_set else
                          " (Duplicate)" if c in duplicate_set else " (New)")
            lines.append(f"\u25CF Chapter: {c}{status}\n")
        return "".join(lines)

    def send(self, *, title: str, is_new: bool, category: str, chapters: list[str],
             suwayomi_id: int | None = None, replaced: list[str] | None = None,
             duplicates: list[str] | None = None) -> bool:
        if not self.enabled:
            return False
        replaced = [c for c in (replaced or []) if c in chapters]
        duplicates = [c for c in (duplicates or []) if c in chapters and c not in replaced]
        chapter_text = self.chapter_text(chapters, replaced, duplicates)
        data = {
            # v1 field names - used by ntfy/MangaUpdates.yml
            "New": "Yes" if is_new else "No",
            "category": category,
            "MangaTitle": title,
            "Chapters": chapter_text,
            # extra fields, available to custom templates
            "SuwayomiId": suwayomi_id,
            "ChapterCount": len(chapters),
            "NewChapterCount": len(chapters) - len(replaced) - len(duplicates),
            "ReplacedChapterCount": len(replaced),
            "DuplicateChapterCount": len(duplicates),
        }
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        click = self.dashboard_url or None
        cover = (f"{self.dashboard_url}/{self.thumb_path(suwayomi_id)}"
                 if self.dashboard_url and suwayomi_id is not None else None)
        icon = cover if self.attach_icon else None
        attach = cover if self.attach_cover else None

        if self.template and not attach:
            # The server-side template (ntfy/MangaUpdates.yml) builds the text from `data`.
            request = dict(url=f"{self.url}/{self.topic}", params={"template": self.template}, json=data,
                           headers={**headers, **({"Click": click} if click else {}),
                                    **({"Icon": icon} if icon else {})})
        else:
            # ntfy never applies a template to a message with an attachment (it takes the body
            # as plain text), so the text is built here, identical to MangaUpdates.yml.
            msg_title, message = render_message(is_new=is_new, category=category, title=title,
                                                chapter_text=chapter_text)
            payload = {"topic": self.topic, "title": msg_title, "message": message}
            if click:
                payload["click"] = click
            if icon:
                payload["icon"] = icon
            if attach:
                # Shown as a large image in the Android app (the phone downloads it itself).
                payload["attach"] = attach
                payload["filename"] = "cover.png"
            request = dict(url=self.url, json=payload, headers=headers)

        if self.dry_run:
            log.info("[dry-run] would notify ntfy: %s (%s, new=%s, %d chapter(s))", title, category, is_new, len(chapters))
            return True
        self._pace()
        try:
            resp = requests.post(timeout=20, **request)
            if not resp.ok:
                log.warning("ntfy returned HTTP %s: %s", resp.status_code, resp.text[:200])
                return False
            log.info("Notification sent for %s", title)
            return True
        except requests.RequestException as exc:
            log.warning("Could not send ntfy notification for %s: %s", title, exc)
            return False
        finally:
            self._last_sent = self._clock()


class NotifierGroup:
    """Sends every update through each notifier (ntfy, Discord, ...); one failing never blocks the others."""

    def __init__(self, *notifiers):
        self.notifiers = [n for n in notifiers if n is not None]

    def send(self, **kwargs) -> bool:
        results = []
        for n in self.notifiers:
            try:
                results.append(n.send(**kwargs))
            except Exception:   # a notifier must never break the run
                log.exception("%s failed", type(n).__name__)
                results.append(False)
        return any(results)


# Same wording as ntfy/MangaUpdates.yml; tests/test_notifier_text.py checks the two agree.
_TITLES = {
    ("Yes", "Regular"): "\U0001F389 New Manga Added \U0001F389",
    ("Yes", "Adult"): "\U0001F51E New Adult Manga Added \U0001F51E",
    ("Yes", "Hentai"): "\U0001F51E New Hentai Manga Added \U0001F51E",
    ("Yes", None): "\U0001F4DA New Manga Added \U0001F4DA",
    ("No", "Regular"): "\U0001F389 Manga Updated \U0001F389",
    ("No", "Adult"): "\U0001F51E Adult Manga Updated \U0001F51E",
    ("No", "Hentai"): "\U0001F51E Hentai Manga Updated \U0001F51E",
    ("No", None): "\U0001F4DA Manga Updated \U0001F4DA",
}
_LINES = {
    ("Yes", "Regular"): '\U0001F389 The New Manga: "{t}" Has Been Added \U0001F389',
    ("Yes", "Adult"): '\U0001F51E The New Adult Manga: "{t}" Has Been Added \U0001F51E',
    ("Yes", "Hentai"): '\U0001F51E The New Hentai Manga: "{t}" Has Been Added \U0001F51E',
    ("Yes", None): '\U0001F4DA The New Manga: "{t}" Has Been Added \U0001F4DA',
    ("No", "Regular"): '\U0001F389 The Manga: "{t}" Has Been Updated \U0001F389',
    ("No", "Adult"): '\U0001F51E The Adult Manga: "{t}" Has Been Updated \U0001F51E',
    ("No", "Hentai"): '\U0001F51E The Hentai Manga: "{t}" Has Been Updated \U0001F51E',
    ("No", None): '\U0001F4DA The Manga: "{t}" Has Been Updated \U0001F4DA',
}


def render_message(*, is_new: bool, category: str, title: str, chapter_text: str) -> tuple[str, str]:
    """(title, message) exactly as the MangaUpdates template would produce them."""
    new = "Yes" if is_new else "No"
    cat = category if category in ("Regular", "Adult", "Hentai") else None
    message = _LINES[(new, cat)].format(t=title)
    if chapter_text:
        message += "\n\nCHAPTERS:\n" + chapter_text
    return _TITLES[(new, cat)], message.strip()
