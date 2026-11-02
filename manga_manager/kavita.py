"""Kavita client (REST API, replaces the direct SQLite queries)."""
from __future__ import annotations

import base64
import logging
import ntpath
import posixpath
from dataclasses import dataclass

import requests

from .comicinfo import normalize_title, stripped_title

log = logging.getLogger(__name__)


class KavitaError(Exception):
    pass


@dataclass
class KavitaSeries:
    id: int
    name: str
    library_id: int
    cover_locked: bool
    folder_path: str = ""
    original_name: str = ""
    localized_name: str = ""

    @property
    def folder_name(self) -> str:
        """Last path component of the series folder (works for Windows and POSIX paths)."""
        path = (self.folder_path or "").rstrip("\\/")
        if not path:
            return ""
        return ntpath.basename(path) if "\\" in path else posixpath.basename(path)

    @classmethod
    def from_dto(cls, dto: dict) -> "KavitaSeries":
        return cls(
            id=int(dto["id"]),
            name=dto.get("name") or "",
            library_id=int(dto.get("libraryId") or 0),
            cover_locked=bool(dto.get("coverImageLocked")),
            folder_path=dto.get("folderPath") or dto.get("lowestFolderPath") or "",
            original_name=dto.get("originalName") or "",
            localized_name=dto.get("localizedName") or "",
        )


class KavitaClient:
    def __init__(self, base_url: str, api_key: str, plugin_name: str = "MoveManga", timeout: int = 60):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.plugin_name = plugin_name
        self.timeout = timeout
        self.session = requests.Session()
        self._token: str | None = None
        self._upload_by_file_supported: bool | None = None

    # -- auth / transport ----------------------------------------------------
    def authenticate(self) -> None:
        try:
            resp = self.session.post(
                f"{self.base_url}/api/Plugin/authenticate",
                params={"apiKey": self.api_key, "pluginName": self.plugin_name},
                timeout=self.timeout,
            )
        except requests.RequestException as exc:
            raise KavitaError(f"Kavita is unreachable at {self.base_url}: {exc}") from exc
        if not resp.ok:
            raise KavitaError(f"Kavita authentication failed (HTTP {resp.status_code}). Check kavita.api_key.")
        token = (resp.json() or {}).get("token")
        if not token:
            raise KavitaError("Kavita authentication returned no token.")
        self._token = token

    def _request(self, method: str, path: str, *, retry_auth: bool = True, **kwargs) -> requests.Response:
        if not self._token:
            self.authenticate()
        headers = kwargs.pop("headers", {}) or {}
        headers["Authorization"] = f"Bearer {self._token}"
        try:
            resp = self.session.request(method, f"{self.base_url}{path}", headers=headers,
                                        timeout=kwargs.pop("timeout", self.timeout), **kwargs)
        except requests.RequestException as exc:
            raise KavitaError(f"Kavita request {method} {path} failed: {exc}") from exc
        if resp.status_code == 401 and retry_auth:
            log.debug("Kavita token expired, re-authenticating")
            self._token = None
            return self._request(method, path, retry_auth=False, headers=headers, **kwargs)
        return resp

    @staticmethod
    def _check(resp: requests.Response, what: str) -> requests.Response:
        if not resp.ok:
            raise KavitaError(f"Kavita {what} failed: HTTP {resp.status_code} {resp.text[:300]}")
        return resp

    # -- series --------------------------------------------------------------
    def all_series(self) -> list[KavitaSeries]:
        """Every series the API user can see (PageSize=0 means 'no paging' in Kavita)."""
        body = {
            "statements": [],
            "combination": 1,
            "sortOptions": {"sortField": 1, "isAscending": True},
            "limitTo": 0,
        }
        resp = self._check(
            self._request("POST", "/api/Series/all-v2", params={"PageNumber": 1, "PageSize": 0}, json=body),
            "listing series",
        )
        return [KavitaSeries.from_dto(dto) for dto in resp.json()]

    def scan_library(self, library_id: int, force: bool = False) -> None:
        self._check(
            self._request("POST", "/api/Library/scan", params={"libraryId": library_id, "force": str(force).lower()}),
            f"scan of library {library_id}",
        )

    def scan_series(self, library_id: int, series_id: int) -> None:
        self._check(
            self._request("POST", "/api/Series/scan",
                          json={"libraryId": library_id, "seriesId": series_id, "forceUpdate": False}),
            f"scan of series {series_id}",
        )

    # -- covers --------------------------------------------------------------
    def upload_series_cover(self, series_id: int, png_bytes: bytes, lock: bool = True) -> str:
        """Set a series cover. Returns the method used ('file' or 'base64').

        Kavita >= 0.9.1 supports staging the image as a multipart upload
        (/api/Upload/upload-by-file) and then referencing it by file name, which avoids
        sending a large base64 blob. Older versions only accept base64, so we fall back.
        """
        if self._upload_by_file_supported is not False:
            resp = self._request(
                "POST", "/api/Upload/upload-by-file",
                files={"file": (f"series{series_id}.png", png_bytes, "image/png")},
            )
            if resp.status_code in (404, 405):
                log.info("Kavita has no upload-by-file endpoint (older than 0.9.1); using base64 uploads")
                self._upload_by_file_supported = False
            else:
                self._check(resp, f"staging cover for series {series_id}")
                self._upload_by_file_supported = True
                file_name = resp.text.strip().strip('"')
                self._check(
                    self._request("POST", "/api/Upload/series",
                                  json={"id": series_id, "fileName": file_name, "url": "", "lockCover": lock}),
                    f"setting cover for series {series_id}",
                )
                return "file"

        payload = {"id": series_id, "url": base64.b64encode(png_bytes).decode("ascii"), "lockCover": lock}
        self._check(self._request("POST", "/api/Upload/series", json=payload), f"uploading cover for series {series_id}")
        return "base64"


class KavitaIndex:
    """Lookup helper over one snapshot of Kavita's series list."""

    def __init__(self, series: list[KavitaSeries]):
        self.series = series

    def find(self, library_id: int, *titles: str | None, folder_name: str | None = None) -> KavitaSeries | None:
        """Exact name, loose name, folder name, then decoration-stripped name (only if unambiguous)."""
        candidates = [s for s in self.series if s.library_id == library_id]
        wanted_exact = {t.casefold() for t in titles if t}
        wanted_loose = {normalize_title(t) for t in titles if t} - {""}

        def names(s: KavitaSeries) -> list[str]:
            return [n for n in (s.name, s.original_name, s.localized_name) if n]

        for s in candidates:
            if {n.casefold() for n in names(s)} & wanted_exact:
                return s
        for s in candidates:
            if {normalize_title(n) for n in names(s)} & wanted_loose:
                return s
        if folder_name:
            key = normalize_title(folder_name)
            for s in candidates:
                if key and normalize_title(s.folder_name) == key:
                    return s

        wanted_stripped = {stripped_title(t) for t in [*titles, folder_name] if t} - {""}
        hits = [s for s in candidates
                if {stripped_title(n) for n in [*names(s), s.folder_name] if n} & wanted_stripped]
        return hits[0] if len(hits) == 1 else None
