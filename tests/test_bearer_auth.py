"""Bearer device tokens: deny-by-default allowlist, uniform errors, rate limits, admin-only device management."""
import logging
import re

import pytest
from werkzeug.security import generate_password_hash

from manga_manager import web
from manga_manager.config import Config
from manga_manager.context import AppContext
from manga_manager.covers import CoverStore
from manga_manager.devices import DeviceStore
from manga_manager.history import History
from manga_manager.settings import Settings
from manga_manager.web import ADMIN_ENDPOINTS, DEVICE_ALLOWED_ENDPOINTS, create_app

ADMIN_PASSWORD = "admin-password-123"
GUEST_PASSWORD = "guest-password-123"
ADMIN_HASH = generate_password_hash(ADMIN_PASSWORD)
UNAUTHORIZED = {"error": "unauthorized"}
DEVICE_FORBIDDEN = {"error": "this device cannot use this endpoint"}
SKIP_METHODS = {"HEAD", "OPTIONS"}


def build_app(db, tmp_path, auth=True, monkeypatch=None):
    cfg = Config(data_dir=tmp_path / "data", config_dir=tmp_path / "config",
                 auth_enabled=auth, auth_users={"admin": ADMIN_HASH} if auth else {},
                 auth_secret_key="test-secret-key")
    ctx = AppContext(cfg=cfg, db=db, settings=Settings(db))
    if monkeypatch is not None:
        # Safety net: never let a test request restart (SIGTERM) the test runner.
        monkeypatch.setattr("manga_manager.core_routes.threading.Timer",
                            lambda *a, **k: type("T", (), {"start": lambda self: None})())
    app = create_app(cfg, History(db), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None,
                     ctx=ctx)
    return app, ctx


def login(client, user="admin", password=ADMIN_PASSWORD):
    r = client.post("/login", data={"username": user, "password": password})
    assert r.status_code == 302, r.data
    return client


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


def placeholder_path(rule) -> str:
    """The rule's path with each URL variable filled with a value its converter accepts."""
    def fill(m):
        conv = m.group(1) or "default"
        return "1" if conv in ("int", "float") else "x"
    return re.sub(r"<(?:(\w+)(?:\([^)]*\))?:)?\w+>", fill, rule.rule)


def device_reachable(app, client, headers, skip=frozenset()):
    """Every (endpoint, method, path, status) that a device token is NOT refused with the guard's 403 on."""
    leaks = []
    for rule in app.url_map.iter_rules():
        if rule.endpoint in skip:
            continue
        path = placeholder_path(rule)
        for method in sorted(rule.methods - SKIP_METHODS):
            r = client.open(path, method=method, headers=headers, json={} if method != "GET" else None)
            if r.status_code != 403 or r.get_json(silent=True) != DEVICE_FORBIDDEN:
                leaks.append((rule.endpoint, method, path, r.status_code))
    return leaks


@pytest.fixture
def app_ctx(db, tmp_path, monkeypatch):
    return build_app(db, tmp_path, monkeypatch=monkeypatch)


@pytest.fixture
def token(db):
    return DeviceStore(db).create("Pixel 8", "admin")[1]


# ---------------------------------------------------------------- basic acceptance
def test_valid_bearer_reaches_updates(app_ctx, token):
    app, _ = app_ctx
    r = app.test_client().get("/api/updates", headers=bearer(token))
    assert r.status_code == 200
    assert r.json["items"] == [] and r.json["total"] == 0
    assert r.headers["Cache-Control"] == "no-store"


def test_valid_bearer_reaches_allowlisted_doubles_and_health(app_ctx, token):
    app, _ = app_ctx
    c = app.test_client()
    assert c.get("/api/double-chapters/series", headers=bearer(token)).status_code == 200
    assert c.get("/api/double-chapters/status", headers=bearer(token)).status_code == 200
    assert c.get("/api/health", headers=bearer(token)).status_code == 200
    assert c.get("/api/status", headers=bearer(token)).status_code == 200


def test_revoked_bearer_gets_401_uniform_body(app_ctx, db):
    app, _ = app_ctx
    store = DeviceStore(db)
    device_id, token = store.create("Pixel", "admin")
    c = app.test_client()
    assert c.get("/api/updates", headers=bearer(token)).status_code == 200
    store.revoke(device_id)
    responses = [
        c.get("/api/updates", headers=bearer(token)),                         # revoked
        c.get("/api/updates", headers=bearer("not-a-real-token")),            # unknown
        c.get("/api/updates", headers={"Authorization": "Bearer"}),           # empty
        c.get("/api/updates", headers={"Authorization": "bearer\tnope"}),     # odd spelling, still a bearer
        c.get("/api/updates", headers={"Authorization": "Bearer " + "a" * 2000}),   # absurd
        c.get("/thumb/1.png", headers=bearer(token)),                         # non-JSON route, same answer
    ]
    for r in responses:
        assert r.status_code == 401
        assert r.get_json() == UNAUTHORIZED
        assert token not in r.get_data(as_text=True)
        assert "Bearer" not in r.get_data(as_text=True)


