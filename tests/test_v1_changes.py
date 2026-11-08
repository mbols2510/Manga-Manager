"""/api/v1/changes: cursor feed of dashboard updates for the Android companion."""
import pytest

from manga_manager.devices import DeviceStore
from manga_manager.history import History

from tests.test_bearer_auth import bearer, build_app, login


@pytest.fixture
def env(db, tmp_path, monkeypatch):
    app, ctx = build_app(db, tmp_path, monkeypatch=monkeypatch)
    token = DeviceStore(db).create("Pixel 8", "admin")[1]
    return app, History(db), db, bearer(token)


def add(h, n, category="Manga", **kw):
    return [h.add_update(title=f"T{i}", category=category, is_new=bool(i % 2), chapters=[f"c{i}"], **kw)
            for i in range(n)]


def get(app, headers, **params):
    return app.test_client().get("/api/v1/changes", headers=headers, query_string=params)


def test_returns_items_after_cursor_oldest_first(env):
    app, h, _, hd = env
    ids = add(h, 4)
    r = get(app, hd, since=ids[0])
    assert r.status_code == 200
    assert [i["id"] for i in r.json["items"]] == ids[1:]
    item = r.json["items"][0]
    assert set(item) == {"id", "title", "category", "is_new", "chapters", "created_at", "thumbnail",
                         "kavita_url", "sensitive"}
    assert item["sensitive"] is False
    assert r.headers["ETag"]
    assert r.headers["Cache-Control"] == "no-store"


def test_items_match_api_updates_fields(env):
    app, h, _, hd = env
    h.add_update(title="X", category="Manga", is_new=True, chapters=["1"], suwayomi_id=7,
                 kavita_series_id=3, kavita_library_id=2)
    feed = get(app, hd).json["items"][0]
    old = app.test_client().get("/api/updates", headers=hd).json["items"][0]
    for k in ("title", "thumbnail", "kavita_url"):
        assert feed[k] == old[k]
    assert feed["thumbnail"] == "thumb/7.png"


def test_next_cursor_is_last_item_id_and_has_more_flag(env):
    app, h, _, hd = env
    ids = add(h, 5)
    r = get(app, hd, limit=2).json
    assert [i["id"] for i in r["items"]] == ids[:2]
    assert r["next_cursor"] == ids[1] and r["has_more"] is True
    r = get(app, hd, since=ids[2], limit=2).json
    assert [i["id"] for i in r["items"]] == ids[3:] and r["has_more"] is False
    assert r["next_cursor"] == ids[4]


def test_empty_result_returns_304_with_matching_etag(env):
    app, h, _, hd = env
    ids = add(h, 2)
    first = get(app, hd, since=ids[-1])
    assert first.status_code == 200 and first.json == {"items": [], "next_cursor": ids[-1], "has_more": False}
    etag = first.headers["ETag"]
    r = get(app, {**hd, "If-None-Match": etag}, since=ids[-1])
    assert r.status_code == 304 and r.data == b""
    assert r.headers["ETag"] == etag
    assert r.headers["Cache-Control"] == "no-store"


def test_etag_changes_when_new_update_added(env):
    app, h, _, hd = env
    ids = add(h, 1)
    etag = get(app, hd, since=ids[-1]).headers["ETag"]
    new = add(h, 1)[0]
    r = get(app, {**hd, "If-None-Match": etag}, since=ids[-1])
    assert r.status_code == 200 and [i["id"] for i in r.json["items"]] == [new]
    assert r.headers["ETag"] != etag


def test_since_older_than_retention_returns_410(env):
    app, h, db, hd = env
    ids = add(h, 4)
    with db.connect() as c:
        c.execute("UPDATE updates SET created_at = now() - interval '100 days' WHERE id = ANY(%s)", (ids[:2],))
    assert h.prune_updates(30) == 2
    r = get(app, hd, since=1)
    assert r.status_code == 410 and r.json == {"error": "cursor too old or reset"}
    assert get(app, hd, since=ids[1]).status_code == 200


