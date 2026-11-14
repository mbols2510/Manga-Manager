"""#7: coordinated Kavita scans and optional library scans for bulk updates."""
import logging

import pytest

from manga_manager.config import load_config
from manga_manager.kavita import KavitaClient, KavitaError
from manga_manager.kavita_scans import ScanCoordinator


class FakeClient:
    """Records submissions; `busy` is a list of answers popped per check (then free)."""

    def __init__(self, busy=()):
        self.busy = list(busy)
        self.calls = []
        self.checks = 0

    def is_scan_busy(self):
        self.checks += 1
        if self.busy:
            answer = self.busy.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer
        return False

    def scan_library(self, library_id, force=False):
        self.calls.append(("library", library_id))

    def scan_series(self, library_id, series_id):
        self.calls.append(("series", library_id, series_id))


def make(client=None, **kw):
    client = client or FakeClient()
    sleeps = []
    kw.setdefault("scan_delay", 0)
    coord = ScanCoordinator(client, sleep=sleeps.append, **kw)
    return coord, client, sleeps


def test_waits_while_busy_then_submits(caplog):
    coord, client, sleeps = make(FakeClient(busy=[True, True, False]), poll_interval=15)
    coord.request_series(1, 10, "A")
    with caplog.at_level(logging.INFO):
        coord.flush()
    assert client.calls == [("series", 1, 10)]
    assert sleeps == [15, 15]
    assert "busy" in caplog.text.lower()


def test_next_scan_waits_again_between_submissions():
    coord, client, sleeps = make(FakeClient(busy=[False, True, False]), poll_interval=5)
    coord.request_series(1, 10, "A")
    coord.request_series(1, 11, "B")
    coord.flush()
    assert client.calls == [("series", 1, 10), ("series", 1, 11)]
    assert sleeps == [5]


def test_duplicate_requests_combined():
    coord, client, _ = make()
    coord.request_series(1, 10, "A")
    coord.request_series(1, 10, "A")
    coord.request_library(2)
    coord.request_library(2)
    coord.flush()
    assert sorted(client.calls) == [("library", 2), ("series", 1, 10)]


def test_api_error_is_unknown_state_and_retried():
    coord, client, sleeps = make(FakeClient(busy=[KavitaError("boom"), False]), poll_interval=7)
    coord.request_series(1, 10, "A")
    coord.flush()
    assert client.calls == [("series", 1, 10)]
    assert sleeps == [7]


def test_gives_up_waiting_after_timeout_and_still_scans():
    coord, client, sleeps = make(FakeClient(busy=[True] * 100), poll_interval=10, wait_timeout=30)
    coord.request_series(1, 10, "A")
    coord.flush()
    assert client.calls == [("series", 1, 10)]
    assert sum(sleeps) == 30


def test_scan_delay_between_submissions_only():
    coord, client, sleeps = make(scan_delay=60)
    coord.request_series(1, 10, "A")
    coord.request_series(1, 11, "B")
    coord.flush()
    assert sleeps == [60]


def six(coord, lib=1, n=6, start=100):
    for i in range(n):
        coord.request_series(lib, start + i, f"S{i}")


def test_bulk_over_threshold_becomes_one_library_scan(caplog):
    coord, client, _ = make(bulk_enabled=True, bulk_threshold=5)
    six(coord)
    with caplog.at_level(logging.INFO):
        coord.flush()
    assert client.calls == [("library", 1)]
    assert "replac" in caplog.text.lower()


def test_exactly_threshold_scans_individually():
    coord, client, _ = make(bulk_enabled=True, bulk_threshold=5)
    six(coord, n=5)
    coord.flush()
    assert client.calls == [("series", 1, 100 + i) for i in range(5)]


def test_bulk_disabled_by_default():
    coord, client, _ = make()
    six(coord, n=20)
    coord.flush()
    assert len(client.calls) == 20 and all(c[0] == "series" for c in client.calls)


def test_series_counted_once_even_with_many_chapters():
    coord, client, _ = make(bulk_enabled=True, bulk_threshold=5)
    for _ in range(4):
        six(coord, n=5)
    coord.flush()
    assert all(c[0] == "series" for c in client.calls) and len(client.calls) == 5


