"""Entry point.

    python -m manga_manager            # dashboard + scheduled runs (what the container does)
    python -m manga_manager import-legacy --move-manga history.db --double-chapters DIR --collections DIR
    python -m manga_manager run-once   # a single run, then exit
    python -m manga_manager check      # test connections and config, change nothing
    python -m manga_manager hash-password  # make a password hash for a dashboard account
    add --dry-run to log what would happen without moving/scanning/uploading anything
"""
from __future__ import annotations

import argparse
import logging
import os
import signal
import sys
from logging.handlers import TimedRotatingFileHandler

from . import __version__
from .config import Config, ConfigError, load_config
from .context import AppContext
from .covers import CoverStore
from .db import Database
from .history import History
from .kavita import KavitaClient
from .notifier import Notifier
from .pipeline import Pipeline
from .scheduler import ALREADY_RUNNING, Scheduler
from .settings import Settings
from .suwayomi import SuwayomiClient

log = logging.getLogger("manga_manager")


def setup_logging(cfg: Config) -> None:
    fmt = logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s", "%Y-%m-%d %H:%M:%S")
    root = logging.getLogger()
    root.setLevel(cfg.log_level)
    root.handlers.clear()
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    root.addHandler(console)
    try:
        cfg.log_dir.mkdir(parents=True, exist_ok=True)
        fh = TimedRotatingFileHandler(cfg.log_dir / "manga-manager.log", when="midnight", backupCount=14,
                                      encoding="utf-8")
        fh.setFormatter(fmt)
        root.addHandler(fh)
    except OSError as exc:
        log.warning("File logging disabled: %s", exc)
    for noisy in ("urllib3", "waitress", "PIL"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def open_database(cfg: Config) -> Database:
    if not cfg.database_url:
        raise ConfigError("No database configured. Set DATABASE_URL (or POSTGRES_HOST, POSTGRES_USER, "
                          "POSTGRES_PASSWORD, POSTGRES_DB) in .env.")
    db = Database.wait_for(cfg.database_url)
    db.init_schema()
    return db


def prepare(cfg: Config) -> tuple[Database, Settings]:
    """Connect to Postgres and let the saved Settings override config.yml / .env."""
    db = open_database(cfg)
    settings = Settings(db)
    from . import guard as _guard, preferred as _preferred  # noqa: F401  register their settings
    from .tools import import_settings_only
    import_settings_only()           # registers every tab's settings
    settings.apply_to_config(cfg)
    return db, settings


def build(cfg: Config, db: Database):
    suwayomi = SuwayomiClient(cfg.suwayomi_url, cfg.suwayomi_username, cfg.suwayomi_password)
    kavita = KavitaClient(cfg.kavita_url, cfg.kavita_api_key, cfg.kavita_plugin_name)
    history = History(db)
    cfg.dashboard_aliases = history.aliases()   # #12: aliases added on the dashboard
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwayomi, cfg.thumbnail_export, cfg.dry_run)
    thumb_path = None
    if cfg.auth_enabled:
        from .auth import ThumbSigner, load_secret_key
        thumb_path = ThumbSigner(load_secret_key(cfg)).path
    notifier = Notifier(cfg.ntfy_url, cfg.ntfy_topic, cfg.ntfy_template, cfg.ntfy_token, cfg.ntfy_delay_seconds,
                        dashboard_url=cfg.web_public_url, attach_icon=cfg.ntfy_attach_icon,
                        enabled=cfg.ntfy_enabled, dry_run=cfg.dry_run, attach_cover=cfg.ntfy_attach_cover,
                        chapter_status=cfg.ntfy_chapter_status, thumb_path=thumb_path)

    def make_pipeline() -> Pipeline:
        return Pipeline(cfg, suwayomi, kavita, covers, history, notifier)

    return suwayomi, kavita, history, covers, make_pipeline