def test_bearer_takes_precedence_over_session_cookie(app_ctx, db):
    """An admin session cookie must not upgrade a request that also carries a (bad or device) token."""
    app, _ = app_ctx
    c = login(app.test_client())
    assert c.get("/api/settings").status_code == 200
    assert c.get("/api/settings", headers=bearer("bogus")).status_code == 401
    token = DeviceStore(db).create("Pixel", "admin")[1]
    r = c.get("/api/settings", headers=bearer(token))
    assert r.status_code == 403 and r.get_json() == DEVICE_FORBIDDEN


def test_non_bearer_authorization_header_is_ignored(app_ctx):
    app, _ = app_ctx
    c = app.test_client()
    assert c.get("/api/updates", headers={"Authorization": "Basic YWRtaW46eA=="}).status_code == 401
    c = login(app.test_client())
    assert c.get("/api/updates", headers={"Authorization": "Basic YWRtaW46eA=="}).status_code == 200


# ---------------------------------------------------------------- allowlist
def test_allowlist_is_exactly_the_spec_capabilities():
    assert DEVICE_ALLOWED_ENDPOINTS == frozenset({
        "updates", "status", "run_now", "thumb", "core.health_detail",
        "double_chapters.list_series", "double_chapters.series_detail", "double_chapters.cover",
        "double_chapters.remediate", "double_chapters.kavita_scan", "double_chapters.trash",
        "double_chapters.trash_restore", "double_chapters.status",
        "v1.changes", "v1.latest",
    })
    assert not DEVICE_ALLOWED_ENDPOINTS & ADMIN_ENDPOINTS


def test_device_token_is_forbidden_on_every_admin_endpoint(app_ctx, token):
    app, _ = app_ctx
    admin_rules = [r for r in app.url_map.iter_rules() if r.endpoint in ADMIN_ENDPOINTS]
    assert {r.endpoint for r in admin_rules} == ADMIN_ENDPOINTS      # every admin endpoint is actually routed
    leaks = device_reachable(app, app.test_client(), bearer(token),
                             skip=frozenset(e.endpoint for e in app.url_map.iter_rules()) - ADMIN_ENDPOINTS)
    assert leaks == []


def test_device_token_is_forbidden_on_every_route_not_in_allowlist(app_ctx, token):
    """Iterates app.url_map, so routes added later are closed to devices until deliberately allowlisted.
    PUBLIC_ENDPOINTS are not skipped: a device has no business on login, sign-up or static pages either."""
    app, _ = app_ctx
    checked = [r for r in app.url_map.iter_rules() if r.endpoint not in DEVICE_ALLOWED_ENDPOINTS]
    assert len(checked) > 40                                         # the tools really are registered
    leaks = device_reachable(app, app.test_client(), bearer(token), skip=DEVICE_ALLOWED_ENDPOINTS)
    assert leaks == []
    # Unknown paths are refused too, not answered with a 404 that maps the site.
    r = app.test_client().get("/api/does-not-exist", headers=bearer(token))
    assert r.status_code == 403 and r.get_json() == DEVICE_FORBIDDEN


def test_route_check_catches_a_permissive_allowlist(app_ctx, token, monkeypatch):
    """The checker above must notice when an endpoint slips into the allowlist."""
    app, _ = app_ctx
    widened = DEVICE_ALLOWED_ENDPOINTS | {"save_alias", "core.settings_get", "collections.rules_list"}
    monkeypatch.setattr(web, "DEVICE_ALLOWED_ENDPOINTS", widened)
    leaks = device_reachable(app, app.test_client(), bearer(token), skip=DEVICE_ALLOWED_ENDPOINTS)
    assert {endpoint for endpoint, *_ in leaks} == {"save_alias", "core.settings_get", "collections.rules_list"}