def test_since_newer_than_max_returns_410(env):
    app, h, _, hd = env
    ids = add(h, 2)
    assert get(app, hd, since=ids[-1] + 1).status_code == 410
    assert get(app, hd, since=ids[-1]).status_code == 200


def test_empty_table_since_zero_ok(env):
    app, _, _, hd = env
    r = get(app, hd, since=0)
    assert r.status_code == 200 and r.json == {"items": [], "next_cursor": 0, "has_more": False}


def test_latest_returns_max_id_zero_when_empty(env):
    app, h, _, hd = env
    c = app.test_client()
    assert c.get("/api/v1/changes/latest", headers=hd).json == {"cursor": 0}
    ids = add(h, 3)
    assert c.get("/api/v1/changes/latest", headers=hd).json == {"cursor": ids[-1]}


def test_mature_category_marked_sensitive(db, tmp_path, monkeypatch):
    app, ctx = build_app(db, tmp_path, monkeypatch=monkeypatch)
    hd = bearer(DeviceStore(db).create("Pixel 8", "admin")[1])
    ctx.cfg.web_mature_categories = ["Adult"]
    h = History(db)
    h.add_update(title="A", category="adult", is_new=True, chapters=["1"])
    h.add_update(title="B", category="Manga", is_new=True, chapters=["1"])
    items = get(app, hd).json["items"]
    assert [i["sensitive"] for i in items] == [True, False]


def test_api_updates_marks_mature_category_sensitive(db, tmp_path, monkeypatch):
    # The app's feed is also filled from /api/updates, so it needs the same flag to blur mature covers (spec 7.5).
    app, ctx = build_app(db, tmp_path, monkeypatch=monkeypatch)
    hd = bearer(DeviceStore(db).create("Pixel 8", "admin")[1])
    ctx.cfg.web_mature_categories = ["Adult"]
    h = History(db)
    h.add_update(title="A", category="adult", is_new=True, chapters=["1"])
    h.add_update(title="B", category="Manga", is_new=True, chapters=["1"])
    items = app.test_client().get("/api/updates", headers=hd).json["items"]
    assert sorted((i["title"], i["sensitive"]) for i in items) == [("A", True), ("B", False)]


@pytest.mark.parametrize("limit,expected", [(0, 1), (-5, 1), (500, 200), (3, 3)])
def test_limit_is_clamped_to_1_200(env, limit, expected):
    app, h, _, hd = env
    add(h, 5)
    items = get(app, hd, limit=limit).json["items"]
    assert len(items) == min(expected, 5)


def test_limit_upper_bound_is_200(env):
    app, h, _, hd = env
    add(h, 205)
    r = get(app, hd, limit=9999).json
    assert len(r["items"]) == 200 and r["has_more"] is True


@pytest.mark.parametrize("since", ["abc", "-1", "1.5"])
def test_bad_since_is_400(env, since):
    app, _, _, hd = env
    assert get(app, hd, since=since).status_code == 400


def test_requires_auth_when_enabled(env):
    app, _, _, _ = env
    c = app.test_client()
    assert c.get("/api/v1/changes").status_code == 401
    assert c.get("/api/v1/changes/latest").status_code == 401


def test_cookie_session_works(env):
    app, h, _, _ = env
    add(h, 1)
    c = login(app.test_client())
    assert c.get("/api/v1/changes").status_code == 200
    assert c.get("/api/v1/changes/latest").status_code == 200


def test_works_with_auth_disabled(db, tmp_path, monkeypatch):
    app, _ = build_app(db, tmp_path, auth=False, monkeypatch=monkeypatch)
    h = History(db)
    ids = add(h, 2)
    c = app.test_client()
    assert [i["id"] for i in c.get("/api/v1/changes").json["items"]] == ids
    assert c.get("/api/v1/changes/latest").json == {"cursor": ids[-1]}


