from conftest import shared_db
"""Tests for 2.5.0: #12 aliases on the dashboard, #13 a folder of frames, #14 cleaning up old updates."""
import json
import re
from datetime import datetime, timedelta, timezone

import pytest

from manga_manager.auth import hash_password
from manga_manager.config import ConfigError, load_config
from manga_manager.covers import CoverStore
from manga_manager.frames import FrameStore
from manga_manager.history import History
from manga_manager.kavita import KavitaSeries
from manga_manager.suwayomi import SuwayomiManga
from manga_manager.web import create_app

from test_pipeline import FakeKavita, FakeSuwayomi, build, env, make_cbz  # noqa: F401
from test_v22 import png, write_cfg

PW = "correct horse battery"


def app_client(cfg, history=None, titles=None):
    history = history or History(shared_db())
    app = create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None,
                     titles=titles)
    return app.test_client(), history


def frames_on_page(html: str) -> dict:
    return json.loads(re.search(r"const FRAMES = (.*?);\n", html).group(1))


def age(history, update_id, days):
    when = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat(timespec="seconds")
    with history.db.connect() as c:
        c.execute("UPDATE updates SET created_at=%s WHERE id=%s", (when, update_id))


# ================================================================ #14 cleanup
def test_updates_older_than_keep_days_are_deleted(tmp_path):
    h = History(shared_db())
    old = h.add_update(title="Old", category="Regular", is_new=False, chapters=["1"])
    edge = h.add_update(title="Edge", category="Regular", is_new=False, chapters=["2"])
    h.add_update(title="Fresh", category="Regular", is_new=True, chapters=["3"])
    age(h, old, 15)
    age(h, edge, 13)
    assert h.prune_updates(14) == 1
    titles = [i["title"] for i in h.list_updates()[0]]
    assert titles == ["Fresh", "Edge"]
    assert h.stats()["chapters"] == 2                 # the totals now cover what is kept


def test_keep_days_zero_keeps_everything(tmp_path):
    h = History(shared_db())
    age(h, h.add_update(title="Ancient", category="Regular", is_new=False, chapters=["1"]), 400)
    assert h.prune_updates(0) == 0 and h.list_updates()[1] == 1


def test_keep_days_config(tmp_path):
    assert load_config(write_cfg(tmp_path)).web_keep_days == 14
    assert load_config(write_cfg(tmp_path, "  keep_days: 30")).web_keep_days == 30
    assert load_config(write_cfg(tmp_path, "  keep_days: 0")).web_keep_days == 0
    with pytest.raises(ConfigError):
        load_config(write_cfg(tmp_path, "  keep_days: -1"))


def test_run_cleans_up_but_dry_run_does_not(env):
    cfg, *_ = env
    pipe, history, _ = build(cfg, FakeSuwayomi([]), FakeKavita([]))
    age(history, history.add_update(title="Old", category="Regular", is_new=False, chapters=["1"]), 20)
    cfg.dry_run = True
    pipe.run()
    assert history.list_updates()[1] == 1
    cfg.dry_run = False
    pipe.run()
    assert history.list_updates()[1] == 0


def test_summary_mentions_the_period(env):
    cfg, *_ = env
    html = app_client(cfg)[0].get("/").get_data(as_text=True)
    assert "const KEEP_DAYS = 14;" in html and "in the last" in html


# ================================================================ #13 frames
def test_folder_of_frames_all_offered(tmp_path):
    d = tmp_path / "frames"
    for name in ("b.png", "a.png", "c.webp"):
        png(d / "mature" / name)
    (d / "mature" / "notes.txt").write_text("not a frame")
    png(d / "mature" / "frame-guide.png")            # the guide is never a frame
    png(d / "mature.png")                             # the 2.2.0 file counts as one more
    store = FrameStore(d, ["Adult", "Hentai"])
    names = [f.path.name for f in store.frames_for("Adult")]
    assert names == ["mature.png", "a.png", "b.png", "c.webp"]
    assert store.frames_for("Regular") == []


