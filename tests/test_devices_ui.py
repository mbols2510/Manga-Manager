"""Settings > Devices card, the pairing response (code + QR) and docs/api-v1.md coverage."""
import base64
import json
import re
from pathlib import Path

import pytest

from manga_manager.devices import DeviceStore
from manga_manager.web import DEVICE_ALLOWED_ENDPOINTS
from tests.test_bearer_auth import GUEST_PASSWORD, app_ctx, build_app, login  # noqa: F401

ROOT = Path(__file__).resolve().parent.parent


def decode_pairing(code: str) -> dict:
    assert code.startswith("mm1.")
    body = code[4:]
    return json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))


def test_settings_page_has_devices_card_for_admin(app_ctx):
    app, _ = app_ctx
    r = login(app.test_client()).get("/settings")
    html = r.get_data(as_text=True)
    assert r.status_code == 200
    assert 'id="devices-h"' in html
    assert re.search(r'<script src="[^"]*static/devices\.js"', html)


def test_settings_devices_card_not_available_to_non_admin(app_ctx):
    app, ctx = app_ctx
    from manga_manager.auth import UserStore
    from tests.test_bearer_auth import ADMIN_HASH
    users = UserStore(ctx.db, {"admin": ADMIN_HASH})
    users.create("first", GUEST_PASSWORD)
    users.create("guest", GUEST_PASSWORD)
    guest = login(app.test_client(), "guest", GUEST_PASSWORD)
    r = guest.get("/settings")
    assert r.status_code == 403
    assert "devices-h" not in r.get_data(as_text=True)


def test_create_device_returns_pairing_code_decodable(app_ctx):
    app, _ = app_ctx
    body = login(app.test_client()).post("/api/v1/devices", json={"name": "Pixel"}).get_json()
    payload = decode_pairing(body["pairing"])
    assert payload == {"v": 1, "url": "http://localhost", "token": body["token"]}


def test_create_device_includes_qr_svg(app_ctx):
    app, _ = app_ctx
    body = login(app.test_client()).post("/api/v1/devices", json={"name": "Pixel"}).get_json()
    svg = body["qr_svg"]
    assert svg.startswith("<svg")
    assert "<?xml" not in svg
    assert body["token"] not in svg
    assert body["pairing"] not in svg
    stripped = svg.replace("http://www.w3.org/2000/svg", "")
    assert "http://" not in stripped and "https://" not in stripped


def test_devices_js_escapes_names():
    js = (ROOT / "manga_manager" / "static" / "devices.js").read_text()
    assert re.search(r"const esc\s*=", js), "an esc helper must exist"
    assert "textContent" in js
    # Every innerHTML assignment (a statement may continue over lines ending in "+") must be an empty string,
    # the server-made QR svg, or a template whose ${...} parts are esc()-wrapped or plain local identifiers.
    statements = re.findall(r"innerHTML\s*=\s*((?:[^;]*?\+\s*\n)*[^;]*);", js)
    assert len(statements) >= 3
    for rhs in statements:
        rhs = rhs.strip()
        if rhs in ("''", '""') or rhs == "body.qr_svg":
            continue
        assert rhs.startswith("`"), rhs
        for expr in re.findall(r"\$\{([^}]*)\}", rhs):
            assert re.fullmatch(r"esc\([^()]*(\([^()]*\))?[^()]*\)|cls|label", expr.strip()), f"unescaped: {expr}"
    # Names reach the DOM through textContent / aria-label or esc(), never raw into markup.
    assert "b.textContent = 'Revoke'" in js and "esc(d.name)" in js
    assert "localStorage" not in js and "sessionStorage" not in js


def _rules_for(app, endpoint):
    return [r.rule for r in app.url_map.iter_rules() if r.endpoint == endpoint]


def test_api_docs_list_every_allowlisted_endpoint(app_ctx):
    app, _ = app_ctx
    docs = (ROOT / "docs" / "api-v1.md").read_text()
    missing = []
    for endpoint in sorted(DEVICE_ALLOWED_ENDPOINTS):
        rules = _rules_for(app, endpoint)
        assert rules, f"allowlisted endpoint {endpoint} has no route"
        for rule in rules:
            documented = re.sub(r"<\w+(?:\([^)]*\))?:", "<", rule)   # docs write <series_id>, not <int:series_id>
            if documented not in docs:
                missing.append((endpoint, rule))
    assert not missing
