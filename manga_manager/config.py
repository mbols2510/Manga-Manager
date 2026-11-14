"""Configuration loading.

The config is a YAML file (default: /config/config.yml). Any value may reference
environment variables with ${VAR} or ${VAR:-default}, so secrets can live in a
.env file / compose environment instead of the YAML file.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from .db import database_url_from_env

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")


class ConfigError(Exception):
    """Raised when the configuration is missing or invalid."""


def _expand(value: Any) -> Any:
    if isinstance(value, str):
        return _ENV_PATTERN.sub(lambda m: os.environ.get(m.group(1), m.group(2) or ""), value)
    if isinstance(value, list):
        return [_expand(v) for v in value]
    if isinstance(value, dict):
        return {k: _expand(v) for k, v in value.items()}
    return value


def _as_bool(value: Any, default: bool = False) -> bool:
    if value is None or value == "":
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class LibraryConfig:
    category: str            # Suwayomi category name (prefix match, case-insensitive)
    kavita_library_id: int   # Kavita library id
    path: Path               # Library folder as seen from inside the container
    label: str = ""          # Name shown in notifications / dashboard (defaults to category)

    def matches(self, category_name: str) -> bool:
        return category_name.strip().lower().startswith(self.category.strip().lower())


@dataclass
class Config:
    # schedule
    interval_minutes: int = 15
    run_on_start: bool = True
    # paths
    downloads: Path = Path("/suwayomi/downloads/mangas")
    suwayomi_thumbnails: Path = Path("/suwayomi/downloads/thumbnails")
    data_dir: Path = Path("/data")
    thumbnail_export: Path | None = None
    settle_seconds: int = 120
    # #8: wait until the whole manga folder is quiet (True) instead of judging each chapter (False)
    wait_for_whole_manga: bool = True
    extensions: tuple[str, ...] = (".cbz",)
    # suwayomi
    suwayomi_url: str = ""
    suwayomi_username: str = ""
    suwayomi_password: str = ""
    check_download_queue: bool = True   # #8: hold back manga that still have chapters in Suwayomi's queue
    # #8 (aliases): manga in this Suwayomi category are added to the aliases with an empty Kavita source; "" = off
    official_library: str = "ADULT_OFFICIAL"
    # kavita
    kavita_url: str = ""
    kavita_api_key: str = ""
    kavita_public_url: str = ""
    kavita_plugin_name: str = "MoveManga"
    align_series_name: bool = True
    new_series_timeout: int = 300
    scan_delay: int = 60
    scan_bulk_enabled: bool = False     # #7: one library scan instead of many series scans
    scan_bulk_threshold: int = 5        # more distinct series than this in a library -> library scan
    scan_poll_interval: int = 15        # seconds between "is Kavita still scanning?" checks
    scan_wait_timeout: int = 1800       # give up waiting for Kavita to be free after this many seconds
    # libraries
    libraries: list[LibraryConfig] = field(default_factory=list)
    # manual title mapping: Suwayomi title -> Kavita series name
    aliases: dict[str, str] = field(default_factory=dict)
    # aliases added on the dashboard (#12), loaded from the history database; they win over config.yml
    dashboard_aliases: dict[str, str] = field(default_factory=dict)
    # source type set on an alias (casefolded Suwayomi title -> 1 official, 2 uncensored, 3 both)
    dashboard_ranks: dict[str, int] = field(default_factory=dict)
    # preferred sources: official / uncensored versions replace the same chapter from a lower-ranked source
    src_enabled: bool = True
    src_auto_detect: bool = True        # read (Official) / (Uncensored) from the Suwayomi title
    src_min_page_pct: int = 60          # keep both files when the preferred one has fewer pages than this share
    # covers
    covers_enabled: bool = True
    covers_lock: bool = True
    covers_write_file: bool = False
    covers_file_name: str = "cover.png"
    # ntfy
    ntfy_enabled: bool = False
    ntfy_url: str = ""
    ntfy_topic: str = "Manga_Updates"
    ntfy_template: str = "MangaUpdates"
    ntfy_token: str = ""
    ntfy_delay_seconds: float = 10
    ntfy_attach_icon: bool = True
    ntfy_attach_cover: bool = True
    ntfy_chapter_status: bool = True

    # Discord (runs next to ntfy)
    discord_enabled: bool = False
    discord_webhook_url: str = ""
    discord_emoji_new: str = ""
    discord_emoji_replaced: str = ""
    discord_emoji_duplicate: str = ""
    # web
    web_enabled: bool = True
    web_host: str = "0.0.0.0"
    web_port: int = 8080
    web_title: str = "Manga updates"
    web_public_url: str = ""
    web_blur_categories: list[str] = field(default_factory=list)
    web_allow_manual_run: bool = True
    web_mature_categories: list[str] = field(default_factory=lambda: ["Adult", "Hentai"])
    web_behind_proxy: bool = False
    web_secure_cookie: bool = False   # only send the login cookie over https
    web_keep_days: int = 14         # #14: updates older than this are deleted; 0 = keep forever
    # frame drawn around mature covers (#7)
    frame_categories: list[str] = field(default_factory=lambda: ["Adult", "Hentai"])
    frame_window: tuple[int, int, int, int] | None = None   # top, right, bottom, left in image pixels
    frame_columns: int = 0          # 0 = automatic (2 for a frame wider than tall), or 1 / 2
    config_dir: Path = Path("/config")
    # web login
    auth_enabled: bool = False
    auth_users: dict[str, str] = field(default_factory=dict)   # username -> password hash
    auth_allow_signup: bool = False
    auth_signup_code: str = ""
    auth_secret_key: str = ""
    auth_session_days: int = 30
    # database (Postgres)
    database_url: str = ""
    # misc
    log_level: str = "INFO"
    dry_run: bool = False

    # ---- derived helpers -------------------------------------------------
    @property
    def thumbnail_cache(self) -> Path:
        return self.data_dir / "thumbnails"

    @property
    def run_lock(self) -> Path:
        return self.data_dir / "run.lock"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs"

    def library_for_category(self, category_names: list[str]) -> LibraryConfig | None:
        """First configured library that matches one of the manga's Suwayomi categories."""
        for lib in self.libraries:
            for name in category_names:
                if lib.matches(name):
                    return lib
        return None

    def kavita_name_for(self, suwayomi_title: str) -> str | None:
        """Kavita series name configured for a Suwayomi title (case-insensitive)."""
        return self._alias_fwd().get(suwayomi_title.casefold())

    def suwayomi_title_for(self, kavita_name: str) -> str | None:
        return {v.casefold(): k for k, v in self.all_aliases().items() if v}.get(kavita_name.casefold())

    def has_alias(self, suwayomi_title: str) -> bool:
        """True when the title has an alias at all, also one whose Kavita source is still empty."""
        return suwayomi_title.casefold() in self._alias_fwd()

    def alias_pending(self, suwayomi_title: str) -> bool:
        """An alias exists (auto-added, #8) but its Kavita source is still empty: nothing is moved for it."""
        value = self._alias_fwd().get(suwayomi_title.casefold())
        return value is not None and not value.strip()

    def all_aliases(self) -> dict[str, str]:
        """config.yml aliases plus dashboard aliases; a dashboard alias replaces one for the same title."""
        merged = {k.casefold(): (k, v) for k, v in self.aliases.items()}
        merged.update({k.casefold(): (k, v) for k, v in self.dashboard_aliases.items()})
        return dict(merged.values())

    def _alias_fwd(self) -> dict[str, str]:
        return {k.casefold(): v for k, v in self.all_aliases().items()}

    def source_rank(self, suwayomi_title: str) -> int:
        """Source type of a Suwayomi title: set on its alias, else read from (Official) / (Uncensored) in the name."""
        from .preferred import detect_rank
        if not self.src_enabled:
            return 0
        explicit = self.dashboard_ranks.get(suwayomi_title.casefold(), 0)
        if explicit:
            return explicit
        return detect_rank(suwayomi_title) if self.src_auto_detect else 0

    def display_title(self, suwayomi_title: str, kavita_name: str | None = None) -> str:
        """Name shown in notifications and on the dashboard: Kavita's name when known."""
        return kavita_name or self.kavita_name_for(suwayomi_title) or suwayomi_title

    def library_by_id(self, library_id: int) -> LibraryConfig | None:
        return next((l for l in self.libraries if l.kavita_library_id == library_id), None)


