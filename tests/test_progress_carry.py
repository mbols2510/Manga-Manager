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
    pipe = Pipeline(SimpleNamespace(dry_run=False, data_dir=tmp_path, scan_poll_interval=15, scan_wait_timeout=1800), None, kav, None,
                    SimpleNamespace(db=None, forget_sources=lambda *a: None), None, sleep=lambda s: None)
    plan = preferred.Plan(replace=[(Path("Chapter 5.cbz"), old, "5")])
    lib = SimpleNamespace(kavita_library_id=1)
    pipe._apply_plan(plan, [Path("Chapter 5.cbz")], KavitaSeries(7, "Series", 1, False), lib, tmp_path, "Series")
    assert len(pipe._carry) == 1
    kav.scanned = True
    pipe._restore_progress()
    assert kav.writes == [("read", 12)] and pipe._carry == []


# ---------------------------------------------------------------- 3.3.2: wait for the scan first
def test_wait_for_scans_polls_until_idle_then_returns():
    class K:
        def __init__(self):
            self.answers = [True, True, False]

        def is_scan_busy(self):
            return self.answers.pop(0)
    sleeps = []
    progress.wait_for_scans(K(), sleeps.append, poll_interval=15, timeout=100)
    assert sleeps == [15, 15]


def test_wait_for_scans_is_bounded_and_survives_errors():
    class Busy:
        def is_scan_busy(self):
            return True
    sleeps = []
    progress.wait_for_scans(Busy(), sleeps.append, poll_interval=40, timeout=100)
    assert sleeps == [40, 40, 20]

    class Broken:
        def is_scan_busy(self):
            raise RuntimeError("boom")
    sleeps.clear()
    progress.wait_for_scans(Broken(), sleeps.append)
    assert sleeps == []
    progress.wait_for_scans(object(), sleeps.append)       # no is_scan_busy at all
    assert sleeps == []


def test_restore_waits_for_scan_before_reading_chapters():
    events = []

    class K:
        busy = [True, False]

        def is_scan_busy(self):
            events.append("busy?")
            return self.busy.pop(0)

        def series_chapters(self, sid):
            events.append("chapters")
            return []
    c = progress.Carry(1, 7, "x.cbz", 5, 10)
    progress.restore(K(), [c], lambda s: events.append("sleep"), attempts=1)
    assert events == ["busy?", "sleep", "busy?", "chapters"]


def test_restore_without_carries_does_not_touch_kavita():
    class K:
        def is_scan_busy(self):
            raise AssertionError("not needed")
    assert progress.restore(K(), [], lambda s: None) == 0


# ---------------------------------------------------------------- same file name: overwritten in place
class OverwriteKavita(ProgressKavita):
    """The new file has the same name, so Kavita keeps the chapter and its old pagesRead after the rescan."""

    def __init__(self, series, old_pages, old_read, new_pages):
        super().__init__(series, "Chapter 5.cbz", old_pages, old_read, "Chapter 5.cbz", new_pages)
        self.new = {**self.chapters[0], "pages": new_pages}      # same id, new page count, stale pagesRead


def run_overwrite(env, old_pages, old_read, new_pages):
    cfg, libs, _ = env
    series_dir = libs["Regular"] / "Series"
    make_cbz(series_dir / "Chapter 5.cbz", "Series", "5")
    make_cbz(cfg.downloads / "Src (EN)" / "Series (Official)" / "Chapter 5.cbz", "Series (Official)", "5")
    kavita = OverwriteKavita([KavitaSeries(7, "Series", 1, False, str(series_dir))], old_pages, old_read, new_pages)
    suwa = FakeSuwayomi([SuwayomiManga(1, "Series (Official)", ["Regular"], "Src")])
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwa, None, False)
    Pipeline(cfg, suwa, kavita, covers, History(shared_db()), FakeNotifier(), sleep=lambda s: None).run()
    return kavita


def test_overwritten_fully_read_chapter_stays_fully_read(env):
    assert run_overwrite(env, 20, 20, 40).writes == [("read", 11)]       # not left at 20 of 40 pages


def test_overwritten_partial_progress_is_scaled(env):
    assert run_overwrite(env, 20, 10, 30).writes == [("progress", 11, 15)]


def test_overwrite_with_the_same_page_count_changes_nothing(env):
    assert run_overwrite(env, 1, 1, 1).writes == []


def test_restore_ignores_the_stale_entry_until_the_page_count_changes():
    stale = {"id": 11, "volumeId": 3, "pages": 20, "pagesRead": 20, "files": [{"filePath": "/m/Chapter 5.cbz"}]}
    fresh = {**stale, "pages": 40}

    class K:
        calls = 0
        writes = []

        def series_chapters(self, sid):
            self.calls += 1
            return [stale] if self.calls < 3 else [fresh]

        def mark_chapter_read(self, sid, cid):
            self.writes.append(("read", cid))

    k = K()
    c = progress.Carry(1, 7, "Chapter 5.cbz", 20, 20, overwrite=True)
    assert progress.restore(k, [c], lambda s: None) == 1 and k.writes == [("read", 11)] and k.calls == 3
