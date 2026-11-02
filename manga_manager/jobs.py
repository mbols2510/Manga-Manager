"""Small background-job runner for the tabs (one daemon thread per job)."""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Callable

log = logging.getLogger(__name__)


@dataclass
class Job:
    name: str
    fn: Callable[[], object]
    interval: Callable[[], float] | float      # seconds (callable so a changed setting applies on the next wait)
    run_on_start: bool = True
    running: bool = False
    last_started: float | None = None
    last_finished: float | None = None
    last_error: str = ""
    next_run: float | None = None
    manual: bool = False
    _trigger: threading.Event = field(default_factory=threading.Event)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def status(self) -> dict:
        def iso(t):
            return datetime.fromtimestamp(t, timezone.utc).isoformat(timespec="seconds") if t else None
        return {"name": self.name, "running": self.running, "last_started": iso(self.last_started),
                "last_finished": iso(self.last_finished), "last_error": self.last_error, "next_run": iso(self.next_run)}


class JobRunner:
    def __init__(self):
        self.jobs: dict[str, Job] = {}
        self.gate: Callable[[], str | None] | None = None   # returns why scheduled work must wait
        self._stop = threading.Event()

    def add(self, name: str, fn: Callable[[], object], interval: Callable[[], float] | float,
            run_on_start: bool = True) -> Job:
        job = Job(name, fn, interval, run_on_start)
        self.jobs[name] = job
        return job

    def start(self) -> None:
        for job in self.jobs.values():
            threading.Thread(target=self._loop, args=(job,), name=f"job-{job.name}", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()
        for job in self.jobs.values():
            job._trigger.set()

    def trigger(self, name: str) -> bool:
        """Run a job now. False if it is already running or unknown."""
        job = self.jobs.get(name)
        if not job or job.running:
            return False
        job.manual = True          # an explicit request ignores the connection gate
        job._trigger.set()
        return True

    def status(self) -> dict[str, dict]:
        return {n: j.status() for n, j in self.jobs.items()}

    def _run(self, job: Job) -> None:
        manual, job.manual = job.manual, False
        if not manual and self.gate is not None:
            try:
                reason = self.gate()
            except Exception as exc:  # noqa: BLE001 - never let the gate itself kill a job
                reason = None
                log.warning("Connection gate failed: %s", exc)
            if reason:
                job.last_error = f"Skipped: {reason}"
                log.info("Job %s skipped. %s", job.name, reason)
                return
        if not job._lock.acquire(blocking=False):
            return
        job.running, job.last_started, job.next_run = True, time.time(), None
        try:
            job.fn()
            job.last_error = ""
        except Exception as exc:  # noqa: BLE001
            job.last_error = str(exc)[:500]
            log.warning("Job %s failed: %s", job.name, exc)
            log.debug("Job %s traceback", job.name, exc_info=True)
        finally:
            job.running, job.last_finished = False, time.time()
            job._lock.release()

    def _loop(self, job: Job) -> None:
        first = True
        while not self._stop.is_set():
            if not (first and job.run_on_start):
                wait = max(1.0, float(job.interval() if callable(job.interval) else job.interval))
                job.next_run = time.time() + wait
                job._trigger.wait(wait)
                job._trigger.clear()
                if self._stop.is_set():
                    break
            first = False
            self._run(job)
