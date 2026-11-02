"""Tests for 2.3.0: #8 move a manga only once it's fully downloaded, #9 runs never overlap."""
import os
import subprocess
import sys
import textwrap
import threading
import time

from manga_manager.comicinfo import normalize_title
from manga_manager.config import load_config
from manga_manager.scheduler import ALREADY_RUNNING, RunLock, Scheduler
from manga_manager.suwayomi import ActiveDownloads, SuwayomiClient, SuwayomiManga

from test_pipeline import FakeKavita, FakeSuwayomi, build, env, make_cbz  # noqa: F401


class QueueSuwayomi(FakeSuwayomi):
    """FakeSuwayomi that also answers the download-queue question."""

    def __init__(self, mangas, active=None, fail=False):
        super().__init__(mangas)
        self.active = active or ActiveDownloads()
        self.fail = fail

    def active_downloads(self):
        if self.fail:
            raise RuntimeError("Cannot query field 'downloadStatus'")
        return self.active


def _backdate(path, seconds=3600):
    past = time.time() - seconds
    os.utime(path, (past, past))


KNOWN = [SuwayomiManga(7, "Delinquent Girl", ["Regular"])]


# ------------------------------------------------------------------ #8: folder timer
def test_manga_waits_while_newest_chapter_is_fresh(env):
    cfg, libs, _ = env
    folder = cfg.downloads / "Src" / "Delinquent Girl"
    old = make_cbz(folder / "Ch 1.cbz", "Delinquent Girl", "1")
    fresh = make_cbz(folder / "Ch 2.cbz", "Delinquent Girl", "2", old=False)
    pipe, history, notifier = build(cfg, QueueSuwayomi(KNOWN), FakeKavita([]))

    result = pipe.run()

    assert result.processed == [] and notifier.sent == []
    assert old.exists() and fresh.exists(), "nothing may move while the manga is still downloading"
    assert result.waiting == ["Delinquent Girl"]
    assert "1 still downloading" in result.summary()


def test_whole_manga_moves_in_one_go_once_quiet(env):
    cfg, libs, _ = env
    folder = cfg.downloads / "Src" / "Delinquent Girl"
    for n in range(1, 6):
        make_cbz(folder / f"Ch {n}.cbz", "Delinquent Girl", str(n))
    _backdate(folder)
    pipe, history, notifier = build(cfg, QueueSuwayomi(KNOWN), FakeKavita([]))

    result = pipe.run()

    assert len(result.processed) == 1 and result.waiting == []
    assert len(result.processed[0].chapters) == 5
    assert len(notifier.sent) == 1, "one notification for the whole download"
    assert len(list((libs["Regular"] / "Delinquent Girl").glob("*.cbz"))) == 5


def test_half_written_chapter_in_a_subfolder_counts_as_activity(env):
    cfg, libs, _ = env
    folder = cfg.downloads / "Src" / "Delinquent Girl"
    done = make_cbz(folder / "Ch 1.cbz", "Delinquent Girl", "1")
    pages = folder / "Ch 2_tmp"
    pages.mkdir()
    (pages / "001.jpg").write_bytes(b"page")          # Suwayomi still fetching pages
    _backdate(folder)
    _backdate(pages)                                   # only the page itself is fresh
    pipe, _, _ = build(cfg, QueueSuwayomi(KNOWN), FakeKavita([]))

    result = pipe.run()

    assert result.processed == [] and done.exists()
    assert result.waiting == ["Delinquent Girl"]


def test_old_per_chapter_behaviour_still_available(env):
    cfg, libs, _ = env
    cfg.wait_for_whole_manga = False
    folder = cfg.downloads / "Src" / "Delinquent Girl"
    old = make_cbz(folder / "Ch 1.cbz", "Delinquent Girl", "1")
    fresh = make_cbz(folder / "Ch 2.cbz", "Delinquent Girl", "2", old=False)
    suwa = QueueSuwayomi(KNOWN, fail=True)             # the queue isn't even asked in this mode
    pipe, _, _ = build(cfg, suwa, FakeKavita([]))

    result = pipe.run()

    assert len(result.processed) == 1 and result.processed[0].chapters == ["1"]
    assert not old.exists() and fresh.exists()


# ------------------------------------------------------------------ #8: download queue
def _quiet_manga(cfg, chapters=2):
    folder = cfg.downloads / "Src" / "Delinquent Girl"
    for n in range(1, chapters + 1):
        make_cbz(folder / f"Ch {n}.cbz", "Delinquent Girl", str(n))
    _backdate(folder)
    return folder


def test_manga_waits_while_suwayomi_still_has_chapters_queued(env):
    cfg, _, _ = env
    folder = _quiet_manga(cfg)
    active = ActiveDownloads(ids={7}, titles=set(), chapters={7: 6})
    pipe, _, notifier = build(cfg, QueueSuwayomi(KNOWN, active), FakeKavita([]))

    result = pipe.run()

    assert result.processed == [] and notifier.sent == []
    assert len(list(folder.glob("*.cbz"))) == 2
    assert result.waiting == ["Delinquent Girl"]


def test_queue_match_by_title_when_id_missing(env):
    cfg, _, _ = env
    _quiet_manga(cfg)
    key = normalize_title("Delinquent Girl")
    active = ActiveDownloads(titles={key}, chapters={key: 1})
    pipe, _, _ = build(cfg, QueueSuwayomi(KNOWN, active), FakeKavita([]))

    assert pipe.run().processed == []


