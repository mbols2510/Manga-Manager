"""Settings page, connection tests and the health endpoint."""
from __future__ import annotations

import logging
import os
import signal
import threading
import time

import requests
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
            return {"status": "error", "database": str(exc)[:200]}, 503

    def check(name, fn):
        started = time.monotonic()
        try:
            detail = fn()
            return {"name": name, "state": "ok", "detail": detail, "ms": int((time.monotonic() - started) * 1000)}
        except SkipCheck as exc:
            return {"name": name, "state": "unset", "detail": str(exc), "ms": 0}
        except Exception as exc:  # noqa: BLE001
            return {"name": name, "state": "fail", "detail": str(exc)[:300], "ms": int((time.monotonic() - started) * 1000)}

    class SkipCheck(Exception):
        pass

    def check_db():
        ctx.db.ping()
        return "connected"

    def check_kavita():
        cfg = ctx.cfg
        if not (cfg.kavita_url and cfg.kavita_api_key):
            raise SkipCheck("Kavita URL / API key not set")
        from .kavita import KavitaClient
        client = KavitaClient(cfg.kavita_url, cfg.kavita_api_key, cfg.kavita_plugin_name)
        return f"{len(client.all_series())} series visible"

    def check_suwayomi():
        cfg = ctx.cfg
        if not cfg.suwayomi_url:
            raise SkipCheck("Suwayomi URL not set")
        from .suwayomi import SuwayomiClient
        client = SuwayomiClient(cfg.suwayomi_url, cfg.suwayomi_username, cfg.suwayomi_password)
        return f"{len(client.library())} manga in library"

    def check_ntfy():
        cfg = ctx.cfg
        if not cfg.ntfy_url:
            raise SkipCheck("ntfy URL not set")
        r = requests.get(cfg.ntfy_url + "/v1/health", timeout=8)
        r.raise_for_status()
        return "reachable"

    @bp.get("/api/health")
    def health_detail():
        checks = [check("Database", check_db), check("Kavita", check_kavita),
                  check("Suwayomi", check_suwayomi), check("ntfy", check_ntfy)]
        return jsonify({"version": __version__, "checks": checks, "jobs": ctx.jobs.status()})

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
