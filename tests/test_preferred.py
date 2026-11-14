"""Preferred sources: official / uncensored chapters replace the same chapter from a normal source."""
import zipfile
from pathlib import Path

from conftest import shared_db
from manga_manager.covers import CoverStore
from manga_manager.history import History
from manga_manager.kavita import KavitaSeries
from manga_manager.pipeline import Pipeline
from manga_manager.preferred import RANK_LABELS, detect_rank
from manga_manager.suwayomi import SuwayomiManga

from test_pipeline import FakeKavita, FakeNotifier, FakeSuwayomi, env, make_cbz  # noqa: F401


def pipeline(cfg, mangas, history):
    kavita = FakeKavita([KavitaSeries(7, "Series", 1, False, r"\\nas\Regular\Series")])
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, FakeSuwayomi(mangas), None, False)
    return Pipeline(cfg, FakeSuwayomi(mangas), kavita, covers, history, FakeNotifier(), sleep=lambda s: None), kavita


def add_pages(path: Path, n: int):
    with zipfile.ZipFile(path, "a") as zf:
        for i in range(n):
            zf.writestr(f"p{i:03}.jpg", b"x")


def test_detect_rank():
    assert detect_rank("Governess (Official)") == 1
    assert detect_rank("Governess [Uncensored]") == 2
    assert detect_rank("Governess (Official Uncensored)") == 3
    assert detect_rank("Unofficial Guide") == 0 and detect_rank("Plain title") == 0
    assert set(RANK_LABELS) == {0, 1, 2, 3}


