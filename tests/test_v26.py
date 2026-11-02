"""Tests for 2.6.0: #16 move chapters into the alias's series from the dashboard, #17 readable badge."""
import re

from manga_manager.kavita import KavitaSeries
from manga_manager.suwayomi import SuwayomiManga

from test_pipeline import FakeKavita, FakeSuwayomi, build, env, make_cbz  # noqa: F401


def scenario(env):
    """Suwayomi renamed the series: its chapters sit in their own folder next to Kavita's series."""
    cfg, libs, _ = env
    old, new = libs["Regular"] / "Solo Leveling", libs["Regular"] / "Ore dake Level Up na Ken"
    make_cbz(old / "1.cbz", "Solo Leveling", "1")
    make_cbz(new / "2.cbz", "Ore dake Level Up na Ken", "2")
    make_cbz(new / "3.cbz", "Ore dake Level Up na Ken", "3")
    kavita = FakeKavita([KavitaSeries(9, "Solo Leveling", 1, True, str(old))])
    pipe, history, _ = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Ore dake Level Up na Ken", ["Regular"])]), kavita)
    history.set_alias("Ore dake Level Up na Ken", "Solo Leveling")
    return cfg, pipe, history, kavita, old, new


def test_preview_changes_nothing(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    out = pipe.reprocess_alias("Ore dake Level Up na Ken")
    assert out["moves"] == [{"from": new.name, "to": "Solo Leveling", "files": 2, "replaced": 0}]
    assert not out["applied"] and (new / "2.cbz").exists() and not (old / "2.cbz").exists()
    assert kavita.calls == []


def test_apply_moves_files_rescans_and_cleans_up(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    uid = history.add_update(title="Ore dake Level Up na Ken", category="Regular", is_new=True, chapters=["2", "3"],
                             kavita_library_id=1, destination=str(new))
    out = pipe.reprocess_alias("Ore dake Level Up na Ken", apply=True)
    assert out["applied"] and "2 chapter" in out["message"]
    assert sorted(p.name for p in old.iterdir()) == ["1.cbz", "2.cbz", "3.cbz"]
    assert not new.exists()                                   # the emptied folder is gone
    assert ("scan_library", 1) in kavita.calls
    row = history.list_updates()[0][0]
    assert row["id"] == uid and row["title"] == "Solo Leveling" and row["destination"] == str(old)
    assert row["kavita_series_id"] == 9
    assert pipe.reprocess_alias("Ore dake Level Up na Ken", apply=True)["moves"] == []   # second time: nothing left


def test_cover_file_does_not_keep_the_old_folder(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    (new / f"{cfg.covers_file_name}.png").write_bytes(b"x")
    pipe.reprocess_alias("Ore dake Level Up na Ken", apply=True)
    assert not new.exists()


def test_no_series_in_kavita_yet(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    kavita.series.clear()
    out = pipe.reprocess_alias("Ore dake Level Up na Ken", apply=True)
    assert out["moves"] == [] and "isn't in Kavita" in out["message"] and (new / "2.cbz").exists()


def test_title_without_alias(env):
    cfg, pipe, *_ = scenario(env)
    assert "no alias" in pipe.reprocess_alias("Something else")["message"]


def test_api_preview_then_apply_and_permissions(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    from manga_manager.covers import CoverStore
    from manga_manager.web import create_app
    app = create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None,
                     reprocess=pipe.reprocess_alias)
    c = app.test_client()
    body = {"suwayomi_title": "Ore dake Level Up na Ken"}
    assert c.post("/api/aliases/reprocess", json=body).json["moves"][0]["files"] == 2
    assert (new / "2.cbz").exists()
    assert c.post("/api/aliases/reprocess", json={**body, "apply": True}).json["applied"]
    assert (old / "3.cbz").exists()
    assert c.post("/api/aliases/reprocess", json={}).status_code == 400


def test_api_refuses_while_a_run_is_busy(env):
    cfg, pipe, history, kavita, old, new = scenario(env)
    from manga_manager.covers import CoverStore
    from manga_manager.scheduler import Scheduler
    from manga_manager.web import create_app
    sched = Scheduler(lambda: pipe, history, 60, run_on_start=False)
    app = create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), sched,
                     reprocess=pipe.reprocess_alias)
    sched._run_lock.acquire()                                  # a run is in progress
    r = app.test_client().post("/api/aliases/reprocess", json={"suwayomi_title": "Ore dake Level Up na Ken", "apply": True})
    assert r.status_code == 409 and (new / "2.cbz").exists()


def test_pipeline_remembers_the_destination(env):
    cfg, libs, _ = env
    make_cbz(cfg.downloads / "Src" / "Foo" / "1.cbz", "Foo", "1")
    pipe, history, _ = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Foo", ["Regular"])]), FakeKavita([]))
    pipe.run()
    assert history.list_updates()[0][0]["destination"] == str(libs["Regular"] / "Foo")
