"""Discord webhook notifications: one card per updated manga, styled like the dashboard.

Runs next to the ntfy Notifier (see NotifierGroup in notifier.py); ntfy is not touched.
Bar colour: gold = new series, red = Adult/Hentai update, green = regular update.
"""
from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Callable

import requests

log = logging.getLogger(__name__)

GOLD, RED, GREEN = 0xF5C542, 0xE4002B, 0x4CC38A
LABELS = {"new": "New", "replaced": "Replaced", "duplicate": "Duplicate"}
FALLBACK_ICONS = {"new": "\U0001F7E9", "replaced": "\U0001F7E7", "duplicate": "\U0001F7EA"}   # green/orange/purple square
MATURE = ("Adult", "Hentai")


def _number(text: str) -> float | None:
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def chapter_ranges(chapters: list[str]) -> str:
    """["95", "96", "90"] -> "90, 95–96". Non-integer chapter names are listed as they are."""
    nums = [_number(c) for c in chapters]
    if not chapters or any(n is None or n != int(n) for n in nums):
        return ", ".join(chapters)
    values = sorted({int(n) for n in nums})
    out, i = [], 0
    while i < len(values):
        j = i
        while j + 1 < len(values) and values[j + 1] - values[j] == 1:
            j += 1
        out.append(str(values[i]) if i == j else f"{values[i]}–{values[j]}")
        i = j + 1
    return ", ".join(out)


def build_embed(*, title: str, is_new: bool, category: str, chapters: list[str], replaced: list[str],
                duplicates: list[str], dashboard_url: str = "", cover_url: str = "",
                icons: dict[str, str] | None = None, when: datetime | None = None) -> dict:
    icons = {**FALLBACK_ICONS, **{k: v for k, v in (icons or {}).items() if v}}
    replaced_set, duplicate_set = set(replaced), set(duplicates) - set(replaced)
    groups: dict[str, list[str]] = {"new": [], "replaced": [], "duplicate": []}
    for c in chapters:
        groups["replaced" if c in replaced_set else "duplicate" if c in duplicate_set else "new"].append(c)
    rows = [f"{icons[k]} **{LABELS[k]}** · Ch. {chapter_ranges(v)}" for k, v in groups.items() if v]
    mature = category in MATURE
    color = GOLD if is_new else RED if mature else GREEN
    stamp = (when or datetime.now()).strftime("%d %b %H:%M")
    head = f"{'NEW SERIES · ' if is_new else ''}{category.upper() or 'MANGA'}{' · 18+' if mature else ''} · {stamp}"
    name = f"[{title}]({dashboard_url})" if dashboard_url else title
    embed = {"color": color, "author": {"name": head}, "description": f"## {name}\n" + "\n".join(rows),
             "footer": {"text": "Manga updates"}}
    if cover_url:
        embed["thumbnail"] = {"url": cover_url}
    return embed


class DiscordNotifier:
    def __init__(self, webhook_url: str, *, dashboard_url: str = "", enabled: bool = True, dry_run: bool = False,
                 emoji_new: str = "", emoji_replaced: str = "", emoji_duplicate: str = "",
                 thumb_path: Callable[[int], str] | None = None, delay: float = 1.0,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic):
        self.webhook_url = webhook_url.strip()
        self.dashboard_url = dashboard_url.rstrip("/")
        self.enabled = enabled and bool(self.webhook_url)
        self.dry_run = dry_run
        self.icons = {"new": emoji_new.strip(), "replaced": emoji_replaced.strip(), "duplicate": emoji_duplicate.strip()}
        self.thumb_path = thumb_path or (lambda manga_id: f"thumb/{manga_id}.png")
        self.delay = delay
        self._sleep, self._clock = sleep, clock
        self._last_sent: float | None = None

    def _pace(self) -> None:
        if self.delay and self._last_sent is not None:
            left = self.delay - (self._clock() - self._last_sent)
            if left > 0:
                self._sleep(left)

    def send(self, *, title: str, is_new: bool, category: str, chapters: list[str],
             suwayomi_id: int | None = None, replaced: list[str] | None = None,
             duplicates: list[str] | None = None) -> bool:
        if not self.enabled:
            return False
        replaced = [c for c in (replaced or []) if c in chapters]
        duplicates = [c for c in (duplicates or []) if c in chapters and c not in replaced]
        cover = (f"{self.dashboard_url}/{self.thumb_path(suwayomi_id)}"
                 if self.dashboard_url and suwayomi_id is not None else "")
        embed = build_embed(title=title, is_new=is_new, category=category, chapters=chapters, replaced=replaced,
                            duplicates=duplicates, dashboard_url=self.dashboard_url, cover_url=cover, icons=self.icons)
        if self.dry_run:
            log.info("[dry-run] would notify Discord: %s (%s, new=%s, %d chapter(s))", title, category, is_new, len(chapters))
            return True
        self._pace()
        try:
            for attempt in (1, 2):
                resp = requests.post(self.webhook_url, json={"username": "Manga updates", "embeds": [embed]}, timeout=20)
                if resp.status_code == 429 and attempt == 1:       # rate limited: wait as told, once
                    try:
                        wait = float(resp.json().get("retry_after", 1))
                    except (ValueError, AttributeError):
                        wait = 1.0
                    self._sleep(min(wait, 10))
                    continue
                break
            if not resp.ok:
                log.warning("Discord returned HTTP %s: %s", resp.status_code, resp.text[:200])
                return False
            log.info("Discord notification sent for %s", title)
            return True
        except requests.RequestException as exc:
            log.warning("Could not send Discord notification for %s: %s", title, exc)
            return False
        finally:
            self._last_sent = self._clock()