def _window(value: Any) -> tuple[int, int, int, int] | None:
    if value in (None, "", []):
        return None
    try:
        t, r, b, l = (int(v) for v in value)
    except (TypeError, ValueError) as exc:
        raise ConfigError("web.frame.window must be [top, right, bottom, left] in pixels") from exc
    return t, r, b, l


def _columns(value: Any) -> int:
    if value in (None, "", "auto"):
        return 0
    try:
        n = int(value)
    except (TypeError, ValueError) as exc:
        raise ConfigError("web.frame.columns must be auto, 1 or 2") from exc
    if n not in (1, 2):
        raise ConfigError("web.frame.columns must be auto, 1 or 2")
    return n


def load_config(path: str | os.PathLike | None = None) -> Config:
    path = Path(path or os.environ.get("MANGA_MANAGER_CONFIG", "/config/config.yml"))
    if not path.exists():
        # First start: seed the config folder from the bundled example (values come from .env / Settings).
        example = Path(__file__).resolve().parent.parent / "config.example.yml"
        if not example.exists():
            example = Path("/app/config.example.yml")
        if not example.exists():
            raise ConfigError(f"Config file not found: {path}. Copy config.example.yml to that location.")
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(example.read_text(encoding="utf-8"), encoding="utf-8")
        except OSError:
            path = example
    with path.open("r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh) or {}
    raw = _expand(raw)

    sched = raw.get("schedule", {}) or {}
    paths = raw.get("paths", {}) or {}
    suwa = raw.get("suwayomi", {}) or {}
    kav = raw.get("kavita", {}) or {}
    cov = raw.get("covers", {}) or {}
    ntfy = raw.get("ntfy", {}) or {}
    discord = raw.get("discord", {}) or {}
    web = raw.get("web", {}) or {}
    auth = web.get("auth", {}) or {}
    frame = web.get("frame", {}) or {}

    libraries = []
    for i, lib in enumerate(raw.get("libraries", []) or []):
        try:
            libraries.append(
                LibraryConfig(
                    category=str(lib["category"]),
                    kavita_library_id=int(lib["kavita_library_id"]),
                    path=Path(lib["path"]),
                    label=str(lib.get("label") or lib["category"]),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ConfigError(f"libraries[{i}] is invalid ({exc}); needs category, kavita_library_id and path") from exc

    aliases = raw.get("aliases") or {}
    if not isinstance(aliases, dict):
        raise ConfigError("aliases must be a mapping of 'Suwayomi title': 'Kavita series name'")

    users = auth.get("users") or {}
    if not isinstance(users, dict):
        raise ConfigError("web.auth.users must be a mapping of 'username': 'password hash'")

    export = paths.get("thumbnail_export")
    exts = paths.get("extensions") or [".cbz"]

    cfg = Config(
        interval_minutes=int(sched.get("interval_minutes", 15)),
        run_on_start=_as_bool(sched.get("run_on_start"), True),
        downloads=Path(paths.get("downloads", "/suwayomi/downloads/mangas")),
        suwayomi_thumbnails=Path(paths.get("suwayomi_thumbnails", "/suwayomi/downloads/thumbnails")),
        data_dir=Path(paths.get("data", "/data")),
        thumbnail_export=Path(export) if export else None,
        settle_seconds=int(paths.get("settle_seconds", 120)),
        wait_for_whole_manga=_as_bool(paths.get("wait_for_whole_manga"), True),
        extensions=tuple(e.lower() if e.startswith(".") else f".{e.lower()}" for e in exts),
        suwayomi_url=str(suwa.get("url", "")).rstrip("/"),
        suwayomi_username=str(suwa.get("username", "") or ""),
        suwayomi_password=str(suwa.get("password", "") or ""),
        check_download_queue=_as_bool(suwa.get("check_download_queue"), True),
        official_library=str(suwa["official_library"] if suwa.get("official_library") is not None
                             else "ADULT_OFFICIAL").strip(),
        kavita_url=str(kav.get("url", "")).rstrip("/"),
        kavita_api_key=str(kav.get("api_key", "") or ""),
        # public_url: the address Kavita links on the dashboard open (falls back to url)
        kavita_public_url=str(kav.get("public_url") or kav.get("url", "")).rstrip("/"),
        kavita_plugin_name=str(kav.get("plugin_name", "MoveManga")),
        align_series_name=_as_bool(kav.get("align_series_name"), True),
        new_series_timeout=int(kav.get("new_series_timeout", 300)),
        # scan_delay replaces series_scan_delay (2.0.0-2.0.2); the old name is still read.
        scan_delay=int(kav.get("scan_delay", kav.get("series_scan_delay", 60))),
        scan_bulk_enabled=_as_bool(kav.get("scan_bulk_enabled"), False),
        scan_bulk_threshold=int(kav.get("scan_bulk_threshold", 5)),
        scan_poll_interval=int(kav.get("scan_poll_interval", 15)),
        scan_wait_timeout=int(kav.get("scan_wait_timeout", 1800)),
        libraries=libraries,
        aliases={str(k): str(v) for k, v in aliases.items()},
        covers_enabled=_as_bool(cov.get("enabled"), True),
        covers_lock=_as_bool(cov.get("lock"), True),
        covers_write_file=_as_bool(cov.get("write_cover_file"), False),
        covers_file_name=str(cov.get("file_name", "cover.png")),
        ntfy_enabled=_as_bool(ntfy.get("enabled"), False),
        ntfy_url=str(ntfy.get("url", "")).rstrip("/"),
        ntfy_topic=str(ntfy.get("topic", "Manga_Updates")),
        ntfy_template=str(ntfy.get("template", "MangaUpdates") or ""),
        ntfy_token=str(ntfy.get("token", "") or ""),
        ntfy_delay_seconds=float(ntfy.get("delay_seconds", 10)),
        ntfy_attach_icon=_as_bool(ntfy.get("attach_icon"), True),
        ntfy_attach_cover=_as_bool(ntfy.get("attach_cover"), True),
        ntfy_chapter_status=_as_bool(ntfy.get("chapter_status"), True),
        discord_enabled=_as_bool(discord.get("enabled"), False),
        discord_webhook_url=str(discord.get("webhook_url", "") or ""),
        discord_emoji_new=str(discord.get("emoji_new", "") or ""),
        discord_emoji_replaced=str(discord.get("emoji_replaced", "") or ""),
        discord_emoji_duplicate=str(discord.get("emoji_duplicate", "") or ""),
        web_enabled=_as_bool(web.get("enabled"), True),
        web_host=str(web.get("host", "0.0.0.0")),
        web_port=int(web.get("port", 8080)),
        web_title=str(web.get("title", "Manga updates")),
        web_public_url=str(web.get("public_url", "") or "").rstrip("/"),
        web_blur_categories=[str(c) for c in (web.get("blur_categories") or [])],
        web_allow_manual_run=_as_bool(web.get("allow_manual_run"), True),
        web_mature_categories=[str(c) for c in (web["mature_categories"] if "mature_categories" in web
                                                 else ["Adult", "Hentai"]) or []],
        web_behind_proxy=_as_bool(web.get("behind_proxy"), False),
        web_secure_cookie=_as_bool(web.get("secure_cookie"), False),
        web_keep_days=int(web["keep_days"]) if web.get("keep_days") not in (None, "") else 14,
        frame_categories=[str(c) for c in (frame["categories"] if "categories" in frame
                                           else ["Adult", "Hentai"]) or []],
        frame_window=_window(frame.get("window")),
        frame_columns=_columns(frame.get("columns", "auto")),
        config_dir=path.parent,
        auth_enabled=_as_bool(auth.get("enabled"), False),
        auth_users={str(k): str(v) for k, v in users.items() if v},
        auth_allow_signup=_as_bool(auth.get("allow_signup"), False),
        auth_signup_code=str(auth.get("signup_code", "") or ""),
        auth_secret_key=str(auth.get("secret_key", "") or ""),
        auth_session_days=int(auth.get("session_days", 30)),
        database_url=database_url_from_env(),
        log_level=str(raw.get("log_level", "INFO")).upper(),
        dry_run=_as_bool(raw.get("dry_run"), False),
    )
    validate(cfg)
    return cfg


def validate(cfg: Config) -> None:
    problems = []
    if not cfg.libraries:
        problems.append("at least one entry under libraries is required")
    ids = [l.kavita_library_id for l in cfg.libraries]
    if len(ids) != len(set(ids)):
        problems.append("each library must have a unique kavita_library_id")
    if cfg.scan_delay < 0:
        problems.append("kavita.scan_delay can't be negative")
    if cfg.scan_bulk_threshold < 0:
        problems.append("kavita.scan_bulk_threshold can't be negative")
    if cfg.scan_poll_interval < 1:
        problems.append("kavita.scan_poll_interval must be at least 1")
    if cfg.scan_wait_timeout < 0:
        problems.append("kavita.scan_wait_timeout can't be negative")
    if cfg.web_keep_days < 0:
        problems.append("web.keep_days can't be negative (0 keeps every update)")
    if cfg.ntfy_delay_seconds < 0:
        problems.append("ntfy.delay_seconds can't be negative")
    if cfg.auth_session_days < 1:
        problems.append("web.auth.session_days must be at least 1")
    if cfg.settle_seconds < 0:
        problems.append("paths.settle_seconds can't be negative")
    if cfg.interval_minutes < 1:
        problems.append("schedule.interval_minutes must be at least 1")
    if problems:
        raise ConfigError("Invalid configuration:\n  - " + "\n  - ".join(problems))