def check(cfg: Config, db: Database) -> int:
    suwayomi, kavita, _history, _covers, _ = build(cfg, db)
    ok = True

    def report(name: str, fn):
        nonlocal ok
        try:
            print(f"  OK    {name}: {fn()}")
        except Exception as exc:
            ok = False
            print(f"  FAIL  {name}: {exc}")

    print(f"Manga Manager {__version__} - configuration check")
    report("PostgreSQL", lambda: "connected" if db.ping() else "no answer")
    report("Suwayomi GraphQL", lambda: f"{len(suwayomi.library())} manga in library")
    if cfg.wait_for_whole_manga and cfg.check_download_queue:
        def queue_check():
            active = suwayomi.active_downloads()
            if not active.chapters:
                return "readable, nothing downloading right now"
            return (f"readable, {sum(active.chapters.values())} chapter(s) for {len(active.chapters)} manga "
                    "still downloading (these wait until they're done)")
        report("Suwayomi download queue", queue_check)
    report("Kavita API", lambda: f"{len(kavita.all_series())} series visible")
    def folder_exists(path):
        if not path.is_dir():
            raise FileNotFoundError(f"{path} does not exist (check the volume mount)")
        return f"{path} exists"

    report("download folder", lambda: folder_exists(cfg.downloads))
    report("Suwayomi thumbnails", lambda: folder_exists(cfg.suwayomi_thumbnails))
    for lib in cfg.libraries:
        def lib_check(lib=lib):
            if not lib.path.is_dir():
                raise FileNotFoundError(f"{lib.path} does not exist")
            probe = lib.path / ".manga_manager_write_test"
            probe.write_text("ok")
            probe.unlink()
            return f"{lib.path} writable, Kavita library {lib.kavita_library_id}"
        report(f"library '{lib.label}'", lib_check)
    if cfg.ntfy_enabled:
        import requests
        def ntfy_check():
            resp = requests.get(cfg.ntfy_url + "/v1/health", timeout=10)
            if not resp.ok:
                raise RuntimeError(f"HTTP {resp.status_code} from {cfg.ntfy_url}/v1/health")
            return f"reachable ({resp.text.strip()[:60]})"
        report("ntfy", ntfy_check)
    for line in address_notes(cfg) + frame_notes(cfg):
        print(line)
    try:
        matching_report(cfg, suwayomi, kavita)
    except Exception as exc:
        print(f"\n  (matching report skipped: {exc})")
    print("\nAll checks passed." if ok else "\nSome checks failed.")
    return 0 if ok else 1


def _is_local(url: str) -> bool:
    import ipaddress
    from urllib.parse import urlparse
    host = urlparse(url).hostname or ""
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        return "." not in host or host.endswith((".local", ".lan", ".home", ".internal"))


def frame_notes(cfg: Config) -> list[str]:
    """Which frame image each framed category uses on the dashboard (#7)."""
    from .frames import FrameStore
    if not cfg.frame_categories:
        return ["\nCover frames: off"]
    store = FrameStore(cfg.config_dir / "frames", cfg.frame_categories, cfg.frame_window)
    lines = ["\nCover frames:"]
    for lib in cfg.libraries:
        if lib.label.lower() not in {c.lower() for c in cfg.frame_categories}:
            continue
        options = store.frames_for(lib.label)
        if not options:
            lines.append(f"  {lib.label}: no usable image")
        elif options[0].path == store.builtin:
            lines.append(f"  {lib.label}: built-in frame")
        else:
            lines.append(f"  {lib.label}: {len(options)} frame(s), one picked at random per cover")
            lines.extend(f"    {f.path}" for f in options)
    return lines


