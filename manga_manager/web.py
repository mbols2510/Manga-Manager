"""Dashboard: every manga that was added or updated, with its cover."""
from __future__ import annotations

import hmac
import logging
import threading
import time
from datetime import timedelta
from typing import Callable
from urllib.parse import urlparse

from flask import (Flask, abort, jsonify, redirect, render_template, request, send_file, session,
                   url_for)

from . import __version__
from .auth import LoginLimiter, ThumbSigner, UserStore, load_secret_key, validate_signup
from .config import Config
from .covers import CoverStore
from .frames import FrameStore, slug
from .history import History
from .preferred import RANK_LABELS, detect_rank
from .scheduler import Scheduler

log = logging.getLogger(__name__)

# Reachable without logging in. /thumb also works with a signed link (for ntfy).
# Settings hold the connection secrets and paths, so only an administrator (a config.yml account or the
# first account) may open or change them.
ADMIN_ENDPOINTS = {"core.settings_page", "core.settings_get", "core.settings_save", "core.settings_restart",
                   "core.test_notification"}
PUBLIC_ENDPOINTS = {"login", "signup", "health", "core.health", "static", "manifest", "service_worker", "offline"}


ALIAS_MAX = 300           # longest title accepted for an alias (#12)
SUGGESTIONS_TTL = 600     # seconds the Suwayomi / Kavita title lists for the alias form are kept


