"""Coordinated Kavita scans (#7).

During one update run, scan requests are only collected. `flush()` then submits them one at
a time, and before every submission waits until Kavita is not busy with another scan.

* duplicate requests (same series / same library) are combined;
* with bulk scans on, a library with MORE than `bulk_threshold` distinct updated series gets one
  library scan, which replaces all individual series scans of that library (each library is
  judged on its own);
* libraries are handled smallest to largest (by total series count), and only those with a request.
"""
from __future__ import annotations

import logging
import time
from typing import Callable

log = logging.getLogger(__name__)

LOG_EVERY = 60  # seconds between "still waiting" log lines


class ScanCoordinator:
    def __init__(self, client, *, sleep: Callable[[float], None] = time.sleep, scan_delay: int = 0,
                 bulk_enabled: bool = False, bulk_threshold: int = 5,
                 library_sizes: dict[int, int] | None = None, poll_interval: int = 15,
                 wait_timeout: int = 1800, dry_run: bool = False):
        self.client = client
        self.sleep = sleep
        self.scan_delay = scan_delay
        self.bulk_enabled = bulk_enabled
        self.bulk_threshold = bulk_threshold
        self.library_sizes = library_sizes or {}
        self.poll_interval = max(1, poll_interval)
        self.wait_timeout = wait_timeout
        self.dry_run = dry_run
        self._libraries: dict[int, str] = {}                   # library id -> reason
        self._series: dict[int, dict[int, str]] = {}           # library id -> {series id: title}

    # ------------------------------------------------------------ collecting
    def request_library(self, library_id: int, reason: str = "") -> None:
        self._libraries.setdefault(library_id, reason)

    def request_series(self, library_id: int, series_id: int, title: str = "") -> None:
        self._series.setdefault(library_id, {}).setdefault(series_id, title)

    # ------------------------------------------------------------ planning
    def plan(self) -> list[tuple[str, Callable[[], None]]]:
        steps: list[tuple[str, Callable[[], None]]] = []
        affected = set(self._libraries) | set(self._series)
        for lib in sorted(affected, key=lambda l: (self.library_sizes.get(l, 0), l)):
            series = self._series.get(lib, {})
            if lib in self._libraries:
                reason = f" ({self._libraries[lib]})" if self._libraries[lib] else ""
                if series:
                    log.info("Library %s scan replaces %d individual series scan(s)", lib, len(series))
                steps.append((f"library {lib}{reason}", lambda lib=lib: self.client.scan_library(lib)))
            elif self.bulk_enabled and len(series) > self.bulk_threshold:
                log.info("%d series updated in library %s (more than %d): one library scan replaces the "
                         "individual series scans", len(series), lib, self.bulk_threshold)
                steps.append((f"library {lib} ({len(series)} updated series)",
                              lambda lib=lib: self.client.scan_library(lib)))
            else:
                for sid, title in series.items():
                    steps.append((f"series {sid} ({title})",
                                  lambda lib=lib, sid=sid: self.client.scan_series(lib, sid)))
        return steps

    # ------------------------------------------------------------ submitting
    def flush(self) -> None:
        steps = self.plan()
        self._libraries.clear()
        self._series.clear()
        if self.dry_run:
            for what, _ in steps:
                log.info("[dry-run] would scan Kavita %s", what)
            return
        if len(steps) > 1:
            log.info("Starting %d Kavita scans, one at a time", len(steps))
        for i, (what, start) in enumerate(steps):
            if i and self.scan_delay:
                self.sleep(self.scan_delay)
            self._wait_until_free(what)
            log.info("Scanning Kavita %s", what)
            try:
                start()
            except Exception as exc:  # one failed scan shouldn't stop the others
                log.warning("Kavita scan of %s failed: %s", what, exc)

    def _wait_until_free(self, what: str) -> None:
        wait_until_free(getattr(self.client, "is_scan_busy", None), what, sleep=self.sleep,
                        poll_interval=self.poll_interval, wait_timeout=self.wait_timeout)


def wait_until_free(check, what: str, *, sleep: Callable[[float], None] = time.sleep, poll_interval: int = 15,
                    wait_timeout: int = 1800) -> None:
    """Poll `check()` (True = Kavita busy) until it is False, for at most `wait_timeout` seconds.

    A failing check counts as busy. After the timeout it returns anyway. `check=None` returns at once.
    Used by the coordinator and by the Double Chapters tool, so both wait the same way."""
    if check is None:
        return
    poll_interval = max(1, poll_interval)
    waited = 0
    logged_at = None
    while True:
        try:
            busy = check()
            reason = "Kavita is busy with another scan"
        except Exception as exc:  # unknown state: treat like busy and ask again later
            busy = True
            reason = f"could not read Kavita's scan state ({exc})"
        if not busy:
            return
        if waited >= wait_timeout:
            log.warning("Kavita still not free after %ss; scanning %s anyway", waited, what)
            return
        step = min(poll_interval, wait_timeout - waited)
        if logged_at is None or waited - logged_at >= LOG_EVERY:
            log.info("Waiting before scanning %s (polling every %ss): %s", what, poll_interval, reason)
            logged_at = waited
        sleep(step)
        waited += step
