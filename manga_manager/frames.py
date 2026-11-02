"""Decorative frames drawn around the covers of mature categories on the dashboard (#7).

A frame is one transparent image laid over the cover. The cover sits in a rectangular
"window" inside the image; everything outside the window (and anything drawn over it, like
hands) is the frame. Your own frames go in <config>/frames/ (#13: as many as you like; the
dashboard picks one of them at random for every mature cover, each time the page loads):

    frames/adult/*.png    frames for the Adult category
    frames/hentai/*.png   frames for the Hentai category
    frames/mature/*.png   frames for every framed category without frames of its own

A single file next to those folders (frames/adult.png, frames/mature.png, the 2.2.0 layout)
counts as one more frame of that group. PNG, WebP, GIF or SVG all work. Without a frame of
your own the built-in frame is used. On first start the built-in frame (mature/default.png)
and a guide image (frame-guide.png, the cover window drawn in) are copied into that folder,
so there is a file to edit, copy or replace. The guide is never used as a frame.
"""
from __future__ import annotations

import logging
import re
import shutil
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

log = logging.getLogger(__name__)

BUILTIN_DIR = Path(__file__).parent / "static" / "frames"
BUILTIN_NAME = "mature.png"
DEFAULT_GROUP = "mature"
INSTALLED_NAME = "default.png"   # the built-in frame as copied into frames/mature/
# Window of the built-in frame (top, right, bottom, left, in pixels), also the
# default for your own frame when it has the same size as the built-in one.
BUILTIN_WINDOW = (50, 400, 50, 400)
GUIDE_NAME = "frame-guide.png"
EXTENSIONS = (".png", ".webp", ".gif", ".svg")
MIME = {".png": "image/png", ".webp": "image/webp", ".gif": "image/gif", ".svg": "image/svg+xml"}


@dataclass
class Frame:
    path: Path
    width: int
    height: int
    window: tuple[int, int, int, int]

    @property
    def columns(self) -> int:
        """A frame wider than tall (like the built-in one) gets a double-width card."""
        return 2 if self.width > self.height else 1

    @property
    def mimetype(self) -> str:
        return MIME.get(self.path.suffix.lower(), "application/octet-stream")

    def css(self) -> dict:
        """Percentages the dashboard uses to place the cover inside the frame."""
        t, r, b, l = self.window
        return {
            "box": f"{self.width} / {self.height}",
            "left": f"{l / self.width * 100:.4f}%",
            "top": f"{t / self.height * 100:.4f}%",
            "width": f"{(self.width - l - r) / self.width * 100:.4f}%",
            "height": f"{(self.height - t - b) / self.height * 100:.4f}%",
        }


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def _image_size(path: Path) -> tuple[int, int] | None:
    if path.suffix.lower() == ".svg":
        text = path.read_text(encoding="utf-8", errors="replace")[:4000]
        m = re.search(r'viewBox="\s*[-\d.]+[\s,]+[-\d.]+[\s,]+([\d.]+)[\s,]+([\d.]+)', text)
        return (round(float(m.group(1))), round(float(m.group(2)))) if m else None
    try:
        with Image.open(path) as img:
            return img.size
    except OSError as exc:
        log.warning("Frame %s is not a readable image: %s", path, exc)
        return None


class FrameStore:
    def __init__(self, user_dir: Path, categories: list[str], window: tuple[int, int, int, int] | None = None,
                 builtin: Path | None = None, builtin_window: tuple[int, int, int, int] = BUILTIN_WINDOW):
        self.user_dir = user_dir
        self.categories = [c for c in categories if c]
        self.window = window
        self.builtin = builtin or BUILTIN_DIR / BUILTIN_NAME
        self.builtin_window = builtin_window

    def install_defaults(self) -> None:
        """Create the frames folder once, with the built-in frame and the guide, so they can be replaced.

        A frames folder from 2.2.0 (mature.png at the top) only gets the empty mature/ folder added;
        nothing of the user's is moved or overwritten.
        """
        if not self.categories:
            return
        group = self.user_dir / DEFAULT_GROUP
        try:
            if not self.user_dir.exists():
                group.mkdir(parents=True)
                shutil.copyfile(self.builtin, group / INSTALLED_NAME)
                guide = self.builtin.parent / GUIDE_NAME
                if guide.is_file():
                    shutil.copyfile(guide, self.user_dir / GUIDE_NAME)
                log.info("Copied the default cover frame to %s; add your own frames to that folder", group)
            elif not group.exists():
                group.mkdir()
                log.info("Created %s: every image in it is a cover frame, one is picked at random", group)
        except OSError as exc:
            log.warning("Could not create %s for the cover frames: %s", self.user_dir, exc)

    def _files(self, stem: str) -> list[Path]:
        """frames/<stem>.<ext> plus every image in frames/<stem>/, sorted by name."""
        found = [self.user_dir / f"{stem}{ext}" for ext in EXTENSIONS]
        found = [p for p in found if p.is_file()]
        folder = self.user_dir / stem
        if folder.is_dir():
            found += sorted((p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in EXTENSIONS
                             and not p.name.startswith(".") and p.name != GUIDE_NAME),
                            key=lambda p: p.name.casefold())
        return found

    def frames_for(self, category: str) -> list[Frame]:
        """Every usable frame for a category (#13); the dashboard picks one at random per cover."""
        if slug(category) not in {slug(c) for c in self.categories}:
            return []
        for group in (slug(category), DEFAULT_GROUP):
            files = self._files(group)
            if files:  # your own frames, even when none of them is usable (the log says why)
                return [f for f in map(self._load, files) if f]
        f = self._load(self.builtin) if self.builtin.is_file() else None
        return [f] if f else []

    def for_category(self, category: str) -> Frame | None:
        """The first frame for a category (used by `check` and older callers)."""
        frames = self.frames_for(category)
        return frames[0] if frames else None

    def _load(self, path: Path) -> Frame | None:
        size = _image_size(path)
        if not size:
            return None
        if self.window:
            window = self.window
        elif path == self.builtin or size == _image_size(self.builtin):
            window = self.builtin_window
        else:
            log.warning("Frame %s is %sx%s, not the built-in size; set web.frame.window to place the cover",
                        path, *size)
            window = self.builtin_window
        t, r, b, l = window
        if l + r >= size[0] or t + b >= size[1]:
            log.warning("web.frame.window %s leaves no room for the cover in %s (%sx%s)", window, path, *size)
            return None
        return Frame(path, size[0], size[1], window)
