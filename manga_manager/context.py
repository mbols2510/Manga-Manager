"""What every tab gets handed when the app starts."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .config import Config
from .db import Database
from .jobs import JobRunner
from .settings import Settings


@dataclass
class AppContext:
    cfg: Config
    db: Database
    settings: Settings
    jobs: JobRunner = field(default_factory=JobRunner)
    extras: dict[str, Any] = field(default_factory=dict)   # e.g. "kavita": KavitaClient

    def ntfy_publish(self, topic: str, title: str, message: str, **kw) -> None:
        """Publish through the shared ntfy settings. Raises notify.NtfyError."""
        from . import notify
        if not self.cfg.ntfy_enabled:
            raise notify.NtfyError("Notifications are turned off (Settings → Notifications)")
        notify.publish(url=self.cfg.ntfy_url, topic=topic, title=title, message=message,
                       token=self.cfg.ntfy_token, **kw)

    def dashboard_url(self) -> str:
        return (self.cfg.web_public_url or "").rstrip("/")
