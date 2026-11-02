"""Helpers for reading ComicInfo.xml from CBZ files and for fuzzy title matching."""
from __future__ import annotations

import logging
import os
import re
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

log = logging.getLogger(__name__)


@dataclass
class ComicInfo:
    series: str | None = None
    number: str | None = None
    title: str | None = None


def read_comicinfo(path: Path) -> ComicInfo:
    """Read Series/Number/Title from ComicInfo.xml inside a CBZ. Never raises."""
    try:
        with zipfile.ZipFile(path) as zf:
            name = next((n for n in zf.namelist() if n.lower().rsplit("/", 1)[-1] == "comicinfo.xml"), None)
            if not name:
                return ComicInfo()
            root = ElementTree.fromstring(zf.read(name))
    except (zipfile.BadZipFile, ElementTree.ParseError, OSError, KeyError) as exc:
        log.warning("Could not read ComicInfo.xml from %s: %s", path.name, exc)
        return ComicInfo()

    def text(tag: str) -> str | None:
        el = root.find(tag)
        return el.text.strip() if el is not None and el.text and el.text.strip() else None

    return ComicInfo(series=text("Series"), number=text("Number"), title=text("Title"))


_CHAPTER_IN_NAME = re.compile(r"(?:ch(?:apter)?\.?\s*)(\d+(?:\.\d+)?)", re.IGNORECASE)
_ANY_NUMBER = re.compile(r"(\d+(?:\.\d+)?)")


def chapter_label(path: Path) -> str:
    """Best effort chapter number: ComicInfo <Number>, else parsed from the filename."""
    info = read_comicinfo(path)
    if info.number:
        return info.number
    stem = path.stem
    m = _CHAPTER_IN_NAME.search(stem) or _ANY_NUMBER.search(stem.split("_", 1)[-1])
    return m.group(1) if m else stem


def chapter_label_fast(path: Path) -> str:
    """Chapter number from the file name ("..._Chapter 12.cbz"), ComicInfo only as fallback.

    Used for the chapters already in a Kavita folder, which can be hundreds of files on
    a network share: opening every archive would make each run slow.
    """
    m = _CHAPTER_IN_NAME.search(path.stem)
    return m.group(1) if m else chapter_label(path)


def chapter_key(label: str) -> str:
    """Comparable form of a chapter label: "7", "7.0" and "007" are the same chapter."""
    try:
        n = float(label)
        return str(int(n)) if n.is_integer() else repr(n)
    except (TypeError, ValueError):
        return str(label).strip().casefold()


def chapter_sort_key(label: str) -> tuple[int, float, str]:
    try:
        return (0, float(label), label)
    except ValueError:
        return (1, 0.0, label.lower())


def normalize_title(title: str | None) -> str:
    """Loose comparison key: lower-case, accents stripped, only letters/digits.

    Mirrors the cleanTitle() logic of the old n8n workflow, but keeps non-latin
    letters (Japanese/Korean titles) instead of throwing them away.
    """
    if not title:
        return ""
    kept, base = [], ""
    for c in unicodedata.normalize("NFKD", title):
        if unicodedata.combining(c):
            # Drop accents on Latin letters (é -> e) but keep marks that change meaning
            # elsewhere, e.g. Japanese dakuten (だ must not become た).
            if base and ord(base) < 0x250:
                continue
        else:
            base = c
        kept.append(c)
    text = unicodedata.normalize("NFC", "".join(kept))
    return "".join(c for c in text.casefold() if c.isalnum())


# Bracketed decorations sources add to titles: (Official), [English], 〘Official〙, 【Mature】 ...
_BRACKETS = re.compile(r"\([^()]*\)|\[[^\[\]]*\]|\{[^{}]*\}|〘[^〘〙]*〙|【[^【】]*】|〔[^〔〕]*〕|「[^「」]*」|『[^『』]*』")


def stripped_title(title: str | None) -> str:
    """Loose key with all bracketed decorations removed.

    "Governess of a Tyrant (Official)" and "Governess of a Tyrant" give the same key.
    Only used as a last matching step, and only when it points to a single title.
    """
    if not title:
        return ""
    previous = None
    text = unicodedata.normalize("NFKC", title)
    while previous != text:  # handles nested brackets
        previous, text = text, _BRACKETS.sub(" ", text)
    key = normalize_title(text)
    return key or normalize_title(title)


def set_series_name(path: Path, series: str) -> bool:
    """Rewrite <Series> in a CBZ's ComicInfo.xml. Returns True when the file was changed.

    The archive is rebuilt next to the original and swapped in atomically, keeping every
    other entry (and its compression) as it was.
    """
    try:
        with zipfile.ZipFile(path) as zf:
            name = next((n for n in zf.namelist() if n.lower().rsplit("/", 1)[-1] == "comicinfo.xml"), None)
            if not name:
                return False
            root = ElementTree.fromstring(zf.read(name))
            el = root.find("Series")
            if el is not None and (el.text or "").strip() == series:
                return False
            if el is None:
                el = ElementTree.SubElement(root, "Series")
            el.text = series
            new_xml = b'<?xml version="1.0" encoding="utf-8"?>\n' + ElementTree.tostring(root, encoding="utf-8")
            tmp = path.with_name(path.name + ".tmp")
            with zipfile.ZipFile(tmp, "w") as out:
                for info in zf.infolist():
                    data = new_xml if info.filename == name else zf.read(info.filename)
                    out.writestr(info, data, compress_type=info.compress_type)
        os.replace(tmp, path)
        return True
    except (zipfile.BadZipFile, ElementTree.ParseError, OSError) as exc:
        log.warning("Could not update the series name in %s: %s", path.name, exc)
        try:
            path.with_name(path.name + ".tmp").unlink(missing_ok=True)
        except OSError:
            pass
        return False
