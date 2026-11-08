#!/usr/bin/env python3
"""Downloads a CI tool pinned to an exact release and verifies its SHA-256 BEFORE anything is written as
executable. The checksums below were copied from each release's published checksum file
(osv-scanner_SHA256SUMS, actionlint_<v>_checksums.txt) and re-computed over the downloaded asset.

Usage: python3 fetch_pinned.py <osv-scanner|actionlint> <dest-dir>   (prints the executable's path)
To bump a tool: change version, url and sha256 together, and take the sha256 from the release's checksum file.
"""

from __future__ import annotations

import dataclasses
import hashlib
import io
import os
import sys
import tarfile
import urllib.request


@dataclasses.dataclass(frozen=True)
class Pinned:
    name: str
    version: str
    url: str
    sha256: str
    binary: str
    tar_member: str | None  # None: the asset is the binary itself


PINNED = {
    "osv-scanner": Pinned(
        "osv-scanner", "2.7.0",
        "https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_amd64",
        "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b",
        "osv-scanner", None),
    "actionlint": Pinned(
        "actionlint", "1.7.12",
        "https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz",
        "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8",
        "actionlint", "actionlint"),
}


class ChecksumError(Exception):
    pass


def fetch(spec: Pinned, dest_dir: str) -> str:
    with urllib.request.urlopen(spec.url, timeout=120) as resp:  # noqa: S310 (pinned https URL)
        data = resp.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != spec.sha256:
        raise ChecksumError(f"{spec.name} {spec.version}: sha256 {actual} != pinned {spec.sha256}")
    if spec.tar_member is not None:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
            member = tar.getmember(spec.tar_member)
            if not member.isfile():
                raise ChecksumError(f"{spec.name}: {spec.tar_member} is not a regular file in the archive")
            data = tar.extractfile(member).read()
    os.makedirs(dest_dir, exist_ok=True)
    path = os.path.join(dest_dir, spec.binary)
    with open(path, "wb") as fh:
        fh.write(data)
    os.chmod(path, 0o755)
    return path


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in PINNED:
        print(f"usage: fetch_pinned.py <{'|'.join(PINNED)}> <dest-dir>", file=sys.stderr)
        return 2
    spec = PINNED[argv[0]]
    try:
        path = fetch(spec, argv[1])
    except ChecksumError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1
    print(f"{spec.name} {spec.version} sha256 verified ({spec.sha256})", file=sys.stderr)
    print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