def test_other_manga_in_queue_does_not_block(env):
    cfg, _, _ = env
    _quiet_manga(cfg)
    active = ActiveDownloads(ids={99}, chapters={99: 3})
    pipe, _, _ = build(cfg, QueueSuwayomi(KNOWN, active), FakeKavita([]))

    assert len(pipe.run().processed) == 1


def test_unreadable_queue_falls_back_to_folder_timer(env):
    cfg, _, _ = env
    _quiet_manga(cfg)
    pipe, _, _ = build(cfg, QueueSuwayomi(KNOWN, fail=True), FakeKavita([]))

    result = pipe.run()

    assert len(result.processed) == 1 and result.errors == []


def test_queue_check_can_be_switched_off(env):
    cfg, _, _ = env
    cfg.check_download_queue = False
    _quiet_manga(cfg)
    pipe, _, _ = build(cfg, QueueSuwayomi(KNOWN, ActiveDownloads(ids={7}, chapters={7: 1})), FakeKavita([]))

    assert len(pipe.run().processed) == 1


def test_active_downloads_parses_suwayomi_answer(monkeypatch):
    """The reply shape from the issue: QUEUED/DOWNLOADING count, ERROR and FINISHED don't."""
    title = "I Possessed the Body of a Delinquent Girl"
    queue = ([{"state": "DOWNLOADING", "manga": {"id": 7, "title": title}}]
             + [{"state": "QUEUED", "manga": {"id": 7, "title": title}}] * 6
             + [{"state": "ERROR", "manga": {"id": 8, "title": "Broken"}},
                {"state": "FINISHED", "manga": {"id": 9, "title": "Done"}},
                {"state": "QUEUED", "manga": {"title": "No Id Given"}}])
    client = SuwayomiClient("http://suwa")
    monkeypatch.setattr(client, "graphql", lambda q, v=None: {"downloadStatus": {"queue": queue}})

    active = client.active_downloads()

    assert active.ids == {7}
    assert active.contains(SuwayomiManga(7, title))
    assert active.left(SuwayomiManga(7, title)) == 7
    assert active.contains(SuwayomiManga(123, "No Id Given"))
    assert not active.contains(SuwayomiManga(8, "Broken"))
    assert not active.contains(SuwayomiManga(9, "Done"))


def test_new_settings_in_config(tmp_path):
    cfg_file = tmp_path / "config.yml"
    cfg_file.write_text(textwrap.dedent("""
        paths: {wait_for_whole_manga: false}
        suwayomi: {url: http://s, check_download_queue: false}
        kavita: {url: http://k, api_key: x}
        libraries: [{category: Regular, kavita_library_id: 1, path: /m}]
    """))
    cfg = load_config(str(cfg_file))
    assert cfg.wait_for_whole_manga is False and cfg.check_download_queue is False

    cfg_file.write_text(cfg_file.read_text().replace("wait_for_whole_manga: false", "settle_seconds: 120")
                        .replace(", check_download_queue: false", ""))
    cfg = load_config(str(cfg_file))
    assert cfg.wait_for_whole_manga is True and cfg.check_download_queue is True


# ------------------------------------------------------------------ #9: no overlapping runs
class _History:
    def start_run(self):
        return 1

    def finish_run(self, *a):
        pass


class _Result:
    processed, covers_set, errors = [], 0, []

    def summary(self):
        return "ok"


def test_scheduled_run_waits_for_a_long_run(monkeypatch):
    events, t0 = [], time.monotonic()

    class SlowPipeline:
        def run(self):
            events.append(("start", time.monotonic() - t0))
            time.sleep(0.6)                          # a run longer than the interval
            events.append(("end", time.monotonic() - t0))
            return _Result()

    sched = Scheduler(SlowPipeline, _History(), 1)
    sched.interval = 0.2
    sched.start()
    time.sleep(0.2)
    assert sched.trigger() is False, "Run now is refused while a run is busy"
    time.sleep(1.3)
    sched.stop()

    starts = [t for e, t in events if e == "start"]
    ends = [t for e, t in events if e == "end"]
    assert len(starts) >= 2
    assert starts[1] >= ends[0] + 0.15, "next run starts an interval after the previous one ended"


def test_run_lock_blocks_a_second_process(tmp_path):
    lock = tmp_path / "run.lock"
    holder = subprocess.Popen([sys.executable, "-c", textwrap.dedent(f"""
        import sys, time
        from manga_manager.scheduler import RunLock
        l = RunLock(__import__('pathlib').Path({str(lock)!r}))
        assert l.acquire()
        print('locked', flush=True)
        time.sleep(30)
    """)], stdout=subprocess.PIPE, text=True, cwd=os.getcwd(),
        env={**os.environ, "PYTHONPATH": os.getcwd()})
    try:
        assert holder.stdout.readline().strip() == "locked"
        ran = []

        class P:
            def run(self):
                ran.append(1)
                return _Result()

        assert Scheduler(P, _History(), 15, lock_path=lock).run_once() == ALREADY_RUNNING
        assert ran == [], "nothing is moved while another process holds the lock"
    finally:
        holder.kill()
        holder.wait()

    # released when the other process is gone
    assert Scheduler(lambda: type("P", (), {"run": lambda self: _Result()})(), _History(), 15,
                     lock_path=lock).run_once() == "ok"


def test_run_lock_released_after_a_failed_run(tmp_path):
    class Boom:
        def run(self):
            raise RuntimeError("Kavita down")

    sched = Scheduler(Boom, _History(), 15, lock_path=tmp_path / "run.lock")
    assert sched.run_once() == "Kavita down"
    assert RunLock(tmp_path / "run.lock").acquire(), "a failed run must not leave the lock held"
