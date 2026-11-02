"""Settings page, connection tests and the health endpoint."""
from __future__ import annotations

import logging
import os
import signal
import threading

from flask import Blueprint, jsonify, render_template, request, session

from . import __version__
from .context import AppContext
from .settings import all_settings

log = logging.getLogger(__name__)


def build_blueprint(ctx: AppContext, user_for_template) -> Blueprint:
    bp = Blueprint("core", __name__)

    # ---------------------------------------------------------------- health
    @bp.get("/health")
    def health():
        try:
            ctx.db.ping()
            return {"status": "ok", "version": __version__}
        except Exception as exc:  # noqa: BLE001
            log.warning("Health check: database error: %s", exc)
            return {"status": "error"}, 503

    @bp.get("/api/health")
    def health_detail():
        guard = ctx.guard
        checks = guard.checks(force=True) if guard else []
        return jsonify({"version": __version__, "checks": checks, "jobs": ctx.jobs.status(),
                        "paused": guard.reason() if guard else None})

    @bp.post("/api/health/test-notification")
    def test_notification():
        from . import notify
        try:
            ctx.ntfy_publish(ctx.cfg.ntfy_topic, "✅ Manga Manager test",
                             "Notifications are working. This is how a Manga Manager message looks.",
                             tags=["white_check_mark"], priority=2,
                             click=ctx.dashboard_url())
        except notify.NtfyError as exc:
            return jsonify({"ok": False, "error": str(exc)}), 502
        return jsonify({"ok": True})

    # -------------------------------------------------------------- settings
    @bp.get("/settings")
    def settings_page():
        return render_template("settings.html", page_title="Settings", active="settings",
                               user=user_for_template(), version=__version__)

    @bp.get("/api/settings")
    def settings_get():
        return jsonify({"items": ctx.settings.describe(), "database": _redact(ctx.db.url)})

    @bp.post("/api/settings")
    def settings_save():
        data = request.get_json(silent=True) or {}
        known = {s.key for s in all_settings()}
        values = {k: v for k, v in (data.get("values") or {}).items() if k in known}
        changed = ctx.settings.set_many(values)
        ctx.settings.apply_to_config(ctx.cfg)
        return jsonify({"changed": changed, "restart_needed": bool(changed)})

    @bp.post("/api/settings/restart")
    def settings_restart():
        # The container's restart policy brings it back up with the saved settings.
        threading.Timer(1.0, lambda: os.kill(os.getpid(), signal.SIGTERM)).start()
        return jsonify({"restarting": True})

    return bp


def _redact(url: str) -> str:
    from urllib.parse import urlparse
    p = urlparse(url)
    host = p.hostname or ""
    return f"{p.scheme}://{p.username + '@' if p.username else ''}{host}{':' + str(p.port) if p.port else ''}{p.path}"
