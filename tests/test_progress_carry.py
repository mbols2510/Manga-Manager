"""Read progress is carried over when an official chapter replaces one (#6)."""
from conftest import shared_db
from manga_manager import progress
from manga_manager.covers import CoverStore
from manga_manager.history import History
from manga_manager.kavita import KavitaSeries
from manga_manager.pipeline import Pipeline
from manga_manager.suwayomi import SuwayomiManga

from test_pipeline import FakeKavita, FakeNotifier, FakeSuwayomi, env, make_cbz  # noqa: F401


class ProgressKavita(FakeKavita):
    """Kavita fake with one old chapter that is replaced after the scan."""

    def __init__(self, series, old_name, old_pages, old_read, new_name, new_pages, fail=False):
        super().__init__(series)
        self.chapters = [{"id": 11, "volumeId": 3, "pages": old_pages, "pagesRead": old_read,
                          "files": [{"filePath": "/manga/Series/" + old_name}]}]
        self.new = {"id": 12, "volumeId": 3, "pages": new_pages, "pagesRead": 0,
                    "files": [{"filePath": "/manga/Series/" + new_name}]}
        self.scanned = False
        self.writes = []
        self.fail = fail

    def scan_series(self, library_id, series_id):
        super().scan_series(library_id, series_id)
        self.scanned = True

    def series_chapters(self, series_id):
        if self.fail:
            raise RuntimeError("kavita down")
        return [self.new] if self.scanned else list(self.chapters)

    def set_chapter_progress(self, library_id, series_id, volume_id, chapter_id, page):
        self.writes.append(("progress", chapter_id, page))

    def mark_chapter_read(self, series_id, chapter_id):
        self.writes.append(("read", chapter_id))


def run(env, old_pages, old_read, new_pages, fail=False):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(series_dir / "Old Chapter 5.cbz", "Series", "5")
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    kavita = ProgressKavita([KavitaSeries(7, "Series", 1, False, str(series_dir))],
                            "Old Chapter 5.cbz", old_pages, old_read, "Chapter 5.cbz", new_pages, fail)
    suwa = FakeSuwayomi([SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")])
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwa, None, False)
    pipe = Pipeline(cfg, suwa, kavita, covers, History(shared_db()), FakeNotifier(), sleep=lambda s: None)
    pipe.run()
    return kavita


def test_fully_read_stays_fully_read(env):
    assert run(env, 20, 20, 24).writes == [("read", 12)]


def test_partial_progress_is_scaled_to_new_page_count(env):
    assert run(env, 20, 10, 30).writes == [("progress", 12, 15)]


def test_unread_is_untouched(env):
    assert run(env, 20, 0, 24).writes == []


def test_kavita_error_does_not_break_the_replace(env):
    kavita = run(env, 20, 20, 24, fail=True)
    _, libs, _ = env
    assert [f.name for f in (libs["Regular"] / "Series").iterdir()] == ["Chapter 5.cbz"]
    assert kavita.writes == []


def test_target_page_rules():
    assert progress.target_page(20, 20, 24) is None
    assert progress.target_page(10, 20, 30) == 15
    assert progress.target_page(19, 20, 10) == 9          # clamped below the last page
    assert progress.target_page(1, 100, 10) == 1          # never rounds down to unread
    assert progress.target_page(5, 0, 3) == 2             # unknown old size: clamp


def test_restore_gives_up_quietly_when_chapter_never_appears():
    class K:
        def series_chapters(self, sid):
            return []
    c = progress.Carry(1, 7, "x.cbz", 5, 10)
    assert progress.restore(K(), [c], lambda s: None, attempts=3) == 0


def test_pipeline_wiring_without_database(tmp_path, monkeypatch):
    """_apply_plan snapshots progress before trashing; _restore_progress writes it after the scan."""
    from pathlib import Path
    from types import SimpleNamespace
    from manga_manager import preferred

    old = tmp_path / "Old Chapter 5.cbz"
    old.write_bytes(b"x")
    kav = ProgressKavita([], "Old Chapter 5.cbz", 20, 20, "Chapter 5.cbz", 24)
    monkeypatch.setattr(preferred, "to_trash", lambda *a, **k: old.unlink())
    pipe = Pipeline(SimpleNamespace(dry_run=False, data_dir=tmp_path), None, kav, None,
                    SimpleNamespace(db=None, forget_sources=lambda *a: None), None, sleep=lambda s: None)
    plan = preferred.Plan(replace=[(Path("Chapter 5.cbz"), old, "5")])
    lib = SimpleNamespace(kavita_library_id=1)
    pipe._apply_plan(plan, [Path("Chapter 5.cbz")], KavitaSeries(7, "Series", 1, False), lib, tmp_path, "Series")
    assert len(pipe._carry) == 1
    kav.scanned = True
    pipe._restore_progress()
    assert kav.writes == [("read", 12)] and pipe._carry == []
