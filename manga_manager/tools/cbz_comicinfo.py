"""Read and write ComicInfo.xml inside CBZ (zip) archives (CBZ editor tab).

Archives are untrusted uploads: nothing is ever extracted to disk (entries are only streamed
into a new archive), entry names must be plain relative paths, and sizes are capped.
"""
from __future__ import annotations

import copy
import os
import shutil
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import PurePosixPath

# ComicInfo v2.1 element order. Pages is complex and left untouched.
FIELDS = [
    "Title", "Series", "Number", "Count", "Volume", "AlternateSeries",
    "AlternateNumber", "AlternateCount", "Summary", "Notes", "Year", "Month",
    "Day", "Writer", "Penciller", "Inker", "Colorist", "Letterer",
    "CoverArtist", "Editor", "Translator", "Publisher", "Imprint", "Genre",
    "Tags", "Web", "PageCount", "LanguageISO", "Format", "BlackAndWhite",
    "Manga", "Characters", "Teams", "Locations", "ScanInformation",
    "StoryArc", "StoryArcNumber", "SeriesGroup", "AgeRating", "Pages",
    "CommunityRating", "MainCharacterOrTeam", "Review", "GTIN",
]
SIMPLE_FIELDS = [f for f in FIELDS if f != "Pages"]
_ORDER = {name: i for i, name in enumerate(FIELDS)}

MAX_COMICINFO_BYTES = 2 * 1024 * 1024         # a real ComicInfo.xml is a few KB
MAX_ENTRIES = 20000                           # pages plus extras of any sane chapter
MAX_UNCOMPRESSED_BYTES = 16 * 1024 ** 3       # zip-bomb guard on the declared total
MAX_VALUE_CHARS = 100_000


class UnsafeArchive(ValueError):
    """The archive is not something we are willing to read or rewrite."""


def unsafe_name(name: str) -> bool:
    """Zip-slip check: absolute paths, drive letters, backslashes tricks and '..' parts."""
    if not name or "\x00" in name:
        return True
    flat = name.replace("\\", "/")
    if flat.startswith("/") or (len(flat) > 1 and flat[1] == ":"):
        return True
    return ".." in PurePosixPath(flat).parts


def check_archive(zf: zipfile.ZipFile) -> None:
    infos = zf.infolist()
    if len(infos) > MAX_ENTRIES:
        raise UnsafeArchive("Archive has too many entries")
    if sum(i.file_size for i in infos) > MAX_UNCOMPRESSED_BYTES:
        raise UnsafeArchive("Archive is too large when unpacked")
    for i in infos:
        if unsafe_name(i.filename):
            raise UnsafeArchive(f"Unsafe path in archive: {i.filename!r}")


def _find_entry(zf: zipfile.ZipFile):
    """Return the ComicInfo.xml ZipInfo, preferring one at the archive root."""
    matches = [i for i in zf.infolist()
               if not i.is_dir() and os.path.basename(i.filename).lower() == "comicinfo.xml"]
    matches.sort(key=lambda i: i.filename.count("/"))
    return matches[0] if matches else None


def _read_entry(zf: zipfile.ZipFile, entry: zipfile.ZipInfo) -> bytes:
    if entry.file_size > MAX_COMICINFO_BYTES:
        raise UnsafeArchive("ComicInfo.xml is unreasonably large")
    with zf.open(entry) as fh:
        data = fh.read(MAX_COMICINFO_BYTES + 1)   # don't trust the declared size
    if len(data) > MAX_COMICINFO_BYTES:
        raise UnsafeArchive("ComicInfo.xml is unreasonably large")
    return data


def _parse(data: bytes) -> ET.Element:
    if b"<!DOCTYPE" in data[:4096].upper() or b"<!ENTITY" in data.upper():
        raise ET.ParseError("DTDs and entities are not allowed")
    return ET.fromstring(data)


def read_info(path: str) -> dict:
    """Return {'has_info': bool, 'fields': {name: text}, 'error': str|None}."""
    try:
        with zipfile.ZipFile(path) as zf:
            check_archive(zf)
            entry = _find_entry(zf)
            if entry is None:
                return {"has_info": False, "fields": {}, "error": None}
            root = _parse(_read_entry(zf, entry))
    except zipfile.BadZipFile:
        return {"has_info": False, "fields": {}, "error": "Not a valid CBZ/zip file"}
    except UnsafeArchive as e:
        return {"has_info": False, "fields": {}, "error": str(e)}
    except ET.ParseError as e:
        return {"has_info": True, "fields": {}, "error": f"ComicInfo.xml is invalid: {e}"}
    fields = {}
    for child in root:
        if child.tag in SIMPLE_FIELDS:
            fields[child.tag] = (child.text or "").strip()
    return {"has_info": True, "fields": fields, "error": None}


def _clean(value) -> str:
    """Text for an XML element: no control characters XML 1.0 can't hold."""
    text = "" if value is None else str(value)
    if len(text) > MAX_VALUE_CHARS:
        raise ValueError(f"Value is longer than {MAX_VALUE_CHARS} characters")
    return "".join(ch for ch in text if ch in "\t\n\r" or ord(ch) >= 0x20 and ord(ch) not in (0xFFFE, 0xFFFF)
                   and not 0xD800 <= ord(ch) <= 0xDFFF)


def _apply(root: ET.Element, changes: dict) -> None:
    for name, value in changes.items():
        existing = root.find(name)
        value = _clean(value)
        if value == "":
            if existing is not None:
                root.remove(existing)
            continue
        if existing is None:
            existing = ET.SubElement(root, name)
        existing.text = value
    # Keep schema order; unknown elements go last in their original order.
    children = sorted(list(root), key=lambda c: _ORDER.get(c.tag, len(_ORDER)))
    for c in list(root):
        root.remove(c)
    root.extend(children)


def write_info(path: str, changes: dict) -> None:
    """Apply field changes to the archive's ComicInfo.xml (created if missing). Writes atomically."""
    for name in changes:
        if name not in SIMPLE_FIELDS:
            raise ValueError(f"Unknown field: {name}")

    with zipfile.ZipFile(path) as zin:
        check_archive(zin)
        entry = _find_entry(zin)
        root = _parse(_read_entry(zin, entry)) if entry is not None else ET.Element("ComicInfo")
        _apply(root, changes)
        ET.indent(root, space="  ")
        xml = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        target_name = entry.filename if entry is not None else "ComicInfo.xml"

        fd, tmp = tempfile.mkstemp(suffix=".cbz", dir=os.path.dirname(path))
        os.close(fd)
        try:
            with zipfile.ZipFile(tmp, "w") as zout:
                for info in zin.infolist():
                    if entry is not None and info.filename == entry.filename:
                        continue
                    new = copy.copy(info)
                    if info.is_dir():
                        zout.writestr(new, b"")
                        continue
                    with zin.open(info) as src, zout.open(new, "w", force_zip64=info.file_size > 0x7FFFFFFF) as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
                zout.writestr(target_name, xml, compress_type=zipfile.ZIP_DEFLATED)
        except BaseException:
            os.unlink(tmp)
            raise
    os.replace(tmp, path)