def address_notes(cfg: Config) -> list[str]:
    """Which addresses links and images use, with a hint when they only work at home."""
    lines = ["\nLinks:"]
    kav = cfg.kavita_public_url
    lines.append(f"  Dashboard 'open in Kavita' links: {kav or '(none)'}")
    if kav and _is_local(kav):
        lines.append("    This is a home-network address. Set kavita.public_url to your public Kavita address "
                     "to make the links work away from home.")
    if cfg.ntfy_enabled:
        if not cfg.web_public_url:
            lines.append("  ntfy covers and click-through: off (set web.public_url to the dashboard's address)")
        else:
            lines.append(f"  ntfy covers and click-through: {cfg.web_public_url}")
            if _is_local(cfg.web_public_url):
                lines.append("    This is a home-network address: covers in notifications only load while your "
                             "phone is on the home network.")
    if cfg.auth_enabled:
        lines.append(f"  Dashboard login: on ({len(cfg.auth_users)} account(s) in the config, "
                     f"sign-up {'on' if cfg.auth_allow_signup else 'only for the first account'})")
    elif cfg.web_public_url and not _is_local(cfg.web_public_url):
        lines.append("  Dashboard login: OFF while web.public_url is a public address. Turn on web.auth.")
    return lines


def hash_password_command() -> int:
    import getpass
    from .auth import MIN_PASSWORD, hash_password
    pw = getpass.getpass("Password for the dashboard account: ")
    if len(pw) < MIN_PASSWORD:
        print(f"Use at least {MIN_PASSWORD} characters.", file=sys.stderr)
        return 1
    if getpass.getpass("Same password again: ") != pw:
        print("The passwords don't match.", file=sys.stderr)
        return 1
    print("\nPut this line under web.auth.users in config.yml (replace <username>):\n")
    print(f'      <username>: "{hash_password(pw)}"')
    return 0


def matching_report(cfg: Config, suwayomi: SuwayomiClient, kavita: KavitaClient, show: int = 25) -> None:
    """How well Kavita series and Suwayomi manga line up (read-only)."""
    from collections import Counter
    from .suwayomi import SuwayomiIndex

    mangas = suwayomi.library()
    index = SuwayomiIndex(mangas)
    configured = {l.kavita_library_id for l in cfg.libraries}
    series = [s for s in kavita.all_series() if s.library_id in configured]

    print("\nCategories in Suwayomi:")
    cats = Counter(c for m in mangas for c in (m.categories or ["[none]"]))
    for name, count in cats.most_common():
        lib = cfg.library_for_category([name]) if name != "[none]" else None
        target = f"-> {lib.label} (Kavita {lib.kavita_library_id})" if lib else "-> NOT MAPPED, downloads will be skipped"
        print(f"  {name}: {count} {target}")

    hows, unmatched = Counter(), []
    for s in series:
        manga, how = index.match(cfg.suwayomi_title_for(s.name), s.name, s.localized_name,
                                 s.original_name, s.folder_name)
        hows[how] += 1
        if manga is None:
            unmatched.append(s)
        elif how == "stripped":
            print(f"  matched without decorations: {s.name!r} -> {manga.title!r}")
    print(f"\nKavita series in configured libraries: {len(series)}")
    print(f"  exact {hows['exact']}, loose {hows['loose']}, without decorations {hows['stripped']}, "
          f"no match {hows['none']}")
    if unmatched:
        print("  No Suwayomi match (removed from the Suwayomi library, or renamed; add an alias if the "
              "manga still gets chapters):")
        for s in unmatched[:show]:
            print(f"    [{s.library_id}] {s.name}")
        if len(unmatched) > show:
            print(f"    ... and {len(unmatched) - show} more")


def title_lists(cfg: Config, suwayomi: SuwayomiClient, kavita: KavitaClient):
    """Names suggested in the dashboard's alias form (#12)."""
    def load() -> dict[str, list[str]]:
        libs = {l.kavita_library_id for l in cfg.libraries}
        return {"suwayomi": [m.title for m in suwayomi.library()],
                "kavita": [s.name for s in kavita.all_series() if s.library_id in libs]}
    return load