@pytest.mark.parametrize("mature,blur,category,expected", [
    ([], ["Hentai"], "hentai", True),        # blur-only category hides covers, so it is sensitive (spec 7.5)
    (["Adult"], [], "Adult", True),          # mature-only stays sensitive
    (["Adult"], ["Hentai"], "Manga", False),  # neither
])
def test_sensitive_uses_mature_and_blur_categories_on_both_endpoints(db, tmp_path, monkeypatch,
                                                                      mature, blur, category, expected):
    app, ctx = build_app(db, tmp_path, monkeypatch=monkeypatch)
    hd = bearer(DeviceStore(db).create("Pixel 8", "admin")[1])
    ctx.cfg.web_mature_categories = mature
    ctx.cfg.web_blur_categories = blur
    h = History(db)
    h.add_update(title="A", category=category, is_new=True, chapters=["1"])
    v1 = get(app, hd).json["items"]
    old = app.test_client().get("/api/updates", headers=hd).json["items"]
    assert [i["sensitive"] for i in v1] == [expected]
    assert [i["sensitive"] for i in old] == [expected]


def test_is_sensitive_category_is_none_safe_and_case_insensitive():
    from manga_manager.config import Config
    from manga_manager.web import is_sensitive_category
    cfg = Config(web_mature_categories=["Adult"], web_blur_categories=["HENTAI"])
    assert is_sensitive_category(cfg, None) is False
    assert is_sensitive_category(cfg, "") is False
    assert is_sensitive_category(cfg, "adult") is True
    assert is_sensitive_category(cfg, "Hentai") is True
    assert is_sensitive_category(cfg, "Manga") is False


def test_cursor_survives_a_fully_pruned_table(env):
    # After web.keep_days without updates pruning empties the table; the id sequence keeps counting, so the
    # cursor must too (otherwise since=C gets 410, the app re-baselines to 0, and the next item C+1 is lost).
    app, h, db, hd = env
    ids = add(h, 3)
    high = ids[-1]
    with db.connect() as c:
        c.execute("UPDATE updates SET created_at = now() - interval '100 days'")
    assert h.prune_updates(14) == 3
    c = app.test_client()
    assert c.get("/api/v1/changes/latest", headers=hd).json == {"cursor": high}
    r = get(app, hd, since=high)
    assert r.status_code == 200 and r.json == {"items": [], "next_cursor": high, "has_more": False}
    assert get(app, {**hd, "If-None-Match": r.headers["ETag"]}, since=high).status_code == 304
    assert get(app, hd, since=high + 1).status_code == 410
    new = add(h, 1)[0]
    assert new == high + 1
    r = get(app, hd, since=high)
    assert r.status_code == 200 and [i["id"] for i in r.json["items"]] == [new]


def test_restart_identity_still_gives_410(env):
    app, h, db, hd = env
    ids = add(h, 3)
    with db.connect() as c:
        c.execute("TRUNCATE updates RESTART IDENTITY")
    assert get(app, hd, since=ids[-1]).status_code == 410
    assert app.test_client().get("/api/v1/changes/latest", headers=hd).json == {"cursor": 0}


def test_max_update_id_uses_the_sequence_high_water_mark(env):
    _, h, db, _ = env
    assert h.max_update_id() == 0
    ids = add(h, 2)
    with db.connect() as c:
        c.execute("DELETE FROM updates")
    assert h.max_update_id() == ids[-1]


def test_cursor_behind_a_fully_pruned_table_gets_410_before_the_next_insert(env):
    # Rows the device never saw were pruned: it must re-baseline NOW (to the high-water mark), not only after
    # the next insert, or that next update would be skipped by the late re-baseline.
    app, h, db, hd = env
    ids = add(h, 3)
    with db.connect() as c:
        c.execute("UPDATE updates SET created_at = now() - interval '100 days'")
    h.prune_updates(14)
    assert get(app, hd, since=ids[0]).status_code == 410
    new = add(h, 1)[0]
    assert [i["id"] for i in get(app, hd, since=ids[-1]).json["items"]] == [new]
