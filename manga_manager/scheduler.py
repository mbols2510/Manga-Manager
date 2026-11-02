"""Runs the pipeline on an interval in a background thread (and on demand)."""
from __future__ import annotations

import logging
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

try:
    import fcntl
except ImportError:  # not on Linux (the container always is)
    fcntl = None

from .history import History
from .pipeline import Pipeline

log = logging.getLogger(__name__)

ALREADY_RUNNING = "already running"


class RunLock:
    """A lock file shared by every Move Manga process using the same data folder (#9).

    Inside the service the thread lock already keeps runs one at a time; this also stops a
    manual `run-once` from moving files while the service is busy, and the other way round.
    """

    def __init__(self, path: Path | None):
        self.path = path
        self._fd: int | None = None

    def acquire(self) -> bool:
        if self.path is None or fcntl is None:
            return True
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        except OSError as exc:  # never let a lock problem stop the moves
            log.warning("Could not open run lock %s (%s); continuing without it", self.path, exc)
            return True
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            return False
        os.ftruncate(fd, 0)
        os.write(fd, str(os.getpid()).encode())
        self._fd = fd
        return True

    def release(self) -> None:
        if self._fd is not None:
            fcntl.flock(self._fd, fcntl.LOCK_UN)
            os.close(self._fd)
            self._fd = None


class Scheduler:
    def __init__(self, make_pipeline: Callable[[], Pipeline], history: History, interval_minutes: int,
                 run_on_start: bool = True, lock_path: Path | None = None,
                 configured: Callable[[], bool] | None = None,
                 gate: Callable[[], str | None] | None = None):
        self.make_pipeline = make_pipeline
        self.gate = gate
        self._manual = False
        self.configured = configured
        self.history = history
        self.interval = interval_minutes * 60
        self.run_on_start = run_on_start
        self._trigger = threading.Event()
        self._stop = threading.Event()
        self._run_lock = threading.Lock()
        self._file_lock = RunLock(lock_path)
        self.running = False
        self.next_run: datetime | None = None
        self.last_result: str | None = None
        self._thread: threading.Thread | None = None

    # -- control -------------------------------------------------------------
    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="move-manga-scheduler", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._trigger.set()

    def trigger(self) -> bool:
        """Request a run as soon as possible. Returns False if one is already running."""
        if self.running:
            return False
        self._manual = True        # an explicit request ignores the connection gate
        self._trigger.set()
        return True

    def run_exclusive(self, work: Callable[[], object]):
        """Do work while no run is moving files (#16). Returns (True, result), or (False, None) when busy."""
        if not self._run_lock.acquire(blocking=False):
            return False, None
        if not self._file_lock.acquire():
            self._run_lock.release()
            return False, None
        try:
            return True, work()
        finally:
            self._file_lock.release()
            self._run_lock.release()

    def status(self) -> dict:
        return {
            "running": self.running,
            "next_run": self.next_run.isoformat(timespec="seconds") if self.next_run else None,
            "interval_minutes": self.interval // 60,
            "last_result": self.last_result,
        }

    # -- internals -----------------------------------------------------------
    # Runs never overlap: the next one is planned `interval` after the previous one ends (#9).
    def _loop(self) -> None:
        first = True
        while not self._stop.is_set():
            if not (first and self.run_on_start):
                self.next_run = datetime.now(timezone.utc) + timedelta(seconds=self.interval)
                self._trigger.wait(self.interval)
                self._trigger.clear()
                if self._stop.is_set():
                    break
            first = False
            self.run_once()

    def run_once(self) -> str:
        if self.configured is not None and not self.configured():
            self.last_result = "Not configured yet: set Suwayomi and Kavita on the Settings page"
            log.info(self.last_result)
            return self.last_result
        manual, self._manual = self._manual, False
        if self.gate is not None and not manual:
            reason = self.gate()
            if reason:
                self.last_result = f"Skipped: {reason}"
                log.info("Run skipped. %s", reason)
                return self.last_result
        if not self._run_lock.acquire(blocking=False):
            return ALREADY_RUNNING
        if not self._file_lock.acquire():
            self._run_lock.release()
            log.info("Another Move Manga process is moving files right now; skipping this run")
            return ALREADY_RUNNING
        self.running = True
        self.next_run = None
        run_id = self.history.start_run()
        started = time.monotonic()
        status, moved, covers, message = "ok", 0, 0, ""
        try:
            result = self.make_pipeline().run()
            moved, covers = len(result.processed), result.covers_set
            message = result.summary()
            if result.errors:
                status = "warning"
                message += " | " + "; ".join(result.errors[:5])
            log.info("Run finished in %.0fs: %s", time.monotonic() - started, result.summary())
        except Exception as exc:
            status, message = "error", str(exc)
            log.exception("Run failed")
        finally:
            self.history.finish_run(run_id, status, moved, covers, message)
            self.last_result = message
            self.running = False
            self._file_lock.release()
            self._run_lock.release()
        return message
