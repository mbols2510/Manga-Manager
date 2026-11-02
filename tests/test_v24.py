from conftest import shared_db
"""Tests for 2.4.0: #10 new / replaced / duplicate chapters, #11 pause between notifications."""

import pytest

from manga_manager.config import ConfigError, load_config
from manga_manager.history import History
from manga_manager.kavita import KavitaSeries
from manga_manager import notifier as notifier_mod
from manga_manager.notifier import Notifier
from manga_manager.suwayomi import SuwayomiManga

from test_pipeline import FakeKavita, FakeSuwayomi, build, env, make_cbz  # noqa: F401


# ---------------------------------------------------------------- #10 three chapter statuses
def _run_with_existing(env, existing: dict[str, str], incoming: dict[str, str]):
    cfg, libs, _ = env
    folder = libs["Regular"] / "Series"
    for name, number in existing.items():
        make_cbz(folder / name, "Series", number)
    for name, number in incoming.items():
        make_cbz(cfg.downloads / "Src" / "Series" / name, "Series", number)
    pipe, history, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Series", ["Regular"])]),
                                    FakeKavita([KavitaSeries(2, "Series", 1, True, "/m/Series")]))
    pipe.run()
    return folder, history, notifier


def test_same_name_is_replaced_other_name_is_duplicate(env):
    folder, history, notifier = _run_with_existing(
        env,
        existing={"Group A_Chapter 6.cbz": "6", "Group A_Chapter 7.cbz": "7"},
        incoming={"Group A_Chapter 5.cbz": "5", "Group B_Chapter 6.cbz": "6", "Group A_Chapter 7.cbz": "7"})
    sent = notifier.sent[0]
    assert sent["chapters"] == ["5", "6", "7"]
    assert sent["replaced"] == ["7"]
    assert sent["duplicates"] == ["6"]
    row = history.list_updates()[0][0]
    assert row["replaced"] == ["7"] and row["duplicates"] == ["6"]
    # Duplicate: both files are kept. Replaced: one file with that name.
    assert (folder / "Group A_Chapter 6.cbz").exists() and (folder / "Group B_Chapter 6.cbz").exists()
    assert sorted(p.name for p in folder.glob("*Chapter 7.cbz")) == ["Group A_Chapter 7.cbz"]


def test_duplicate_number_matches_across_formats(env):
    # "Chapter 007" in Kavita and number "7" in the new file are the same chapter.
    _, _, notifier = _run_with_existing(env, existing={"Old_Chapter 007.cbz": "7"},
                                        incoming={"New_Chapter 7.cbz": "7"})
    assert notifier.sent[0]["duplicates"] == ["7"] and notifier.sent[0]["replaced"] == []


def test_replaced_wins_when_a_chapter_is_both(env):
    # Two new files for chapter 8: one overwrites, one sits next to it -> shown once, as replaced.
    _, _, notifier = _run_with_existing(
        env, existing={"Group A_Chapter 8.cbz": "8"},
        incoming={"Group A_Chapter 8.cbz": "8", "Group B_Chapter 8.cbz": "8"})
    assert notifier.sent[0]["chapters"] == ["8"]
    assert notifier.sent[0]["replaced"] == ["8"] and notifier.sent[0]["duplicates"] == []


def test_new_series_has_no_duplicates(env):
    cfg, *_ = env
    make_cbz(cfg.downloads / "Src" / "Fresh" / "Chapter 1.cbz", "Fresh", "1")
    pipe, _, notifier = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Fresh", ["Regular"])]), FakeKavita([]))
    pipe.run()
    assert notifier.sent[0]["replaced"] == [] and notifier.sent[0]["duplicates"] == []


def test_chapter_text_has_three_tags():
    n = Notifier("http://ntfy", "t")
    assert n.chapter_text(["5", "6", "7"], ["7"], ["6"]) == (
        "\u25CF Chapter: 5 (New)\n\u25CF Chapter: 6 (Duplicate)\n\u25CF Chapter: 7 (Replaced)\n")
    n.chapter_status = False
    assert n.chapter_text(["6"], [], ["6"]) == "\u25CF Chapter: 6\n"