def test_official_replaces_the_same_chapter_and_old_file_goes_to_trash(env):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(series_dir / "Old Group Chapter 5.cbz", "Series", "5")
    make_cbz(series_dir / "Old Group Chapter 4.cbz", "Series", "4")
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 6.cbz", "Series (Official)", "6")
    history = History(shared_db())
    pipe, kavita = pipeline(cfg, [SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")], history)

    result = pipe.run()

    names = sorted(f.name for f in series_dir.iterdir())
    assert names == ["Chapter 5.cbz", "Chapter 6.cbz", "Old Group Chapter 4.cbz"]
    row = history.list_updates()[0][0]
    assert row["replaced"] == ["5"] and row["duplicates"] == [] and sorted(row["chapters"]) == ["5", "6"]
    trash = history.db.kv_get("dc", "trash", {})
    assert len(trash) == 1
    entry = next(iter(trash.values()))
    assert entry["originalPath"].endswith("Old Group Chapter 5.cbz") and Path(entry["trashPath"]).exists()
    assert history.source_ranks(str(series_dir))["Chapter 5.cbz"] == 1
    assert ("scan_series", 1, 7) in kavita.calls


def test_normal_source_cannot_add_a_chapter_the_official_one_has(env):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    history = History(shared_db())
    pipe, _ = pipeline(cfg, [SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")], history)
    pipe.run()
    make_cbz(cfg.downloads / "Other (EN)" / "Series" / "Other Chapter 5.cbz", "Series", "5")
    make_cbz(cfg.downloads / "Other (EN)" / "Series" / "Other Chapter 6.cbz", "Series", "6")
    pipe2, _ = pipeline(cfg, [SuwayomiManga(2, "Series", ["Regular"], "Other")], history)
    pipe2.run()
    assert sorted(f.name for f in series_dir.iterdir()) == ["Chapter 5.cbz", "Other Chapter 6.cbz"]
    assert any("Other Chapter 5" in e["trashPath"] for e in history.db.kv_get("dc", "trash", {}).values())


def test_far_fewer_pages_keeps_both_files(env):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    old = make_cbz(series_dir / "Old Chapter 5.cbz", "Series", "5")
    add_pages(old, 20)
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    history = History(shared_db())
    pipe, _ = pipeline(cfg, [SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")], history)
    pipe.run()
    assert sorted(f.name for f in series_dir.iterdir()) == ["Chapter 5.cbz", "Old Chapter 5.cbz"]
    row = history.list_updates()[0][0]
    assert row["duplicates"] == ["5"] and row["replaced"] == []
    assert history.db.kv_get("dc", "trash", {}) == {}


def test_alias_source_type_marks_oddly_named_titles(env):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(series_dir / "Old Chapter 5.cbz", "Series", "5")
    make_cbz(cfg.downloads / "Src (EN)" / "Totally Different Name" / "Chapter 5.cbz", "Totally Different Name", "5")
    history = History(shared_db())
    history.set_alias("Totally Different Name", "Series", rank=1)
    cfg.src_enabled = True
    pipe, _ = pipeline(cfg, [SuwayomiManga(3, "Totally Different Name", ["Regular"], "Src")], history)
    pipe.run()
    assert sorted(f.name for f in series_dir.iterdir()) == ["Chapter 5.cbz"]


def test_preview_lists_what_would_be_replaced(env):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(series_dir / "Old Chapter 5.cbz", "Series", "5")
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    history = History(shared_db())
    pipe, _ = pipeline(cfg, [SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")], history)
    out = pipe.preview_preferred("Series (Official)")
    assert out["rank"] == 1 and out["folders"][0]["replace"] == 1 and out["folders"][0]["replace_chapters"] == ["5"]
    assert (series_dir / "Old Chapter 5.cbz").exists(), "a preview changes nothing"
    assert pipe.preview_preferred("Plain")["folders"] == []


def test_alias_api_stores_and_validates_source_type(env):
    from manga_manager.web import create_app
    cfg, *_ = env
    history = History(shared_db())
    app = create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    c = app.test_client()
    ok = c.post("/api/aliases", json={"suwayomi_title": "Odd Name", "kavita_name": "Series", "rank": 3})
    assert ok.status_code == 200
    item = next(i for i in ok.json["items"] if i["suwayomi_title"] == "Odd Name")
    assert item["rank"] == 3
    assert history.alias_ranks() == {"odd name": 3}
    bad = c.post("/api/aliases", json={"suwayomi_title": "X", "kavita_name": "Y", "rank": 9})
    assert bad.status_code == 400
    html = c.get("/").data.decode()
    assert 'id="alias-rank"' in html and 'role="combobox"' in html and "api/aliases/preferred-preview" in html


def test_move_chapters_into_the_alias_series_replaces_the_lower_ranked_chapter(env):
    """Setting an alias on an official title and moving its chapters into the series must replace the same
    chapter from the old source, not leave both for the Doubles tab."""
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Kavita Series"
    own_dir = libs["Regular"] / "Series (Official)"
    make_cbz(series_dir / "Old Group Chapter 5.cbz", "Kavita Series", "5")
    make_cbz(series_dir / "Old Group Chapter 4.cbz", "Kavita Series", "4")
    make_cbz(own_dir / "Chapter 5.cbz", "Series (Official)", "5")
    make_cbz(own_dir / "Chapter 6.cbz", "Series (Official)", "6")
    history = History(shared_db())
    history.set_alias("Series (Official)", "Kavita Series", rank=1)
    kavita = FakeKavita([KavitaSeries(7, "Kavita Series", 1, True, str(series_dir))])
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, FakeSuwayomi([]), None, False)
    pipe = Pipeline(cfg, FakeSuwayomi([SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")]), kavita,
                    covers, history, FakeNotifier(), sleep=lambda s: None)

    preview = pipe.reprocess_alias("Series (Official)")
    assert preview["moves"][0]["replaced"] == 1 and (series_dir / "Old Group Chapter 5.cbz").exists()

    out = pipe.reprocess_alias("Series (Official)", apply=True)

    assert out["applied"]
    assert sorted(f.name for f in series_dir.iterdir()) == ["Chapter 5.cbz", "Chapter 6.cbz", "Old Group Chapter 4.cbz"]
    entry = next(iter(history.db.kv_get("dc", "trash", {}).values()))
    assert entry["originalPath"].endswith("Old Group Chapter 5.cbz") and Path(entry["trashPath"]).exists()
    ranks = history.source_ranks(str(series_dir))
    assert ranks["Chapter 5.cbz"] == 1 and ranks["Chapter 6.cbz"] == 1
    assert ("scan_library", 1) in kavita.calls