def create_app(cfg: Config, history: History, covers: CoverStore, scheduler: Scheduler | None,
               limiter: LoginLimiter | None = None,
               titles: Callable[[], dict[str, list[str]]] | None = None,
               reprocess: Callable[[str, bool], dict] | None = None,
               ctx=None, preview: Callable[[str], dict] | None = None) -> Flask:
    """titles: returns {"suwayomi": [...], "kavita": [...]}, the names suggested in the alias form.
    reprocess: (suwayomi_title, apply) -> what it moved or would move into the alias's series (#16)."""
    app = Flask(__name__)
    app.json.sort_keys = False
    secret = load_secret_key(cfg)
    app.secret_key = secret
    app.config.update(
        SESSION_COOKIE_NAME="manga_manager_session",
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        SESSION_COOKIE_SECURE=cfg.web_secure_cookie,
        PERMANENT_SESSION_LIFETIME=timedelta(days=cfg.auth_session_days),
    )
    if cfg.web_behind_proxy:
        # Behind Cloudflare / a reverse proxy: take the client IP and https from its headers.
        from werkzeug.middleware.proxy_fix import ProxyFix
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)

    frames = FrameStore(cfg.config_dir / "frames", cfg.frame_categories, cfg.frame_window)

    def frame_info() -> dict:
        """category -> every frame it can get (#13); the page picks one at random for each cover."""
        out = {}
        for lib in cfg.libraries:
            options = []
            for i, f in enumerate(frames.frames_for(lib.label)):
                mtime = int(f.path.stat().st_mtime)
                options.append({**f.css(), "url": f"frame/{slug(lib.label)}/{i}?v={mtime}",
                                "span": cfg.frame_columns or f.columns})
            if options:
                out[lib.label.lower()] = options
        return out

    signer = ThumbSigner(secret)
    # The "Rescan" button in the weekly ntfy digest has no session; it carries this token instead.
    action_token = hmac.new(secret.encode(), b"action:dc-scan", "sha256").hexdigest()[:32]
    if ctx is not None:
        ctx.extras["action_token"] = action_token
        ctx.extras["scheduler"] = scheduler
    users = UserStore(history.db, cfg.auth_users) if cfg.auth_enabled else None
    limiter = limiter or LoginLimiter()

    def kavita_link(item: dict) -> str | None:
        if cfg.kavita_public_url and item.get("kavita_series_id") and item.get("kavita_library_id"):
            return f"{cfg.kavita_public_url}/library/{item['kavita_library_id']}/series/{item['kavita_series_id']}"
        return None

    # ------------------------------------------------------------ aliases (#12)
    cfg.dashboard_aliases = history.aliases()
    cfg.dashboard_ranks = history.alias_ranks()
    suggestions = {"at": 0.0, "data": None}
    suggestions_lock = threading.Lock()

    def can_edit_aliases() -> bool:
        """With login on, only the accounts written in config.yml; accounts from the sign-up page can look."""
        if not cfg.auth_enabled:
            return True
        user = session.get("user") or ""
        return user.casefold() in {u.casefold() for u in cfg.auth_users}

    def alias_list() -> dict:
        dash = history.aliases()
        cfg.dashboard_aliases = dash
        cfg.dashboard_ranks = history.alias_ranks()
        mine = {k.casefold() for k in dash}
        items = [{"suwayomi_title": k, "kavita_name": v, "source": "dashboard",
                  "rank": cfg.dashboard_ranks.get(k.casefold(), 0), "rank_auto": detect_rank(k)} for k, v in dash.items()]
        items += [{"suwayomi_title": k, "kavita_name": v, "source": "config", "overridden": k.casefold() in mine,
                   "rank": 0, "rank_auto": detect_rank(k)}
                  for k, v in cfg.aliases.items()]
        items.sort(key=lambda i: (i["suwayomi_title"].casefold(), i["source"] != "dashboard"))
        return {"items": items, "can_edit": can_edit_aliases()}

    def clean_title(value) -> str:
        text = " ".join(str(value or "").split())   # also drops newlines and tabs
        if len(text) > ALIAS_MAX:
            raise ValueError(f"Names can be at most {ALIAS_MAX} characters.")
        return text

    def safe_next(target: str | None) -> str:
        # Only paths on this site, never //other.host or http://...
        if target and target.startswith("/") and not target.startswith("//") and "\\" not in target:
            return target
        return url_for("index")

    def signup_open() -> bool:
        """Sign-up is open when the config allows it, and always for the very first account."""
        return bool(cfg.auth_enabled and (cfg.auth_allow_signup or users.count() == 0))

    def client_ip() -> str:
        return request.remote_addr or "?"

    # ------------------------------------------------------------ access control
    @app.before_request
    def guard():
        if request.method in ("POST", "PUT", "PATCH", "DELETE"):
            # Reject cross-site form posts (the session cookie is SameSite=Lax as well).
            origin = request.headers.get("Origin")
            if origin and origin != "null" and urlparse(origin).netloc != request.host:
                abort(403)
        if not cfg.auth_enabled or request.endpoint in PUBLIC_ENDPOINTS:
            return None
        if request.endpoint == "thumb" and signer.verify(request.view_args.get("manga_id", -1),
                                                         request.args.get("s")):
            return None
        if (request.endpoint == "double_chapters.scan" and request.method == "POST"
                and hmac.compare_digest(request.headers.get("X-Action-Token", ""), action_token)):
            return None
        user = session.get("user")
        if user and users.exists(user):
            if request.endpoint in ADMIN_ENDPOINTS and not users.is_admin(user):
                if request.path.startswith("/api/"):
                    return jsonify({"error": "only an administrator can do this"}), 403
                abort(403)
            return None
        session.pop("user", None)
        if request.path.startswith("/api/"):
            return jsonify({"error": "login required"}), 401
        if request.endpoint == "thumb":
            abort(401)
        return redirect(url_for("login", next=request.full_path.rstrip("?")))

    @app.after_request
    def headers(resp):
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        resp.headers.setdefault("Referrer-Policy", "same-origin")
        if cfg.auth_enabled and resp.mimetype in ("text/html", "application/json"):
            resp.headers.setdefault("Cache-Control", "no-store")
        return resp

    # ------------------------------------------------------------ installable web app
    @app.get("/manifest.webmanifest")
    def manifest():
        root = url_for("index")
        data = {"id": root, "name": "Manga Manager", "short_name": "Manga Manager",
                "description": "Your manga library, updates and chapter tools",
                "start_url": root, "scope": root, "display": "standalone",
                "background_color": "#08080a", "theme_color": "#08080a",
                "icons": [{"src": url_for("static", filename=f"icons/icon-{size}.png"),
                           "sizes": f"{size}x{size}", "type": "image/png", "purpose": "any maskable"}
                          for size in (192, 512)]}
        response = jsonify(data)
        response.mimetype = "application/manifest+json"
        response.headers["Cache-Control"] = "no-cache"
        return response

    @app.get("/service-worker.js")
    def service_worker():
        assets = [url_for("static", filename=name) for name in
                  ("app.css", "tools.css", "pwa.js", "icons/icon-180.png", "icons/icon-192.png", "icons/icon-512.png")]
        return app.response_class(render_template("service-worker.js", assets=assets, version=__version__),
                                  mimetype="application/javascript", headers={"Cache-Control": "no-store"})

    @app.get("/offline")
    def offline():
        return render_template("offline.html")

    # ------------------------------------------------------------ login
    def auth_page(mode: str, error: str = "", username: str = "", status: int = 200):
        return render_template("login.html", title=cfg.web_title, mode=mode, error=error, username=username,
                               allow_signup=signup_open(), need_code=bool(cfg.auth_signup_code) and users.count() > 0,
                               next=request.args.get("next", ""), version=__version__), status

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if not cfg.auth_enabled:
            return redirect(url_for("index"))
        if request.method == "GET":
            return auth_page("login")
        username = (request.form.get("username") or "").strip()
        password = request.form.get("password") or ""
        keys = (f"ip:{client_ip()}", f"user:{username.casefold()}")
        if limiter.blocked(*keys):
            log.warning("Login blocked for %r from %s: too many failed attempts", username, client_ip())
            return auth_page("login", "Too many failed attempts. Try again in 15 minutes.", username, 429)
        name = users.authenticate(username, password)
        if not name:
            limiter.fail(*keys)
            log.info("Failed login for %r from %s", username, client_ip())
            return auth_page("login", "Wrong username or password.", username, 401)
        limiter.reset(f"user:{username.casefold()}")
        session.clear()
        session.permanent = True
        session["user"] = name
        log.info("%s logged in from %s", name, client_ip())
        return redirect(safe_next(request.args.get("next")))

    @app.route("/signup", methods=["GET", "POST"])
    def signup():
        if not signup_open():
            abort(404)
        if request.method == "GET":
            return auth_page("signup")
        username = (request.form.get("username") or "").strip()
        key = f"signup:{client_ip()}"
        if limiter.blocked(key):
            return auth_page("signup", "Too many attempts. Try again in 15 minutes.", username, 429)
        if cfg.auth_signup_code and not hmac.compare_digest(
                (request.form.get("code") or "").encode(), cfg.auth_signup_code.encode()):
            limiter.fail(key)
            return auth_page("signup", "That invite code isn't right.", username, 403)
        problem = validate_signup(username, request.form.get("password") or "", request.form.get("confirm") or "")
        if not problem and users.exists(username):
            problem = "That username is taken."
        if problem:
            limiter.fail(key)
            return auth_page("signup", problem, username, 400)
        limiter.fail(key)       # every sign-up counts, successful or not
        created = users.create(username, request.form["password"],
                               first_only=not cfg.auth_allow_signup)
        if not created:
            return auth_page("signup", "Sign-up is closed.", username, 403)
        log.info("New dashboard account %r created from %s", username, client_ip())
        session.clear()
        session.permanent = True
        session["user"] = username
        return redirect(safe_next(request.args.get("next")))

    @app.post("/logout")
    def logout():
        session.clear()
        return redirect(url_for("login") if cfg.auth_enabled else url_for("index"))

    # ------------------------------------------------------------ dashboard
    @app.get("/")
    def index():
        return render_template(
            "index.html",
            title=cfg.web_title,
            blur=[c.lower() for c in cfg.web_blur_categories],
            mature=[c.lower() for c in cfg.web_mature_categories],
            frames=frame_info(),
            keep_days=cfg.web_keep_days,
            categories=[l.label for l in cfg.libraries],
            allow_run=cfg.web_allow_manual_run and scheduler is not None,
            user=session.get("user") if cfg.auth_enabled else None,
            version=__version__,
        )

    @app.get("/api/updates")
    def updates():
        limit = min(max(request.args.get("limit", 60, type=int), 1), 200)
        offset = max(request.args.get("offset", 0, type=int), 0)
        query = (request.args.get("q") or "").strip() or None
        # Updates stored before 2.1.0 carry the Suwayomi title: find them by their alias too.
        also = [k for k, v in cfg.all_aliases().items() if query and query.casefold() in v.casefold()]
        items, total = history.list_updates(
            limit=limit, offset=offset,
            category=request.args.get("category") or None,
            kind=request.args.get("kind") or None,
            query=query, also_titles=also,
        )
        for item in items:
            item["title"] = cfg.display_title(item["title"])
            item["kavita_url"] = kavita_link(item)
            item["thumbnail"] = f"thumb/{item['suwayomi_id']}.png" if item.get("suwayomi_id") else None
        return jsonify({"items": items, "total": total, "offset": offset, "limit": limit})

    @app.get("/api/aliases")
    def aliases():
        return jsonify(alias_list())

    @app.post("/api/aliases")
    def save_alias():
        if not can_edit_aliases():
            return jsonify({"error": "Only accounts listed in config.yml can change aliases."}), 403
        data = request.get_json(silent=True) or {}
        try:
            suwa, kav = clean_title(data.get("suwayomi_title")), clean_title(data.get("kavita_name"))
            previous = clean_title(data.get("previous")) or None
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        if not suwa or not kav:
            return jsonify({"error": "Fill in both the Suwayomi title and the Kavita series."}), 400
        if suwa.casefold() == kav.casefold():
            return jsonify({"error": "Both names are the same, so no alias is needed."}), 400
        try:
            rank = int(data.get("rank") or 0)
        except (TypeError, ValueError):
            rank = -1
        if rank not in RANK_LABELS:
            return jsonify({"error": "Pick a valid source type."}), 400
        history.set_alias(suwa, kav, user=session.get("user"), previous=previous, rank=rank)
        log.info("Alias %r -> %r saved on the dashboard%s", suwa, kav,
                 f" by {session['user']}" if session.get("user") else "")
        return jsonify(alias_list())

    @app.post("/api/aliases/delete")
    def delete_alias():
        if not can_edit_aliases():
            return jsonify({"error": "Only accounts listed in config.yml can change aliases."}), 403
        title = str((request.get_json(silent=True) or {}).get("suwayomi_title") or "")
        if not history.delete_alias(title):
            return jsonify({"error": "That alias doesn't exist (any more)."}), 404
        log.info("Alias for %r removed on the dashboard", title)
        return jsonify(alias_list())

    @app.post("/api/aliases/reprocess")
    def reprocess_alias():
        if not can_edit_aliases():
            return jsonify({"error": "Only accounts listed in config.yml can change aliases."}), 403
        if reprocess is None:
            return jsonify({"error": "Reprocessing isn't available here."}), 404
        data = request.get_json(silent=True) or {}
        try:
            title = clean_title(data.get("suwayomi_title"))
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        if not title:
            return jsonify({"error": "Which alias?"}), 400
        apply = bool(data.get("apply"))
        job = lambda: reprocess(title, apply)
        try:
            if apply and scheduler:
                ran, result = scheduler.run_exclusive(job)
                if not ran:
                    return jsonify({"error": "A run is moving files right now. Try again in a minute."}), 409
            else:
                result = job()
        except Exception as exc:
            log.exception("Reprocessing the alias for %r failed", title)
            return jsonify({"error": f"Reprocessing failed: {exc}"}), 500
        if apply:
            log.info("Alias %r reprocessed from the dashboard%s: %s", title,
                     f" by {session['user']}" if session.get("user") else "", result.get("message"))
        return jsonify(result)

    @app.post("/api/aliases/preferred-preview")
    def preferred_preview():
        if preview is None:
            return jsonify({"error": "Preview isn't available here."}), 404
        try:
            title = clean_title((request.get_json(silent=True) or {}).get("suwayomi_title"))
        except ValueError as exc:
            return jsonify({"error": str(exc)}), 400
        if not title:
            return jsonify({"error": "Which title?"}), 400
        try:
            return jsonify(preview(title))
        except Exception as exc:
            log.exception("Preferred-source preview for %r failed", title)
            return jsonify({"error": f"Preview failed: {exc}"}), 500

    @app.get("/api/aliases/suggestions")
    def alias_suggestions():
        if titles is None:
            return jsonify({"suwayomi": [], "kavita": []})
        with suggestions_lock:
            if suggestions["data"] is None or time.monotonic() - suggestions["at"] > SUGGESTIONS_TTL:
                try:
                    data = titles()
                    suggestions.update(at=time.monotonic(), data={
                        k: sorted(set(data.get(k) or []), key=str.casefold) for k in ("suwayomi", "kavita")})
                except Exception as exc:  # the form still works with typed names
                    log.warning("Could not load title suggestions for the alias form: %s", exc)
                    return jsonify({"suwayomi": [], "kavita": [], "error": str(exc)})
            return jsonify(suggestions["data"])

    @app.get("/api/status")
    def status():
        data = history.stats()
        data["scheduler"] = scheduler.status() if scheduler else None
        data["version"] = __version__
        return jsonify(data)

    @app.post("/api/run")
    def run_now():
        if not (cfg.web_allow_manual_run and scheduler):
            abort(403)
        started = scheduler.trigger()
        return jsonify({"started": started, "message": "Run started" if started else "A run is already in progress"}), (
            202 if started else 409)

    @app.get("/thumb/<int:manga_id>.png")
    def thumb(manga_id: int):
        path = covers.cached_path(manga_id)
        if not path.exists():
            path = covers.get_png(manga_id)
        if not path or not path.exists():
            abort(404)
        resp = send_file(path, mimetype="image/png", max_age=86400)
        if cfg.auth_enabled:
            resp.cache_control.public = False
            resp.cache_control.private = True
        return resp

    @app.get("/frame/<name>")
    @app.get("/frame/<name>/<int:number>")
    def frame(name: str, number: int = 0):
        lib = next((l for l in cfg.libraries if slug(l.label) == name), None)
        options = frames.frames_for(lib.label) if lib else []
        if number >= len(options):
            abort(404)
        f = options[number]
        return send_file(f.path, mimetype=f.mimetype, max_age=3600)

    if ctx is None:
        @app.get("/health")
        def health():
            return {"status": "ok", "version": __version__}

    app.context_processor(lambda: {"has_endpoint": lambda name: name in app.view_functions})
    if ctx is not None:
        from .core_routes import build_blueprint
        from .tools import load_tools
        app.register_blueprint(build_blueprint(ctx, lambda: session.get("user") if cfg.auth_enabled else None))
        for bp in load_tools(ctx):
            app.register_blueprint(bp)

    return app
