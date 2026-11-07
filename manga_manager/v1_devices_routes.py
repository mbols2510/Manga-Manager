"""/api/v1/devices: create, list and revoke Android companion tokens.

Only an administrator's cookie session reaches these: guard() refuses device tokens on every endpoint
outside DEVICE_ALLOWED_ENDPOINTS, and the endpoints are in ADMIN_ENDPOINTS for signed-up accounts.
"""
from __future__ import annotations

import logging

from flask import Blueprint, jsonify, request, session

from .config import Config
from .devices import DeviceStore

log = logging.getLogger(__name__)

NAME_MAX = 64
AUTH_OFF = "Device tokens need dashboard login (web.auth.enabled); without it the API is open to anyone."


def _clean_name(value) -> str | None:
    if not isinstance(value, str):
        return None
    name = value.strip()
    if not name or len(name) > NAME_MAX or not name.isprintable():
        return None
    return name


def _iso(value):
    return value.isoformat() if value is not None else None


def build_blueprint(cfg: Config, store: DeviceStore) -> Blueprint:
    bp = Blueprint("v1_devices", __name__)

    @bp.post("/api/v1/devices")
    def create_device():
        if not cfg.auth_enabled:
            return jsonify({"error": AUTH_OFF}), 409
        data = request.get_json(silent=True)
        name = _clean_name(data.get("name")) if isinstance(data, dict) else None
        if name is None:
            return jsonify({"error": f"Give the device a name of 1 to {NAME_MAX} characters."}), 400
        user = session.get("user") or ""
        device_id, token = store.create(name, user)
        log.info("Device token %d (%r) created by %s", device_id, name, user)
        base = request.host_url.rstrip("/")
        return jsonify({"id": device_id, "token": token, "pairing": DeviceStore.pairing_code(base, token)}), 201

    @bp.get("/api/v1/devices")
    def list_devices():
        if not cfg.auth_enabled:
            return jsonify({"error": AUTH_OFF}), 409
        items = [{"id": d["id"], "name": d["name"], "user": d["user"], "created_at": _iso(d["created_at"]),
                  "last_seen": _iso(d["last_seen"]), "revoked_at": _iso(d["revoked_at"])} for d in store.list()]
        return jsonify(items)

    @bp.delete("/api/v1/devices/<int:device_id>")
    def revoke_device(device_id: int):
        if not cfg.auth_enabled:
            return jsonify({"error": AUTH_OFF}), 409
        if not store.revoke(device_id):
            return jsonify({"error": "No active device with that id."}), 404
        log.info("Device token %d revoked by %s", device_id, session.get("user"))
        return "", 204

    return bp
