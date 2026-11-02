"""Settings shared by every tab, editable on the Settings page.

Precedence: value saved on the Settings page (Postgres) > environment variable > default.
Each tool registers its own entries with `register()`; the Settings page is generated from them.
Secrets are never sent to the browser; an empty submitted secret keeps the stored one.
"""
from __future__ import annotations

import os
import threading
from dataclasses import dataclass
from typing import Any

from .db import Database


@dataclass(frozen=True)
class Setting:
    key: str                  # e.g. "kavita.url"
    label: str
    group: str                # card heading on the Settings page
    kind: str = "text"        # text | url | secret | bool | int | select
    env: str = ""             # environment variable that provides the default
    default: Any = ""
    hint: str = ""
    choices: tuple = ()       # for kind == "select": ((value, label), ...)
    attr: str = ""            # Config attribute this setting overrides at start-up, if any


_REGISTRY: dict[str, Setting] = {}
_ORDER: list[str] = []


def register(*entries: Setting) -> None:
    for e in entries:
        if e.key not in _REGISTRY:
            _ORDER.append(e.key)
        _REGISTRY[e.key] = e


def all_settings() -> list[Setting]:
    return [_REGISTRY[k] for k in _ORDER]


WEEKDAYS = (("0", "Monday"), ("1", "Tuesday"), ("2", "Wednesday"), ("3", "Thursday"),
            ("4", "Friday"), ("5", "Saturday"), ("6", "Sunday"))

register(
    Setting("kavita.url", "Kavita URL", "Kavita", "url", "KAVITA_URL", hint="How this container reaches Kavita, e.g. http://192.168.1.10:5000", attr="kavita_url"),
    Setting("kavita.api_key", "Kavita API key", "Kavita", "secret", "KAVITA_API_KEY", hint="Kavita → User settings → 3rd party clients", attr="kavita_api_key"),
    Setting("kavita.public_url", "Kavita public URL", "Kavita", "url", "KAVITA_PUBLIC_URL", hint="Address used by 'open in Kavita' links; empty = same as URL", attr="kavita_public_url"),
    Setting("suwayomi.url", "Suwayomi URL", "Suwayomi", "url", "SUWAYOMI_URL", hint="e.g. http://192.168.1.10:4567", attr="suwayomi_url"),
    Setting("suwayomi.username", "Username", "Suwayomi", "text", "SUWAYOMI_USERNAME", attr="suwayomi_username"),
    Setting("suwayomi.password", "Password", "Suwayomi", "secret", "SUWAYOMI_PASSWORD", attr="suwayomi_password"),
    Setting("ntfy.enabled", "Send notifications", "Notifications (ntfy)", "bool", "NTFY_ENABLED", False, attr="ntfy_enabled"),
    Setting("ntfy.url", "ntfy server URL", "Notifications (ntfy)", "url", "NTFY_URL", hint="e.g. https://ntfy.sh or your own server", attr="ntfy_url"),
    Setting("ntfy.token", "Access token", "Notifications (ntfy)", "secret", "NTFY_TOKEN", hint="Optional", attr="ntfy_token"),
    Setting("ntfy.topic", "Topic: new manga", "Notifications (ntfy)", "text", "NTFY_TOPIC", "Manga_Updates", attr="ntfy_topic"),
    Setting("web.public_url", "Dashboard public URL", "Notifications (ntfy)", "url", "WEB_PUBLIC_URL", hint="Used for covers and buttons inside notifications", attr="web_public_url"),
)


class Settings:
    def __init__(self, db: Database):
        self.db = db
        self._lock = threading.Lock()
        self._stored: dict[str, Any] = {}
        self.reload()

    def reload(self) -> None:
        with self._lock:
            self._stored = self.db.settings()

    def _coerce(self, spec: Setting, raw: Any) -> Any:
        if spec.kind == "bool":
            return raw if isinstance(raw, bool) else str(raw).strip().lower() in {"1", "true", "yes", "on"}
        if spec.kind == "int":
            try:
                return int(raw)
            except (TypeError, ValueError):
                return spec.default
        return "" if raw is None else str(raw).strip()

    def get(self, key: str, default: Any = None) -> Any:
        spec = _REGISTRY.get(key)
        if spec is None:
            return self._stored.get(key, default)
        if key in self._stored:
            return self._coerce(spec, self._stored[key])
        env = os.environ.get(spec.env) if spec.env else None
        if env not in (None, ""):
            return self._coerce(spec, env)
        return self._coerce(spec, spec.default if default is None else default)

    def source(self, key: str) -> str:
        spec = _REGISTRY[key]
        if key in self._stored:
            return "saved"
        return "env" if spec.env and os.environ.get(spec.env) not in (None, "") else "default"

    def set_many(self, values: dict[str, Any]) -> list[str]:
        """Save submitted values; returns the keys that changed. Empty secrets are left alone."""
        changed = []
        for key, raw in values.items():
            spec = _REGISTRY.get(key)
            if spec is None:
                continue
            if spec.kind == "secret" and raw in (None, ""):
                continue
            value = self._coerce(spec, raw)
            if value != self.get(key):
                self.db.set_setting(key, value)
                changed.append(key)
        self.reload()
        return changed

    def reset(self, key: str) -> None:
        self.db.delete_setting(key)
        self.reload()

    def apply_to_config(self, cfg) -> None:
        """Copy the settings that map onto Config attributes (config.yml values lose to Settings)."""
        for spec in all_settings():
            if not spec.attr:
                continue
            if spec.key in self._stored or (spec.env and os.environ.get(spec.env)):
                value = self.get(spec.key)
                if spec.kind == "url":
                    value = str(value).rstrip("/")
                setattr(cfg, spec.attr, value)
        if not cfg.kavita_public_url:
            cfg.kavita_public_url = cfg.kavita_url

    def describe(self) -> list[dict]:
        """What the Settings page renders. Secrets only say whether they are set."""
        out = []
        for spec in all_settings():
            value = self.get(spec.key)
            out.append({
                "key": spec.key, "label": spec.label, "group": spec.group, "kind": spec.kind, "hint": spec.hint,
                "choices": [list(c) for c in spec.choices], "source": self.source(spec.key),
                "value": "" if spec.kind == "secret" else value,
                "set": bool(value) if spec.kind == "secret" else None,
            })
        return out