def serve(cfg: Config, db: Database, settings: Settings) -> int:
    suwayomi, kavita, history, covers, make_pipeline = build(cfg, db)
    ctx = AppContext(cfg=cfg, db=db, settings=settings)
    from .guard import ConnectionGuard
    ctx.guard = ConnectionGuard(ctx)
    ctx.jobs.gate = ctx.guard.reason
    if not cfg.dry_run and cfg.web_keep_days > 0:
        removed = history.prune_updates(cfg.web_keep_days)   # #14
        if removed:
            log.info("Removed %d dashboard update(s) older than %d days", removed, cfg.web_keep_days)
    scheduler = Scheduler(make_pipeline, history, cfg.interval_minutes, cfg.run_on_start,
                          lock_path=cfg.run_lock,
                          configured=lambda: bool(cfg.suwayomi_url and cfg.kavita_url and cfg.kavita_api_key),
                          gate=ctx.guard.reason)

    def shutdown(*_):
        log.info("Stopping")
        scheduler.stop()
        ctx.jobs.stop()
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    log.info("Manga Manager %s started: running every %d minute(s)%s", __version__, cfg.interval_minutes,
             " (DRY RUN)" if cfg.dry_run else "")
    if cfg.web_enabled and not cfg.auth_enabled and cfg.web_host not in ("127.0.0.1", "localhost", "::1") \
            and os.environ.get("WEB_INSECURE_NO_AUTH") != "1":
        log.error("Refusing to start: web login is off while the dashboard listens on %s. Turn on web.auth in "
                  "config.yml, or set WEB_INSECURE_NO_AUTH=1 if you really want an open dashboard.", cfg.web_host)
        return 2
    if cfg.web_enabled:
        from waitress import serve as waitress_serve
        from .frames import FrameStore
        from .web import create_app
        if not cfg.dry_run:
            FrameStore(cfg.config_dir / "frames", cfg.frame_categories).install_defaults()
        log.info("Dashboard listening on http://%s:%d", cfg.web_host, cfg.web_port)
        flask_app = create_app(cfg, history, covers, scheduler, titles=title_lists(cfg, suwayomi, kavita),
                               reprocess=lambda title, apply: make_pipeline().reprocess_alias(title, apply),
                               ctx=ctx, preview=lambda title: make_pipeline().preview_preferred(title))
        scheduler.start()
        ctx.jobs.start()
        waitress_serve(flask_app, host=cfg.web_host, port=cfg.web_port, threads=8, ident="manga-manager")
    else:
        scheduler.start()
        ctx.jobs.start()
        signal.pause()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="manga_manager", description="Move Suwayomi downloads into Kavita.")
    parser.add_argument("command", nargs="?", default="serve", choices=["serve", "run-once", "check", "hash-password", "import-legacy"])
    parser.add_argument("--move-manga", help="import-legacy: old Move Manga history.db (SQLite)")
    parser.add_argument("--double-chapters", help="import-legacy: folder with the old Double Chapters state.json")
    parser.add_argument("--collections", help="import-legacy: folder with the old Collection Manager config.json/history.json")
    parser.add_argument("-c", "--config", help="path to config.yml (default /config/config.yml)")
    parser.add_argument("--dry-run", action="store_true", help="log actions without changing anything")
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args(argv)
    if args.command == "hash-password":  # needs no config
        return hash_password_command()

    try:
        cfg = load_config(args.config)
    except ConfigError as exc:
        print(exc, file=sys.stderr)
        return 2
    if args.dry_run:
        cfg.dry_run = True
    setup_logging(cfg)

    try:
        db, settings = prepare(cfg)
    except ConfigError as exc:
        print(exc, file=sys.stderr)
        return 2
    if args.command == "import-legacy":
        from .legacy import run_import
        print(run_import(AppContext(cfg=cfg, db=db, settings=settings), args.move_manga,
                         args.double_chapters, args.collections))
        return 0
    if args.command == "check":
        return check(cfg, db)
    if args.command == "run-once":
        _s, _k, history, _c, make_pipeline = build(cfg, db)
        message = Scheduler(make_pipeline, history, cfg.interval_minutes, lock_path=cfg.run_lock).run_once()
        if message == ALREADY_RUNNING:
            print("A run is already in progress (the service is busy); nothing was done. Try again when it's finished.",
                  file=sys.stderr)
            return 1
        print(message)
        return 0
    return serve(cfg, db, settings)


if __name__ == "__main__":
    sys.exit(main())