# ---------------------------------------------------------------- device management
def test_admin_creates_lists_and_revokes_devices(app_ctx, db):
    app, _ = app_ctx
    c = login(app.test_client())
    r = c.post("/api/v1/devices", json={"name": "Pixel 8"})
    assert r.status_code == 201
    body = r.get_json()
    assert set(body) == {"id", "token", "pairing"}
    assert body["pairing"] == DeviceStore.pairing_code("http://localhost", body["token"])
    assert DeviceStore(db).authenticate(body["token"])["name"] == "Pixel 8"

    listed = c.get("/api/v1/devices").get_json()
    assert [d["name"] for d in listed] == ["Pixel 8"]
    assert set(listed[0]) == {"id", "name", "user", "created_at", "last_seen", "revoked_at"}
    assert body["token"] not in c.get("/api/v1/devices").get_data(as_text=True)

    assert c.delete(f"/api/v1/devices/{body['id']}").status_code == 204
    assert c.delete(f"/api/v1/devices/{body['id']}").status_code == 404
    assert c.delete("/api/v1/devices/9999").status_code == 404
    assert app.test_client().get("/api/updates", headers=bearer(body["token"])).status_code == 401


@pytest.mark.parametrize("payload", [{}, {"name": ""}, {"name": "   "}, {"name": 5}, {"name": None},
                                     {"name": "x" * 65}, {"name": "bad\nname"}, ["Pixel"], "Pixel"])
def test_device_name_is_validated(app_ctx, payload):
    app, _ = app_ctx
    c = login(app.test_client())
    r = c.post("/api/v1/devices", json=payload)
    assert r.status_code == 400 and "error" in r.get_json()
    assert c.post("/api/v1/devices", json={"name": "x" * 64}).status_code == 201


def test_device_endpoints_reject_bearer_and_non_admin_cookie(app_ctx, db):
    app, _ = app_ctx
    store = DeviceStore(db)
    device_id, token = store.create("Pixel", "admin")
    calls = [("POST", "/api/v1/devices", {"name": "evil"}), ("GET", "/api/v1/devices", None),
             ("DELETE", f"/api/v1/devices/{device_id}", None)]

    # A device token, even one created by an admin, can never manage tokens.
    for method, path, body in calls:
        r = app.test_client().open(path, method=method, headers=bearer(token), json=body)
        assert r.status_code == 403 and r.get_json() == DEVICE_FORBIDDEN
    # No credentials at all.
    for method, path, body in calls:
        assert app.test_client().open(path, method=method, json=body).status_code == 401
    # A signed-up (non-admin) account.
    from manga_manager.auth import UserStore
    users = UserStore(db, {"admin": ADMIN_HASH})
    users.create("first", GUEST_PASSWORD)          # the first signed-up account is an admin, by design
    users.create("guest", GUEST_PASSWORD)
    assert not users.is_admin("guest")
    guest = login(app.test_client(), "guest", GUEST_PASSWORD)
    for method, path, body in calls:
        assert guest.open(path, method=method, json=body).status_code == 403
    assert [d["name"] for d in store.list()] == ["Pixel"] and store.list()[0]["revoked_at"] is None


def test_devices_refused_when_auth_disabled(db, tmp_path, monkeypatch):
    app, _ = build_app(db, tmp_path, auth=False, monkeypatch=monkeypatch)
    c = app.test_client()
    for method, path, body in [("POST", "/api/v1/devices", {"name": "Pixel"}), ("GET", "/api/v1/devices", None),
                               ("DELETE", "/api/v1/devices/1", None)]:
        r = c.open(path, method=method, json=body)
        assert r.status_code == 409 and "error" in r.get_json()
    assert DeviceStore(db).list() == []
    # Bearer headers are simply ignored while login is off.
    assert c.get("/api/updates", headers=bearer("anything")).status_code == 200


# ---------------------------------------------------------------- rate limiting
def test_failed_bearer_attempts_are_rate_limited(app_ctx, token):
    app, _ = app_ctx
    c = app.test_client()
    for _ in range(8):
        assert c.get("/api/updates", headers=bearer("wrong")).status_code == 401
    r = c.get("/api/updates", headers=bearer("wrong"))
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0
    blocked_valid = c.get("/api/updates", headers=bearer(token))     # even a valid token, from that IP
    assert blocked_valid.status_code == 429 and blocked_valid.get_json() == r.get_json()
    assert token not in blocked_valid.get_data(as_text=True)
    # Another client IP is unaffected, and requests without a bearer header are not counted.
    other = app.test_client()
    assert other.get("/api/updates", headers=bearer(token),
                     environ_overrides={"REMOTE_ADDR": "10.0.0.2"}).status_code == 200
    assert login(other).get("/api/updates").status_code == 200


def test_requests_without_bearer_do_not_count_as_failures(app_ctx, token):
    app, _ = app_ctx
    c = app.test_client()
    for _ in range(12):
        assert c.get("/api/updates").status_code == 401
    assert c.get("/api/updates", headers=bearer(token)).status_code == 200