def test_notification_counts(monkeypatch):
    posted = []

    class Resp:
        ok, status_code, text = True, 200, ""

    monkeypatch.setattr(notifier_mod.requests, "post", lambda **kw: posted.append(kw) or Resp())
    Notifier("http://ntfy", "t", "MangaUpdates", delay=0).send(
        title="T", is_new=False, category="Regular", chapters=["5", "6", "7"], replaced=["7"], duplicates=["6"])
    data = posted[0]["json"]
    assert (data["NewChapterCount"], data["ReplacedChapterCount"], data["DuplicateChapterCount"]) == (1, 1, 1)
    assert "6 (Duplicate)" in data["Chapters"]




def test_dashboard_shows_duplicate_chips():
    from pathlib import Path
    html = (Path(__file__).parent.parent / "manga_manager" / "templates" / "index.html").read_text(encoding="utf-8")
    assert 'class="duplicate"' in html and "item.duplicates" in html


# ---------------------------------------------------------------- #11 pause between notifications
class Clock:
    def __init__(self):
        self.now = 1000.0
        self.slept = []

    def __call__(self):
        return self.now

    def sleep(self, s):
        self.slept.append(round(s, 3))
        self.now += s


def _notifier(clock, delay=10):
    return Notifier("http://ntfy", "t", "MangaUpdates", delay=delay, sleep=clock.sleep, clock=clock)


def _ok(monkeypatch):
    class Resp:
        ok, status_code, text = True, 200, ""
    monkeypatch.setattr(notifier_mod.requests, "post", lambda **kw: Resp())


def test_notifications_are_spaced_without_trailing_wait(monkeypatch):
    _ok(monkeypatch)
    clock = Clock()
    n = _notifier(clock)
    for t in ("A", "B", "C"):
        n.send(title=t, is_new=True, category="Regular", chapters=["1"])
    assert clock.slept == [10, 10], "no wait before the first or after the last notification"


def test_time_already_passed_counts_toward_the_pause(monkeypatch):
    _ok(monkeypatch)
    clock = Clock()
    n = _notifier(clock)
    n.send(title="A", is_new=True, category="Regular", chapters=["1"])
    clock.now += 4
    n.send(title="B", is_new=True, category="Regular", chapters=["1"])
    clock.now += 30
    n.send(title="C", is_new=True, category="Regular", chapters=["1"])
    assert clock.slept == [6]


def test_failed_notification_still_spaces_the_next(monkeypatch):
    class Bad:
        ok, status_code, text = False, 500, "err"
    monkeypatch.setattr(notifier_mod.requests, "post", lambda **kw: Bad())
    clock = Clock()
    n = _notifier(clock)
    n.send(title="A", is_new=True, category="Regular", chapters=["1"])
    n.send(title="B", is_new=True, category="Regular", chapters=["1"])
    assert clock.slept == [10]


def test_zero_delay_never_sleeps(monkeypatch):
    _ok(monkeypatch)
    clock = Clock()
    n = _notifier(clock, delay=0)
    for t in ("A", "B"):
        n.send(title=t, is_new=True, category="Regular", chapters=["1"])
    assert clock.slept == []


def _write_cfg(tmp_path, ntfy_extra=""):
    p = tmp_path / "config.yml"
    p.write_text("suwayomi: {url: http://s}\n"
                 "kavita: {url: http://k, api_key: x}\n"
                 "libraries: [{category: Regular, kavita_library_id: 1, path: /m}]\n"
                 "ntfy: {enabled: true, url: http://ntfy" + (", " + ntfy_extra if ntfy_extra else "") + "}\n")
    return p


def test_default_delay_is_ten_seconds(tmp_path):
    assert load_config(_write_cfg(tmp_path)).ntfy_delay_seconds == 10


def test_negative_delay_is_refused(tmp_path):
    with pytest.raises(ConfigError, match="delay_seconds"):
        load_config(_write_cfg(tmp_path, "delay_seconds: -1"))