def test_category_folder_wins_over_mature(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature" / "one.png")
    png(d / "hentai" / "h1.png")
    png(d / "hentai" / "h2.png")
    store = FrameStore(d, ["Adult", "Hentai"])
    assert [f.path.name for f in store.frames_for("Hentai")] == ["h1.png", "h2.png"]
    assert [f.path.name for f in store.frames_for("Adult")] == ["one.png"]


def test_empty_folder_falls_back_to_builtin(tmp_path):
    d = tmp_path / "frames"
    (d / "mature").mkdir(parents=True)
    store = FrameStore(d, ["Adult"])
    assert [f.path for f in store.frames_for("Adult")] == [store.builtin]


def test_unusable_frame_skipped_others_kept(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature" / "good.png")
    (d / "mature" / "broken.png").write_bytes(b"not an image")
    assert [f.path.name for f in FrameStore(d, ["Adult"]).frames_for("Adult")] == ["good.png"]


def test_install_defaults_on_a_2_2_folder_adds_mature_folder_only(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature.png")
    FrameStore(d, ["Adult"]).install_defaults()
    assert (d / "mature").is_dir() and not any((d / "mature").iterdir())
    assert (d / "mature.png").is_file()


def test_dashboard_gets_every_frame_and_serves_each(env):
    cfg, _, tmp = env
    cfg.config_dir = tmp / "config"
    png(cfg.config_dir / "frames" / "mature" / "a.png")
    png(cfg.config_dir / "frames" / "mature" / "b.png", (1000, 1400))
    cfg.frame_window = None
    c, _ = app_client(cfg)
    html = c.get("/").get_data(as_text=True)
    frames = frames_on_page(html)
    assert [f["url"].split("?")[0] for f in frames["adult"]] == ["frame/adult/0", "frame/adult/1"]
    assert [f["span"] for f in frames["adult"]] == [2, 1]
    assert c.get("/frame/adult/1").data == (cfg.config_dir / "frames" / "mature" / "b.png").read_bytes()
    assert c.get("/frame/adult").data == (cfg.config_dir / "frames" / "mature" / "a.png").read_bytes()
    assert c.get("/frame/adult/2").status_code == 404
    assert "Math.random()" in html                    # a new pick on every page load (user's choice)


# ================================================================ #12 aliases
def test_alias_store_add_edit_rename_delete(tmp_path):
    h = History(shared_db())
    h.set_alias("Ore dake Level Up na Ken", "Solo Leveling")
    h.set_alias("ore dake level up na ken", "Solo Leveling (Official)")   # same title, any case: updated
    assert h.aliases() == {"ore dake level up na ken": "Solo Leveling (Official)"}
    h.set_alias("Na Honjaman Level Up", "Solo Leveling", previous="ore dake level up na ken")
    assert h.aliases() == {"Na Honjaman Level Up": "Solo Leveling"}
    assert h.delete_alias("na honjaman level up") and h.aliases() == {}
    assert not h.delete_alias("nothing")


def test_dashboard_alias_wins_over_config(env):
    cfg, *_ = env
    cfg.aliases = {"Suwa A": "Kavita Config"}
    cfg.dashboard_aliases = {"suwa a": "Kavita Dashboard", "Suwa B": "Kavita B"}
    assert cfg.kavita_name_for("Suwa A") == "Kavita Dashboard"
    assert cfg.suwayomi_title_for("kavita b") == "Suwa B"
    assert cfg.suwayomi_title_for("Kavita Config") is None   # replaced by the dashboard alias


def test_alias_added_on_dashboard_is_used_by_the_next_run(env):
    """The #12 scenario: no alias in config.yml, one added on the dashboard, the next run uses it."""
    cfg, libs, _ = env
    (libs["Regular"] / "Solo Leveling").mkdir()
    make_cbz(cfg.downloads / "Src" / "Ore dake Level Up na Ken" / "1.cbz", "Ore dake Level Up na Ken", "1")
    pipe, history, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Ore dake Level Up na Ken", ["Regular"])]),
                                    FakeKavita([KavitaSeries(9, "Solo Leveling", 1, True, "/m/Solo Leveling")]))
    c, _ = app_client(cfg, history)
    r = c.post("/api/aliases", json={"suwayomi_title": " Ore dake  Level Up na Ken ", "kavita_name": "Solo Leveling"})
    assert r.status_code == 200 and r.json["items"][0]["suwayomi_title"] == "Ore dake Level Up na Ken"
    cfg.dashboard_aliases = {}                        # the run reads the database itself
    result = pipe.run()
    assert result.processed[0].kavita_series.id == 9
    assert (libs["Regular"] / "Solo Leveling" / "1.cbz").exists()
    assert history.list_updates()[0][0]["title"] == "Solo Leveling"


def test_alias_api_lists_config_and_dashboard(env):
    cfg, *_ = env
    cfg.aliases = {"Suwa A": "Kavita A", "Suwa B": "Kavita B"}
    c, history = app_client(cfg)
    history.set_alias("Suwa A", "Kavita A2")
    items = c.get("/api/aliases").json["items"]
    assert [(i["suwayomi_title"], i["source"], i.get("overridden")) for i in items] == [
        ("Suwa A", "dashboard", None), ("Suwa A", "config", True), ("Suwa B", "config", False)]


def test_alias_validation(env):
    cfg, *_ = env
    c, _ = app_client(cfg)
    assert c.post("/api/aliases", json={"suwayomi_title": "x", "kavita_name": ""}).status_code == 400
    assert c.post("/api/aliases", json={"suwayomi_title": "Same", "kavita_name": "same"}).status_code == 400
    assert c.post("/api/aliases", json={"suwayomi_title": "x" * 301, "kavita_name": "y"}).status_code == 400
    assert c.post("/api/aliases/delete", json={"suwayomi_title": "nope"}).status_code == 404


def test_alias_cross_site_post_refused(env):
    cfg, *_ = env
    c, _ = app_client(cfg)
    r = c.post("/api/aliases", json={"suwayomi_title": "a", "kavita_name": "b"},
               headers={"Origin": "https://evil.example"})
    assert r.status_code == 403


def test_only_config_accounts_can_edit(env):
    cfg, *_ = env
    cfg.auth_enabled, cfg.auth_allow_signup = True, True
    cfg.auth_users = {"Maarten": hash_password(PW)}
    c, history = app_client(cfg)
    assert c.get("/api/aliases").status_code == 401   # login required to even look

    # an account from the sign-up page can look but not change
    c.post("/signup", data={"username": "guest", "password": PW, "confirm": PW})
    assert c.get("/api/aliases").json["can_edit"] is False
    assert c.post("/api/aliases", json={"suwayomi_title": "a", "kavita_name": "b"}).status_code == 403
    history.set_alias("a", "b")
    assert c.post("/api/aliases/delete", json={"suwayomi_title": "a"}).status_code == 403
    c.post("/logout")

    # the config.yml account can (any case of the username)
    c.post("/login", data={"username": "maarten", "password": PW})
    assert c.get("/api/aliases").json["can_edit"] is True
    assert c.post("/api/aliases", json={"suwayomi_title": "c", "kavita_name": "d"}).status_code == 200
    assert history.aliases() == {"a": "b", "c": "d"}


def test_suggestions_cached_and_failure_tolerated(env):
    cfg, *_ = env
    calls = []

    def titles():
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("Kavita down")
        return {"suwayomi": ["b", "a", "a"], "kavita": ["Z"]}

    c, _ = app_client(cfg, titles=titles)
    first = c.get("/api/aliases/suggestions").json
    assert first["suwayomi"] == [] and "Kavita down" in first["error"]
    assert c.get("/api/aliases/suggestions").json == {"suwayomi": ["a", "b"], "kavita": ["Z"]}
    c.get("/api/aliases/suggestions")
    assert len(calls) == 2                            # kept for a while, not asked on every open


def test_search_finds_by_dashboard_alias(env):
    cfg, *_ = env
    c, history = app_client(cfg)
    history.add_update(title="Ore dake Level Up na Ken", category="Regular", is_new=False, chapters=["1"])
    c.post("/api/aliases", json={"suwayomi_title": "Ore dake Level Up na Ken", "kavita_name": "Solo Leveling"})
    items = c.get("/api/updates?q=solo").json["items"]
    assert [i["title"] for i in items] == ["Solo Leveling"]