def test_thresholds_are_per_library_and_smallest_library_first():
    coord, client, _ = make(bulk_enabled=True, bulk_threshold=2, library_sizes={1: 500, 2: 50, 3: 10})
    six(coord, lib=1, n=3, start=100)       # over -> library scan
    six(coord, lib=2, n=2, start=200)       # at threshold -> individual
    coord.request_series(3, 300, "x")       # individual, smallest library
    coord.flush()
    assert client.calls == [("series", 3, 300), ("series", 2, 200), ("series", 2, 201), ("library", 1)]


def test_library_scan_replaces_individual_scans_of_that_library():
    coord, client, _ = make()
    coord.request_series(1, 10, "A")
    coord.request_series(2, 20, "B")
    coord.request_library(1)
    coord.flush()
    assert client.calls == [("library", 1), ("series", 2, 20)]


def test_only_libraries_with_requests_are_scanned():
    coord, client, _ = make(library_sizes={1: 5, 2: 1, 3: 9}, bulk_enabled=True, bulk_threshold=0)
    coord.request_series(3, 1, "A")
    coord.flush()
    assert client.calls == [("library", 3)]


def test_dry_run_scans_nothing():
    coord, client, _ = make(dry_run=True)
    coord.request_series(1, 1, "A")
    coord.request_library(2)
    coord.flush()
    assert client.calls == [] and client.checks == 0


def test_failed_scan_does_not_stop_others():
    coord, client, _ = make()
    orig = client.scan_series

    def flaky(lib, sid):
        if sid == 1:
            raise RuntimeError("x")
        orig(lib, sid)
    client.scan_series = flaky
    coord.request_series(1, 1, "A")
    coord.request_series(1, 2, "B")
    coord.flush()
    assert client.calls == [("series", 1, 2)]


def test_client_without_busy_check_just_scans():
    class Old:
        def __init__(self):
            self.calls = []

        def scan_series(self, lib, sid):
            self.calls.append(sid)
    old = Old()
    coord = ScanCoordinator(old, sleep=lambda s: None)
    coord.request_series(1, 5, "A")
    coord.flush()
    assert old.calls == [5]


# ---------------------------------------------------------------- client
class Resp:
    def __init__(self, status=200, body=None):
        self.status_code, self._body = status, body
        self.ok = status < 400
        self.text = str(body)

    def json(self):
        return self._body


def client_with(responses):
    c = KavitaClient("http://k", "key")
    seen = []

    def fake(method, path, **kw):
        seen.append((path, kw.get("params")))
        r = responses[path]
        return r(kw) if callable(r) else r
    c._request = fake
    return c, seen


def test_client_busy_when_any_scan_task_running():
    def running(kw):
        return Resp(200, kw["params"]["methodName"] == "ScanLibrary")
    c, seen = client_with({"/api/Server/is-task-running": running, "/api/Server/activity": Resp(404)})
    assert c.is_scan_busy() is True
    assert {p["methodName"] for path, p in seen if p} >= {"ScanSeries", "ScanLibrary"}


def test_client_free_and_activity_404_falls_back_and_is_remembered():
    c, seen = client_with({"/api/Server/is-task-running": Resp(200, False), "/api/Server/activity": Resp(404)})
    assert c.is_scan_busy() is False
    c.is_scan_busy()
    assert [p for p, _ in seen].count("/api/Server/activity") == 1


def test_client_activity_with_delayed_scans_means_busy():
    c, _ = client_with({"/api/Server/is-task-running": Resp(200, False),
                        "/api/Server/activity": Resp(200, {"active": [], "delayed": [{"name": "ScanSeries"}]})})
    assert c.is_scan_busy() is True


def test_client_error_raises():
    c, _ = client_with({"/api/Server/is-task-running": Resp(500, "x"), "/api/Server/activity": Resp(404)})
    with pytest.raises(KavitaError):
        c.is_scan_busy()


# ---------------------------------------------------------------- config
BASE = """suwayomi: {url: "http://s"}
libraries: [{category: R, kavita_library_id: 1, path: /m}]
kavita: {url: "http://k", api_key: k%s}
"""


def test_config_defaults_and_values(tmp_path):
    f = tmp_path / "c.yml"
    f.write_text(BASE % "")
    cfg = load_config(f)
    assert cfg.scan_bulk_enabled is False and cfg.scan_bulk_threshold == 5
    f.write_text(BASE % ", scan_bulk_enabled: true, scan_bulk_threshold: 8")
    cfg = load_config(f)
    assert cfg.scan_bulk_enabled is True and cfg.scan_bulk_threshold == 8
