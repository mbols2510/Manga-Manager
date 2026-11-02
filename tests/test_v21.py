from conftest import shared_db
"""Tests for 2.1.0: issues #1-#6."""
import json

import pytest

from manga_manager.auth import LoginLimiter, ThumbSigner, hash_password
from manga_manager.config import ConfigError, load_config
from manga_manager.covers import CoverStore
from manga_manager.history import History
from manga_manager.kavita import KavitaSeries
from manga_manager.notifier import Notifier
from manga_manager.suwayomi import SuwayomiManga
from manga_manager.web import create_app

from test_pipeline import FakeKavita, FakeSuwayomi, build, env, jpeg_bytes, make_cbz  # noqa: F401


# ---------------------------------------------------------------- #1 alias names
def test_alias_name_used_in_notification_and_dashboard(env):
    cfg, libs, _ = env
    cfg.aliases = {"Ore dake Level Up na Ken": "Solo Leveling"}
    (libs["Regular"] / "Solo Leveling").mkdir()
    make_cbz(cfg.downloads / "Src" / "Ore dake Level Up na Ken" / "1.cbz", "Ore dake Level Up na Ken", "1")
    pipe, history, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Ore dake Level Up na Ken", ["Regular"])]),
                                    FakeKavita([KavitaSeries(9, "Solo Leveling", 1, True, "/m/Solo Leveling")]))
    pipe.run()
    assert notifier.sent[0]["title"] == "Solo Leveling"
    assert history.list_updates()[0][0]["title"] == "Solo Leveling"


def test_stripped_match_uses_kavita_name(env):
    cfg, libs, _ = env
    (libs["Regular"] / "Tyrant (Official)").mkdir()
    make_cbz(cfg.downloads / "Src" / "Tyrant" / "1.cbz", "Tyrant", "1")
    pipe, _, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Tyrant", ["Regular"])]),
                              FakeKavita([KavitaSeries(3, "Tyrant (Official)", 1, True, "/m/Tyrant (Official)")]))
    pipe.run()
    assert notifier.sent[0]["title"] == "Tyrant (Official)"


def test_new_series_with_alias_uses_alias(env):
    cfg, *_ = env
    cfg.aliases = {"Suwa Name": "Kavita Name"}
    make_cbz(cfg.downloads / "Src" / "Suwa Name" / "1.cbz", "Suwa Name", "1")
    pipe, _, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Suwa Name", ["Regular"])]), FakeKavita([]))
    pipe.run()
    assert notifier.sent[0]["title"] == "Kavita Name"


def test_old_dashboard_rows_show_alias_and_are_searchable(env):
    cfg, *_ = env
    cfg.aliases = {"Ore dake Level Up na Ken": "Solo Leveling"}
    history = History(shared_db())
    history.add_update(title="Ore dake Level Up na Ken", category="Regular", is_new=False, chapters=["1"])
    client = create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None).test_client()
    items = client.get("/api/updates?q=solo").get_json()["items"]
    assert [i["title"] for i in items] == ["Solo Leveling"]


# ---------------------------------------------------------------- #3 new vs replaced
def test_replaced_chapters_are_detected(env):
    cfg, libs, _ = env
    folder = libs["Regular"] / "Series"
    make_cbz(folder / "Group A_Chapter 6.cbz", "Series", "6")        # same number, other file name
    make_cbz(folder / "Group A_Chapter 7.cbz", "Series", "7")        # same file name
    make_cbz(folder / "Group A_Chapter 4.cbz", "Series", "4")
    for n in ("5", "6", "7"):
        name = f"Group {'B' if n == '6' else 'A'}_Chapter {n}.cbz"
        make_cbz(cfg.downloads / "Src" / "Series" / name, "Series", n)
    pipe, history, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Series", ["Regular"])]),
                                    FakeKavita([KavitaSeries(2, "Series", 1, True, "/m/Series")]))
    pipe.run()
    assert notifier.sent[0]["chapters"] == ["5", "6", "7"]
    # Since 2.4.0 (#10) only the same file name counts as replaced; chapter 6 is a duplicate.
    assert notifier.sent[0]["replaced"] == ["7"]
    assert notifier.sent[0]["duplicates"] == ["6"]
    assert history.list_updates()[0][0]["replaced"] == ["7"]


