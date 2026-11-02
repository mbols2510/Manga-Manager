"""The tabs. Each module in this package exposes `init(ctx) -> flask.Blueprint`.

A tool registers its settings (settings.register) at import time, adds background jobs with
ctx.jobs.add(...) inside init(), and serves its page at /<slug> and its API under /api/<slug>/.
"""
from __future__ import annotations

import importlib
import logging

log = logging.getLogger(__name__)

TOOL_MODULES = ("double_chapters", "collections", "cbz")


def load_tools(ctx):
    """Import every tool and return their blueprints. A broken tool must not take the app down."""
    blueprints = []
    for name in TOOL_MODULES:
        try:
            mod = importlib.import_module(f"{__name__}.{name}")
            blueprints.append(mod.init(ctx))
        except Exception:  # noqa: BLE001
            log.exception("Tool %s failed to load", name)
    return blueprints


def import_settings_only() -> None:
    """Import tool modules so their settings are registered (used by the Settings page and tests)."""
    for name in TOOL_MODULES:
        try:
            importlib.import_module(f"{__name__}.{name}")
        except Exception:  # noqa: BLE001
            log.exception("Tool %s failed to import", name)
