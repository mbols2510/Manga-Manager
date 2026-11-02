"""Suwayomi client (GraphQL API, replaces the direct PostgreSQL queries)."""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import requests

from .comicinfo import normalize_title, stripped_title

log = logging.getLogger(__name__)

LIBRARY_QUERY = """
query MoveMangaLibrary {
  mangas(condition: {inLibrary: true}) {
    nodes {
      id
      title
      thumbnailUrl
      source { id displayName }
      categories { nodes { id name } }
    }
  }
}
"""


# Chapters Suwayomi still has to download (#8). ERROR/FINISHED entries are ignored by the caller.
DOWNLOAD_QUEUE_QUERY = """
query MoveMangaDownloadQueue {
  downloadStatus {
    queue {
      state
      manga { id title }
    }
  }
}
"""

# Queue states that mean "more chapters of this manga are on the way".
ACTIVE_DOWNLOAD_STATES = {"QUEUED", "DOWNLOADING"}


@dataclass
class ActiveDownloads:
    """Manga that still have chapters queued or downloading in Suwayomi."""
    ids: set[int] = field(default_factory=set)
    titles: set[str] = field(default_factory=set)       # normalize_title() of the title
    chapters: dict[int | str, int] = field(default_factory=dict)  # id (or title) -> chapters left

    def contains(self, manga: "SuwayomiManga") -> bool:
        return manga.id in self.ids or normalize_title(manga.title) in self.titles

    def left(self, manga: "SuwayomiManga") -> int:
        return self.chapters.get(manga.id) or self.chapters.get(normalize_title(manga.title), 0)


class SuwayomiError(Exception):
    pass


@dataclass
class SuwayomiManga:
    id: int
    title: str
    categories: list[str] = field(default_factory=list)
    source: str = ""
    thumbnail_url: str | None = None


class SuwayomiClient:
    def __init__(self, base_url: str, username: str = "", password: str = "", timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        if username:
            self.session.auth = (username, password)
        self.timeout = timeout

    # -- raw ---------------------------------------------------------------
    def graphql(self, query: str, variables: dict | None = None) -> dict:
        try:
            resp = self.session.post(
                f"{self.base_url}/api/graphql",
                json={"query": query, "variables": variables or {}},
                timeout=self.timeout,
            )
        except requests.RequestException as exc:
            raise SuwayomiError(f"Suwayomi is unreachable: {exc}") from exc
        if resp.status_code == 401:
            raise SuwayomiError("Suwayomi rejected the credentials (HTTP 401). Check suwayomi.username/password.")
        if not resp.ok:
            raise SuwayomiError(f"Suwayomi GraphQL returned HTTP {resp.status_code}: {resp.text[:300]}")
        payload = resp.json()
        if payload.get("errors"):
            raise SuwayomiError(f"Suwayomi GraphQL error: {payload['errors']}")
        return payload.get("data") or {}

    # -- high level --------------------------------------------------------
    def library(self) -> list[SuwayomiManga]:
        data = self.graphql(LIBRARY_QUERY)
        result = []
        for node in data.get("mangas", {}).get("nodes", []):
            result.append(
                SuwayomiManga(
                    id=int(node["id"]),
                    title=node.get("title") or "",
                    categories=[c["name"] for c in (node.get("categories") or {}).get("nodes", [])],
                    source=((node.get("source") or {}).get("displayName") or ""),
                    thumbnail_url=node.get("thumbnailUrl"),
                )
            )
        log.debug("Suwayomi library contains %d manga", len(result))
        return result

    def active_downloads(self) -> ActiveDownloads:
        """Manga with chapters still QUEUED or DOWNLOADING in Suwayomi's download queue."""
        data = self.graphql(DOWNLOAD_QUEUE_QUERY)
        result = ActiveDownloads()
        for entry in (data.get("downloadStatus") or {}).get("queue") or []:
            if str(entry.get("state") or "").upper() not in ACTIVE_DOWNLOAD_STATES:
                continue
            manga = entry.get("manga") or {}
            key: int | str
            if manga.get("id") is not None:
                key = int(manga["id"])
                result.ids.add(key)
            else:
                key = normalize_title(manga.get("title") or "")
            if manga.get("title"):
                result.titles.add(normalize_title(manga["title"]))
            result.chapters[key] = result.chapters.get(key, 0) + 1
        return result

    def download_thumbnail(self, manga_id: int) -> bytes | None:
        """Fetch the cover via Suwayomi's REST endpoint (used when the file isn't on disk)."""
        try:
            resp = self.session.get(f"{self.base_url}/api/v1/manga/{manga_id}/thumbnail", timeout=self.timeout)
        except requests.RequestException as exc:
            log.warning("Could not download thumbnail for Suwayomi manga %s: %s", manga_id, exc)
            return None
        if resp.ok and resp.content:
            return resp.content
        log.warning("Suwayomi returned HTTP %s for thumbnail of manga %s", resp.status_code, manga_id)
        return None


class SuwayomiIndex:
    """Lookup helper over one snapshot of the Suwayomi library."""

    def __init__(self, mangas: list[SuwayomiManga]):
        self.mangas = mangas
        self.by_id = {m.id: m for m in mangas}
        self._exact: dict[str, list[SuwayomiManga]] = {}
        self._loose: dict[str, list[SuwayomiManga]] = {}
        self._stripped: dict[str, list[SuwayomiManga]] = {}
        for m in mangas:
            self._exact.setdefault(m.title.casefold(), []).append(m)
            self._loose.setdefault(normalize_title(m.title), []).append(m)
            self._stripped.setdefault(stripped_title(m.title), []).append(m)

    def match(self, *titles: str | None, source: str | None = None) -> tuple[SuwayomiManga | None, str]:
        """Find a manga by title. Returns (manga, how) with how in exact/loose/stripped/none.

        exact and loose prefer a hit from `source` when a title exists more than once;
        stripped (decorations like "(Official)" removed) is only accepted when unambiguous.
        """
        for how, table, key_fn in (("exact", self._exact, str.casefold), ("loose", self._loose, normalize_title)):
            for title in titles:
                if not title:
                    continue
                hits = table.get(key_fn(title), [])
                if not hits:
                    continue
                if len(hits) > 1 and source:
                    same_source = [h for h in hits if normalize_title(h.source) == normalize_title(source)]
                    if same_source:
                        return same_source[0], how
                return hits[0], how
        for title in titles:
            hits = self._stripped.get(stripped_title(title), []) if title else []
            if len(hits) == 1:
                return hits[0], "stripped"
        return None, "none"

    def find(self, *titles: str | None, source: str | None = None) -> SuwayomiManga | None:
        return self.match(*titles, source=source)[0]