def test_valid_token_is_rate_limited_per_token(app_ctx, db, token):
    app, _ = app_ctx
    other = DeviceStore(db).create("Tablet", "admin")[1]
    c = app.test_client()
    for i in range(120):
        assert c.get("/api/status", headers=bearer(token)).status_code == 200, i
    r = c.get("/api/status", headers=bearer(token))
    assert r.status_code == 429
    assert 0 < int(r.headers["Retry-After"]) <= 60
    assert token not in r.get_data(as_text=True)
    assert c.get("/api/status", headers=bearer(other)).status_code == 200


def test_token_rate_limiter_slides():
    from manga_manager.auth import TokenRateLimiter
    now = [0.0]
    lim = TokenRateLimiter(limit=3, window=60, clock=lambda: now[0])
    assert [lim.hit(1) for _ in range(3)] == [0, 0, 0]
    assert lim.hit(1) == 60 and lim.hit(2) == 0
    now[0] = 30.0
    assert lim.hit(1) == 30
    now[0] = 60.5
    assert lim.hit(1) == 0


# ---------------------------------------------------------------- logging and activity
def test_authorization_header_never_in_logs(app_ctx, db, caplog):
    app, _ = app_ctx
    device_id, token = DeviceStore(db).create("Pixel", "admin")
    caplog.set_level(logging.DEBUG)
    c = app.test_client()
    c.get("/api/updates", headers=bearer(token))
    c.get("/api/settings", headers=bearer(token))
    c.get("/api/updates", headers=bearer("wrong-token-value"))
    for _ in range(9):
        c.get("/api/updates", headers=bearer("wrong-token-value"))
    DeviceStore(db).revoke(device_id)
    c.get("/api/updates", headers=bearer(token), environ_overrides={"REMOTE_ADDR": "10.0.0.9"})
    text = caplog.text
    assert caplog.records                     # the failures were logged, just without the secret
    assert token not in text and "wrong-token-value" not in text
    assert "Authorization" not in text and "Bearer " not in text


def test_remediate_by_device_is_recorded_in_activity_with_device_name(app_ctx, db, tmp_path, token):
    app, ctx = app_ctx
    root = tmp_path / "manga"
    (root / "Regular" / "Berserk").mkdir(parents=True)
    for name in ("ch1-0.cbz", "ch1-1.cbz", "ch2-0.cbz", "ch2-1.cbz"):
        (root / "Regular" / "Berserk" / name).write_bytes(b"x")
    db.set_setting("dc.manga_root", str(root))
    db.set_setting("dc.kavita_manga_root", "/manga")
    ctx.settings.reload()
    tool = ctx.extras["double_chapters"]

    def files(n):
        return [{"id": n * 10 + i, "filePath": f"/manga/Regular/Berserk/ch{n}-{i}.cbz", "bytes": 1} for i in (0, 1)]
    tool.save("series", {"1": {"id": 1, "name": "Berserk", "libraryId": 1,
                               "chapters": [{"id": 1, "files": files(1)}, {"id": 2, "files": files(2)}]}})

    r = app.test_client().post("/api/double-chapters/series/1/chapters/1/remediate", json={"keepFileId": 10},
                               headers=bearer(token))
    assert r.status_code == 200 and len(r.json["moved"]) == 1
    login_client = login(app.test_client())
    assert login_client.post("/api/double-chapters/series/1/chapters/2/remediate",
                             json={"keepFileId": 20}).status_code == 200

    with db.connect() as c:
        rows = c.execute("SELECT kind, message FROM activity ORDER BY id").fetchall()
    trash = [r["message"] for r in rows if r["kind"] == "trash"]
    assert trash == ["Trashed 1 file(s) from Berserk (device: Pixel 8)",
                     "Trashed 1 file(s) from Berserk"]           # cookie users: unchanged

    # Restoring from the trash by a device is recorded too.
    entry = next(e for e in tool.trash_list() if e["chapterId"] == 1)
    tool._client = type("K", (), {"scan_series": lambda *a: True, "get_series_volumes": lambda *a: []})()
    tool._client_key = (ctx.cfg.kavita_url, ctx.cfg.kavita_api_key)
    r = app.test_client().post(f"/api/double-chapters/trash/{entry['id']}/restore", headers=bearer(token))
    assert r.status_code == 200
    with db.connect() as c:
        restored = c.execute("SELECT message FROM activity WHERE kind = 'restore'").fetchall()
    assert [r["message"] for r in restored] == ["Restored ch1-1.cbz to Berserk (device: Pixel 8)"]
