"""/api/v1/devices: create, list and revoke Android companion tokens.

Only an administrator's cookie session reaches these: guard() refuses device tokens on every endpoint
outside DEVICE_ALLOWED_ENDPOINTS, and the endpoints are in ADMIN_ENDPOINTS for signed-up accounts.
"""
from __future__ import annotations

import ipaddress
import logging
import re
from urllib.parse import urlsplit

import segno
from flask import Blueprint, jsonify, request, session

from .config import Config
from .devices import DeviceStore

log = logging.getLogger(__name__)

NAME_MAX = 64
AUTH_OFF = "Device tokens need dashboard login (web.auth.enabled); without it the API is open to anyone."
PUBLIC_HTTP = ("This pairing code points at {url}: plain http to a public address. The Android app refuses that, "
               "so pairing will fail. Behind an HTTPS reverse proxy set web.behind_proxy: true and/or "
               "web.public_url (e.g. https://manga.example.com) in config.yml, restart, and create a new device.")
_DOTTED_QUAD = re.compile(r"(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}")


def is_private_host(host: str) -> bool:
    """The Android app's rule (UrlPolicy.isPrivateHost) for hosts where plain http is accepted.

    localhost, *.local, loopback, RFC 1918, 100.64.0.0/10 (CGNAT, e.g. Tailscale), 169.254.0.0/16 link-local,
    and for IPv6 loopback, fc00::/7 unique-local, fe80::/10 link-local and IPv4-mapped addresses judged by their
    IPv4 part. A trailing dot, empty labels or a non-dotted-quad IPv4 form (octal, hex, short) are never private.
    """
    h = host.strip().removeprefix("[").removesuffix("]").lower()
    if not h or h.endswith(".") or h.startswith(".") or ".." in h:
        return False
    if h == "localhost" or (len(h) > len(".local") and h.endswith(".local")):
        return True
    if ":" not in h and not _DOTTED_QUAD.fullmatch(h):
        return False
    try:
        ip = ipaddress.ip_address(h)
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if isinstance(ip, ipaddress.IPv4Address):
        return any(ip in ipaddress.ip_network(n) for n in
                   ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
                    "100.64.0.0/10"))
    return ip == ipaddress.IPv6Address("::1") or any(
        ip in ipaddress.ip_network(n) for n in ("fc00::/7", "fe80::/10"))


def _public_base(value: str) -> str | None:
    """web.public_url normalised (no trailing slash), or None when it is not an http(s) URL with a host."""
    value = (value or "").strip().rstrip("/")
    try:
        parts = urlsplit(value)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return None
    return value


def pairing_base(cfg: Config, host_url: str) -> tuple[str, str | None]:
    """The URL the pairing code carries, and a warning when the app would refuse it.

    web.public_url wins (it is what the phone reaches behind a reverse proxy); otherwise the URL this request
    came in on, which behind a TLS proxy without web.behind_proxy is the proxy's plain-http upstream address.
    """
    base = _public_base(cfg.web_public_url) or host_url.rstrip("/")
    parts = urlsplit(base)
    warning = None
    if parts.scheme == "http" and not is_private_host(parts.hostname or ""):
        warning = PUBLIC_HTTP.format(url=base)
    return base, warning


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
        base, warning = pairing_base(cfg, request.host_url)
        pairing = DeviceStore.pairing_code(base, token)
        # Drawn here so the token never goes to a third-party QR service.
        qr_svg = segno.make(pairing, error="m").svg_inline(scale=6, border=2)
        body = {"id": device_id, "token": token, "pairing": pairing, "qr_svg": qr_svg}
        if warning:
            body["warning"] = warning
        resp = jsonify(body)
        resp.headers["Cache-Control"] = "no-store"
        return resp, 201

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
