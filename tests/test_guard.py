import socket
import threading
import time

from manga_manager.guard import ConnectionGuard
from manga_manager.jobs import JobRunner
from manga_manager.scheduler import Scheduler


def _listener():
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(5)
    threading.Thread(target=lambda: [srv.accept() for _ in range(20)], daemon=True).start()
    return srv


def test_nas_online_and_offline(ctx):
    srv = _listener()
    port = srv.getsockname()[1]
    ctx.settings.set_many({"nas.host": "127.0.0.1", "nas.ports": str(port)})
    guard = ConnectionGuard(ctx)
    nas = next(c for c in guard.checks(force=True) if c["name"] == "NAS")
    assert nas["state"] == "ok"
    assert guard.reason() is None
    srv.close()
    closed = socket.socket(); closed.bind(("127.0.0.1", 0)); dead = closed.getsockname()[1]; closed.close()
    ctx.settings.set_many({"nas.ports": str(dead)})
    nas = next(c for c in guard.checks(force=True) if c["name"] == "NAS")
    assert nas["state"] == "fail"
    assert guard.reason() == "Offline: NAS"


def test_unset_connections_do_not_block(ctx):
    guard = ConnectionGuard(ctx)
    states = {c["name"]: c["state"] for c in guard.checks(force=True)}
    assert states["Database"] == "ok" and states["NAS"] == "unset"
    assert guard.reason() is None


def test_guard_can_be_switched_off(ctx):
    ctx.settings.set_many({"nas.host": "127.0.0.1", "nas.ports": "1", "guard.enabled": False})
    assert ConnectionGuard(ctx).reason() is None


def test_scheduled_job_is_skipped_but_manual_run_goes_through():
    runner = JobRunner()
    runner.gate = lambda: "Offline: NAS"
    ran = []
    job = runner.add("x", lambda: ran.append(1), 3600, run_on_start=False)
    runner._run(job)
    assert ran == [] and job.last_error == "Skipped: Offline: NAS"
    runner.trigger("x")
    runner._run(job)
    assert ran == [1] and job.last_error == ""
    runner._run(job)          # the bypass is used up
    assert ran == [1]


def test_move_scheduler_skips_when_gate_closed():
    class Hist:
        def start_run(self): raise AssertionError("must not start a run")
    s = Scheduler(lambda: None, Hist(), 10, False, gate=lambda: "Offline: NAS")
    assert s.run_once() == "Skipped: Offline: NAS"


def test_only_admin_can_use_settings_and_first_signup_is_atomic(ctx):
    from manga_manager.auth import UserStore
    store = UserStore(ctx.db, {})
    assert store.create("first", "a-long-password-1", first_only=True) is True
    assert store.create("second", "a-long-password-2", first_only=True) is False   # closed after the first
    store.create("guest", "a-long-password-3")
    assert store.is_admin("first") and not store.is_admin("guest")
    assert UserStore(ctx.db, {"cfg": "x"}).is_admin("CFG")
