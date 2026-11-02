"""CBZ ComicInfo editor tab: upload .cbz files, edit their ComicInfo.xml, download the result.

Uploads live in a scratch folder (<data>/cbz-scratch) that is wiped on start; the originals on the
user's machine are never touched. API under /api/cbz/, page at /cbz.
"""
from __future__ import annotations

import logging
import os
import re
import shutil
import tempfile
import uuid
import zipfile
from decimal import Decimal, InvalidOperation
from pathlib import Path
from threading import RLock

from flask import (Blueprint, after_this_request, jsonify, render_template, request, send_file, session)

from .. import __version__
from ..settings import Setting, register
from . import cbz_comicinfo as comicinfo

log = logging.getLogger(__name__)

register(Setting("cbz.max_upload_mb", "Max upload per request (MB)", "CBZ editor", "int", "CBZ_MAX_UPLOAD_MB", 2048,
                 hint="Larger uploads are refused"))

ID_RE = re.compile(r"[0-9a-f]{32}")
MAX_BULK_FILES = 2000
MAX_OPS = 20
MAX_REGEX_CHARS = 500
CBZ_MIMETYPE = "application/vnd.comicbook+zip"


class ApiError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def natural_key(s: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def safe_name(name: str) -> str:
    """A plain file name: no directories, no control or reserved characters, bounded length."""
    name = os.path.basename((name or "").replace("\\", "/")).strip().lstrip(".") or "file.cbz"
    name = re.sub(r'[<>:"|?*\x00-\x1f]', "_", name)
    stem, ext = os.path.splitext(name)
    return stem[:150] + ext[:10]


def fmt_number(d: Decimal, pad: int) -> str:
    s = format(d.normalize(), "f")
    if "." in s:
        whole, frac = s.split(".", 1)
        return whole.zfill(pad) + "." + frac
    return s.zfill(pad)


def init(ctx) -> Blueprint:
    bp = Blueprint("cbz", __name__)
    scratch: Path = ctx.cfg.data_dir / "cbz-scratch"
    # Uploads are a scratch area; start clean.
    shutil.rmtree(scratch, ignore_errors=True)
    scratch.mkdir(parents=True, exist_ok=True)
    lock = RLock()   # one writer at a time; archives are rewritten in place

    # ------------------------------------------------------------ helpers
    def max_bytes() -> int:
        return max(1, int(ctx.settings.get("cbz.max_upload_mb", 2048))) * 1024 * 1024

    def file_path(file_id: str) -> Path:
        if not ID_RE.fullmatch(file_id or ""):
            raise ApiError("Unknown file", 404)
        d = scratch / file_id
        files = list(d.glob("*")) if d.is_dir() else []
        if not files:
            raise ApiError("Unknown file", 404)
        return files[0]

    def describe(file_id: str) -> dict:
        p = file_path(file_id)
        return {"id": file_id, "name": p.name, "size": p.stat().st_size, **comicinfo.read_info(str(p))}

    @bp.errorhandler(ApiError)
    def _api_error(e: ApiError):
        return jsonify({"error": str(e)}), e.status

    # ------------------------------------------------------------ page
    @bp.get("/cbz")
    def page():
        return render_template("cbz.html", page_title="CBZ editor", active="cbz",
                               user=session.get("user") if ctx.cfg.auth_enabled else None,
                               version=__version__, max_upload_mb=max_bytes() // (1024 * 1024))

    # ------------------------------------------------------------ files
    @bp.get("/api/cbz/fields")
    def fields():
        return jsonify({"fields": comicinfo.SIMPLE_FIELDS})

    @bp.get("/api/cbz/files")
    def list_files():
        out = []
        for d in scratch.iterdir():
            if d.is_dir() and ID_RE.fullmatch(d.name):
                try:
                    out.append(describe(d.name))
                except ApiError:
                    pass
        out.sort(key=lambda f: natural_key(f["name"]))
        return jsonify(out)

    @bp.post("/api/cbz/upload")
    def upload():
        limit = max_bytes()
        if request.content_length is not None and request.content_length > limit + 1024 * 1024:
            raise ApiError(f"Upload is larger than the {limit // (1024 * 1024)} MB limit", 413)
        uploads = request.files.getlist("files")
        if not uploads:
            raise ApiError("No files were sent")
        added, written = [], 0
        for f in uploads:
            name = safe_name(f.filename)
            file_id = uuid.uuid4().hex
            d = scratch / file_id
            d.mkdir(parents=True)
            dest = d / name
            too_big = False
            with dest.open("wb") as out:
                while chunk := f.stream.read(1024 * 1024):
                    written += len(chunk)
                    if written > limit:
                        too_big = True
                        break
                    out.write(chunk)
            if too_big:
                shutil.rmtree(d, ignore_errors=True)
                added.append({"name": name, "error": f"Over the {limit // (1024 * 1024)} MB upload limit"})
                break
            if not zipfile.is_zipfile(dest):
                shutil.rmtree(d, ignore_errors=True)
                added.append({"name": name, "error": "Not a valid CBZ/zip file"})
                continue
            info = describe(file_id)
            if info["error"] and "ComicInfo.xml is invalid" not in info["error"]:
                # Unsafe or corrupt archives are not kept.
                shutil.rmtree(d, ignore_errors=True)
                added.append({"name": name, "error": info["error"]})
                continue
            added.append(info)
        return jsonify(added)

    @bp.delete("/api/cbz/files/<file_id>")
    def delete_file(file_id: str):
        file_path(file_id)
        with lock:
            shutil.rmtree(scratch / file_id, ignore_errors=True)
        return jsonify({"ok": True})

    @bp.delete("/api/cbz/files")
    def delete_all():
        with lock:
            for d in scratch.iterdir():
                shutil.rmtree(d, ignore_errors=True)
        return jsonify({"ok": True})

    def write(path: Path, changes: dict) -> None:
        try:
            comicinfo.write_info(str(path), changes)
        except (ValueError, comicinfo.ET.ParseError, zipfile.BadZipFile) as e:
            raise ApiError(f"Could not update {path.name}: {e}") from e
        except OSError as e:
            log.exception("Could not rewrite %s", path)
            raise ApiError(f"Could not update {path.name}: {e.strerror or e}", 500) from e

    @bp.put("/api/cbz/files/<file_id>")
    def edit_file(file_id: str):
        body = request.get_json(silent=True)
        changes = body.get("fields") if isinstance(body, dict) else None
        if not isinstance(changes, dict) or not all(isinstance(k, str) for k in changes):
            raise ApiError("Body must be {\"fields\": {name: value}}")
        p = file_path(file_id)
        with lock:
            write(p, changes)
        return jsonify(describe(file_id))

    # ------------------------------------------------------------ bulk
    def plan(files: list[dict], ops: list[dict]) -> dict[str, dict]:
        """{id: {field: new_value}} applying ops in order on top of the current values."""
        state = {f["id"]: dict(f["fields"]) for f in files}
        for op in ops:
            fld, typ = op.get("field"), op.get("type")
            if fld not in comicinfo.SIMPLE_FIELDS:
                raise ApiError(f"Unknown field: {fld}")
            if typ == "set":
                value = str(op.get("value") or "")
                for s in state.values():
                    s[fld] = value
            elif typ == "number":
                try:
                    cur, step = Decimal(str(op.get("start") or "1")), Decimal(str(op.get("step") or "1"))
                    pad = int(op.get("pad") or 0)
                except (InvalidOperation, ValueError):
                    raise ApiError("Start, step and zero-pad must be numbers")
                if not (cur.is_finite() and step.is_finite()) or abs(cur) > 10 ** 9 or abs(step) > 10 ** 9:
                    raise ApiError("Start and step are out of range")
                if not 0 <= pad <= 12:
                    raise ApiError("Zero-pad must be between 0 and 12")
                for f in files:   # already in natural filename order
                    state[f["id"]][fld] = fmt_number(cur, pad)
                    cur += step
            elif typ == "regex":
                find = op.get("find")
                if not find:
                    raise ApiError("Regex 'find' is empty")
                if len(find) > MAX_REGEX_CHARS:
                    raise ApiError("Regex is too long")
                try:
                    rx = re.compile(find, re.I if op.get("ignore_case") else 0)
                except re.error as e:
                    raise ApiError(f"Invalid regex: {e}")
                repl = str(op.get("replace") or "")
                for s in state.values():
                    try:
                        s[fld] = rx.sub(repl, s.get(fld, ""))
                    except (re.error, IndexError) as e:
                        raise ApiError(f"Invalid replacement: {e}")
            else:
                raise ApiError(f"Unknown operation: {typ}")
        out = {}
        for f in files:
            before, after = f["fields"], state[f["id"]]
            out[f["id"]] = {k: v for k, v in after.items() if before.get(k, "") != v}
        return out

    @bp.post("/api/cbz/bulk")
    def bulk():
        body = request.get_json(silent=True) or {}
        ids, ops, preview = body.get("ids"), body.get("ops"), bool(body.get("preview"))
        if not (isinstance(ids, list) and all(isinstance(i, str) for i in ids) and isinstance(ops, list)
                and all(isinstance(o, dict) for o in ops)):
            raise ApiError("Body must be {ids: [...], ops: [...], preview: bool}")
        if len(ids) > MAX_BULK_FILES or len(ops) > MAX_OPS:
            raise ApiError("Too many files or operations")
        files = [describe(i) for i in dict.fromkeys(ids)]
        files.sort(key=lambda f: natural_key(f["name"]))
        bad = [f["name"] for f in files if f["error"]]
        if bad and not preview:
            raise ApiError("Cannot edit invalid files: " + ", ".join(bad))
        changes = plan(files, ops)
        if preview:
            return jsonify([{"id": f["id"], "name": f["name"],
                             "changes": [{"field": k, "old": f["fields"].get(k, ""), "new": v}
                                         for k, v in changes[f["id"]].items()]} for f in files])
        with lock:
            for f in files:
                if changes[f["id"]]:
                    write(file_path(f["id"]), changes[f["id"]])
        return jsonify([describe(f["id"]) for f in files])

    # ------------------------------------------------------------ download
    @bp.get("/api/cbz/files/<file_id>/download")
    def download_one(file_id: str):
        p = file_path(file_id)
        return send_file(p, as_attachment=True, download_name=p.name, mimetype=CBZ_MIMETYPE)

    @bp.post("/api/cbz/download")
    def download_many():
        body = request.get_json(silent=True) or {}
        ids = body.get("ids")
        if not (isinstance(ids, list) and ids and all(isinstance(i, str) for i in ids)):
            raise ApiError("Body must be {\"ids\": [...]}")
        if len(ids) > MAX_BULK_FILES:
            raise ApiError("Too many files")
        fd, tmp = tempfile.mkstemp(suffix=".zip", dir=scratch)
        os.close(fd)
        used: set[str] = set()
        try:
            with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
                for i in dict.fromkeys(ids):
                    p = file_path(i)
                    name, n = p.name, 1
                    while name in used:
                        name = f"{p.stem} ({n}){p.suffix}"
                        n += 1
                    used.add(name)
                    z.write(p, name)
        except BaseException:
            os.unlink(tmp)
            raise

        @after_this_request
        def _cleanup(resp):
            resp.call_on_close(lambda: os.path.exists(tmp) and os.unlink(tmp))
            return resp

        return send_file(tmp, as_attachment=True, download_name="edited-cbz.zip", mimetype="application/zip")

    return bp
