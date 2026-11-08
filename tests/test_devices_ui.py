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


def _missing_doc_rows(app, docs: str) -> list[tuple[str, str, str]]:
    """(method, url, endpoint) for each allowlisted route/method with no table row `| `METHOD` | `url` | `endpoint` |`."""
    missing = []
    for endpoint in sorted(DEVICE_ALLOWED_ENDPOINTS):
        rules = _rules_for(app, endpoint)
        assert rules, f"allowlisted endpoint {endpoint} has no route"
        for rule in app.url_map.iter_rules():
            if rule.endpoint != endpoint:
                continue
            url = re.sub(r"<\w+(?:\([^)]*\))?:", "<", rule.rule)   # docs write <series_id>, not <int:series_id>
            for method in sorted(rule.methods - {"HEAD", "OPTIONS"}):
                row = rf"^\|\s*`{method}`\s*\|\s*`{re.escape(url)}`\s*\|\s*`{re.escape(endpoint)}`\s*\|\s*$"
                if not re.search(row, docs, re.M):
                    missing.append((method, url, endpoint))
    return missing


def test_api_docs_list_every_allowlisted_endpoint(app_ctx):
    app, _ = app_ctx
    docs = (ROOT / "docs" / "api-v1.md").read_text()
    assert _missing_doc_rows(app, docs) == []


@pytest.mark.parametrize("url", ["/api/double-chapters/series", "/api/v1/changes", "/api/health",
                                 "/api/double-chapters/trash"])
def test_docs_check_fails_when_a_row_is_removed(app_ctx, url):
    """Removing one row must be caught even though longer URLs (or prose) still contain that URL."""
    app, _ = app_ctx
    docs = (ROOT / "docs" / "api-v1.md").read_text()
    kept = [line for line in docs.splitlines() if not re.match(rf"^\|\s*`GET`\s*\|\s*`{re.escape(url)}`", line)]
    assert len(kept) == len(docs.splitlines()) - 1
    missing = _missing_doc_rows(app, "\n".join(kept))
    assert [m[1] for m in missing] == [url]


def create_pairing(app, base_url="http://localhost"):
    from tests.test_bearer_auth import ADMIN_PASSWORD
    c = app.test_client()
    # Log in on the same host: the test client scopes the session cookie to it.
    assert c.post("/login", data={"username": "admin", "password": ADMIN_PASSWORD},
                  base_url=base_url).status_code == 302
    r = c.post("/api/v1/devices", json={"name": "Pixel"}, base_url=base_url)
    assert r.status_code == 201
    body = r.get_json()
    return decode_pairing(body["pairing"])["url"], body.get("warning")


def test_public_url_wins_over_request_host(app_ctx):
    # Behind an HTTPS reverse proxy without web.behind_proxy the request looks like http://internal-name.
    app, ctx = app_ctx
    ctx.cfg.web_public_url = "https://manga.example.com/"
    url, warning = create_pairing(app, base_url="http://manga-manager:5000")
    assert url == "https://manga.example.com"
    assert warning is None


def test_public_url_keeps_a_sub_path(app_ctx):
    app, ctx = app_ctx
    ctx.cfg.web_public_url = "https://example.com/manga"
    assert create_pairing(app)[0] == "https://example.com/manga"


def test_http_to_public_host_returns_a_warning(app_ctx):
    app, _ = app_ctx
    url, warning = create_pairing(app, base_url="http://manga.example.com")
    assert url == "http://manga.example.com"
    assert warning and "web.public_url" in warning and "behind_proxy" in warning


def test_https_request_has_no_warning(app_ctx):
    app, _ = app_ctx
    url, warning = create_pairing(app, base_url="https://manga.example.com")
    assert url == "https://manga.example.com" and warning is None


@pytest.mark.parametrize("base", ["http://192.168.1.5:5000", "http://10.0.0.2", "http://172.20.1.1",
                                  "http://100.64.0.9", "http://169.254.1.1", "http://nas.local:8080",
                                  "http://localhost", "http://127.0.0.1:5000", "http://[::1]:5000",
                                  "http://[fd12::1]"])
def test_http_to_private_host_has_no_warning(app_ctx, base):
    app, _ = app_ctx
    url, warning = create_pairing(app, base_url=base)
    assert url == base and warning is None


@pytest.mark.parametrize("host,private", [
    ("192.168.1.5", True), ("10.1.2.3", True), ("172.16.0.1", True), ("172.32.0.1", False),
    ("100.64.0.1", True), ("100.128.0.1", False), ("169.254.9.9", True), ("127.0.0.1", True),
    ("localhost", True), ("nas.local", True), (".local", False), ("nas.local.", False), ("8.8.8.8", False),
    ("example.com", False), ("::1", True), ("fe80::1", True), ("fd00::1", True), ("2001:db8::1", False),
    ("::ffff:192.168.1.1", True), ("010.0.0.1", False), ("", False),
])
def test_is_private_host_matches_the_app_rules(host, private):
    from manga_manager.v1_devices_routes import is_private_host
    assert is_private_host(host) is private


def test_devices_js_shows_the_warning_escaped():
    js = (ROOT / "manga_manager" / "static" / "devices.js").read_text()
    assert "body.warning" in js
    # Server text goes in with textContent, never innerHTML.
    assert re.search(r"dev-url-warn'\)\.textContent\s*=\s*body\.warning", js)
    html = (ROOT / "manga_manager" / "templates" / "settings.html").read_text()
    assert 'id="dev-url-warn"' in html
