"""One ntfy publisher for every tab, so all notifications share the same look.

The per-manga notifications keep using notifier.Notifier (templates, cover attachments);
the digests and tool reports go through `publish()`.
"""
from __future__ import annotations

import logging
from typing import Any

import requests

log = logging.getLogger(__name__)

MAX_BYTES = 3800   # ntfy rejects messages over 4096 bytes


class NtfyError(Exception):
    pass


def publish(*, url: str, topic: str, title: str, message: str, token: str = "", tags: list[str] | None = None,
            priority: int = 3, click: str = "", actions: list[dict[str, Any]] | None = None,
            markdown: bool = True, icon: str = "", timeout: int = 15) -> None:
    """Publish one message with ntfy's JSON API. Raises NtfyError on any failure."""
    if not url or not topic:
        raise NtfyError("ntfy URL and topic must be set")
    payload: dict[str, Any] = {"topic": topic, "title": title, "message": message,
                               "priority": priority, "markdown": markdown}
    if tags:
        payload["tags"] = tags
    if click:
        payload["click"] = click
    if icon:
        payload["icon"] = icon
    if actions:
        payload["actions"] = actions
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        resp = requests.post(url.rstrip("/"), json=payload, headers=headers, timeout=timeout)
    except requests.RequestException as exc:
        raise NtfyError(f"ntfy request failed: {exc}") from exc
    if not resp.ok:
        raise NtfyError(f"ntfy returned HTTP {resp.status_code}: {resp.text[:200]}")


def truncate_lines(header: str, lines: list[str], footer_fmt: str = "…and {n} more", limit: int = MAX_BYTES) -> str:
    """Join lines under a header without going over ntfy's size limit."""
    text = header + "\n".join(lines)
    if len(text.encode()) <= limit:
        return text
    keep, size = [], len(header.encode()) + 40
    for line in lines:
        size += len(line.encode()) + 1
        if size > limit:
            break
        keep.append(line)
    return header + "\n".join(keep) + "\n" + footer_fmt.format(n=len(lines) - len(keep))