def test_new_series_has_no_replaced_chapters(env):
    cfg, *_ = env
    make_cbz(cfg.downloads / "Src" / "Fresh" / "Chapter 1.cbz", "Fresh", "1")
    pipe, _, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Fresh", ["Regular"])]), FakeKavita([]))
    pipe.run()
    assert notifier.sent[0]["replaced"] == []


def test_chapter_status_text():
    n = Notifier("http://ntfy", "t")
    assert n.chapter_text(["5", "6", "7"], ["7"]) == (
        "\u25CF Chapter: 5 (New)\n\u25CF Chapter: 6 (New)\n\u25CF Chapter: 7 (Replaced)\n")
    n.chapter_status = False
    assert n.chapter_text(["5"], []) == "\u25CF Chapter: 5\n", "old format still available"




# ---------------------------------------------------------------- #4 cover in notification
class Capture:
    def __init__(self):
        self.calls = []

    def __call__(self, url=None, **kw):
        self.calls.append((url, kw))

        class R:
            ok = True
        return R()


def test_notification_attaches_cover(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.notifier.requests.post", cap)
    signer = ThumbSigner("secret")
    n = Notifier("http://ntfy", "Manga_Updates", "MangaUpdates", delay=0, dashboard_url="https://dash.example/",
                 thumb_path=signer.path)
    assert n.send(title="T", is_new=False, category="Regular", chapters=["1", "2"], suwayomi_id=42, replaced=["2"])
    url, kw = cap.calls[0]
    body = kw["json"]
    cover = f"https://dash.example/thumb/42.png?s={signer.sign(42)}"
    assert body["attach"] == cover and body["icon"] == cover
    assert body["click"] == "https://dash.example"
    assert body["message"].endswith("Chapter: 2 (Replaced)")


def test_notification_without_dashboard_url_has_no_cover(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.notifier.requests.post", cap)
    Notifier("http://ntfy", "t", "MangaUpdates", delay=0).send(title="T", is_new=True, category="R",
                                                               chapters=["1"], suwayomi_id=1)
    url, kw = cap.calls[0]
    assert kw["params"] == {"template": "MangaUpdates"} and "Attach" not in kw["headers"]


# ---------------------------------------------------------------- #2 login
@pytest.fixture
def auth_app(env):
    cfg, *_ = env
    cfg.auth_enabled = True
    cfg.auth_users = {"maarten": hash_password("correct horse battery")}
    history = History(shared_db())
    history.add_update(title="A", category="Regular", is_new=True, chapters=["1"], suwayomi_id=9)
    (cfg.suwayomi_thumbnails / "9.jpg").write_bytes(jpeg_bytes())
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, FakeSuwayomi([]))
    clock = [0.0]
    limiter = LoginLimiter(attempts=3, window=900, clock=lambda: clock[0])
    app = create_app(cfg, history, covers, None, limiter=limiter)
    return cfg, app, clock


def login(client, user, pw):
    return client.post("/login", data={"username": user, "password": pw})


def test_everything_needs_login(auth_app):
    cfg, app, _ = auth_app
    c = app.test_client()
    r = c.get("/")
    assert r.status_code == 302 and "/login" in r.headers["Location"]
    assert c.get("/api/updates").status_code == 401
    assert c.get("/api/status").status_code == 401
    assert c.post("/api/run").status_code == 401
    assert c.get("/thumb/9.png").status_code == 401
    assert c.get("/health").status_code == 200, "Docker health check keeps working"
    assert c.get("/signup").status_code == 404, "sign-up is off by default"


def test_login_logout_and_wrong_password(auth_app):
    cfg, app, _ = auth_app
    c = app.test_client()
    assert login(c, "maarten", "wrong password!").status_code == 401
    r = login(c, "Maarten", "correct horse battery")
    assert r.status_code == 302
    assert c.get("/api/updates").status_code == 200
    assert b"Signed in as" in c.get("/").data
    c.post("/logout")
    assert c.get("/api/updates").status_code == 401


def test_login_redirects_only_to_own_site(auth_app):
    _, app, _ = auth_app
    c = app.test_client()
    r = c.post("/login?next=//evil.example/x", data={"username": "maarten", "password": "correct horse battery"})
    assert r.headers["Location"] == "/"
    c.post("/logout")
    r = c.post("/login?next=/api/status", data={"username": "maarten", "password": "correct horse battery"})
    assert r.headers["Location"] == "/api/status"


def test_login_rate_limited(auth_app):
    _, app, clock = auth_app
    c = app.test_client()
    for _ in range(3):
        login(c, "maarten", "nope nope nope")
    assert login(c, "maarten", "correct horse battery").status_code == 429, "blocked even with the right password"
    clock[0] += 901
    assert login(c, "maarten", "correct horse battery").status_code == 302


def test_signed_thumbnail_works_without_login(auth_app):
    cfg, app, _ = auth_app
    from manga_manager.auth import load_secret_key
    signer = ThumbSigner(load_secret_key(cfg))
    c = app.test_client()
    assert c.get("/" + signer.path(9)).status_code == 200
    assert c.get("/thumb/9.png?s=" + signer.sign(8)).status_code == 401, "signature is per cover"


def test_cross_site_post_rejected(auth_app):
    _, app, _ = auth_app
    c = app.test_client()
    r = c.post("/login", data={"username": "maarten", "password": "correct horse battery"},
               headers={"Origin": "https://evil.example"})
    assert r.status_code == 403


def test_removed_account_loses_access(auth_app):
    cfg, app, _ = auth_app
    c = app.test_client()
    login(c, "maarten", "correct horse battery")
    cfg.auth_users.clear()
    app2 = create_app(cfg, History(shared_db()), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    c2 = app2.test_client()
    c2.set_cookie("manga_manager_session", c.get_cookie("manga_manager_session").value)
    assert c2.get("/api/updates").status_code == 401


def test_signup_when_enabled_with_code(auth_app):
    cfg, _, _ = auth_app
    cfg.auth_allow_signup = True
    cfg.auth_signup_code = "letmein"
    app = create_app(cfg, History(shared_db()), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    c = app.test_client()
    form = {"username": "friend", "password": "a long password", "confirm": "a long password"}
    assert b"Invite code" in c.get("/signup").data
    assert c.post("/signup", data={**form, "code": "wrong"}).status_code == 403
    assert c.post("/signup", data={**form, "code": "letmein", "username": "maarten"}).status_code == 400, "taken"
    assert c.post("/signup", data={**form, "code": "letmein", "confirm": "different pw!"}).status_code == 400
    assert c.post("/signup", data={**form, "code": "letmein"}).status_code == 302
    assert c.get("/api/updates").status_code == 200
    c.post("/logout")
    assert login(c, "friend", "a long password").status_code == 302


def test_auth_config(tmp_path):
    base = ("suwayomi: {url: http://s}\nkavita: {url: http://k, api_key: x}\n"
            "libraries: [{category: Regular, kavita_library_id: 1, path: /m}]\n")
    f = tmp_path / "c.yml"
    f.write_text(base + 'web: {auth: {enabled: true, users: {maarten: "scrypt:x"}}}\n')
    cfg = load_config(f)
    assert cfg.auth_enabled and not cfg.auth_allow_signup and cfg.auth_users == {"maarten": "scrypt:x"}
    assert cfg.web_mature_categories == ["Adult", "Hentai"]


def test_secret_key_is_kept_between_restarts(env):
    from manga_manager.auth import load_secret_key
    cfg, *_ = env
    assert load_secret_key(cfg) == load_secret_key(cfg)
    cfg.auth_secret_key = "from-config"
    assert load_secret_key(cfg) == "from-config"


# ---------------------------------------------------------------- #5 Kavita link / #6 labels
def test_address_notes_flag_home_addresses(env):
    from manga_manager.__main__ import address_notes
    cfg, *_ = env
    cfg.kavita_public_url = "http://192.168.1.20:5000"
    assert any("kavita.public_url" in l for l in address_notes(cfg))
    cfg.kavita_public_url = "https://kavita.example.be"
    assert not any("kavita.public_url" in l for l in address_notes(cfg))


def test_example_config_loads_and_links_default_to_url(tmp_path, monkeypatch):
    from pathlib import Path
    monkeypatch.setenv("KAVITA_API_KEY", "x")
    cfg = load_config(Path(__file__).parent.parent / "config.example.yml")
    assert cfg.kavita_public_url == cfg.kavita_url and not cfg.auth_enabled


def test_dashboard_marks_mature_categories(env):
    cfg, *_ = env
    cfg.web_blur_categories = []
    app = create_app(cfg, History(shared_db()), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    html = app.test_client().get("/").data.decode()
    assert 'const MATURE = ["adult", "hentai"]' in html
    assert 'class="age"' in html          # the 18+ badge
