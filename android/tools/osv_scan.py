#!/usr/bin/env python3
"""Dependency vulnerability scan of the Gradle dependency graph (spec 7.7/7.8) with OSV-Scanner.

Input: android/gradle/verification-metadata.xml, which OSV-Scanner parses natively as a Maven lockfile. It lists
every artifact the CI task set resolves: the app's runtime libraries AND the build/test toolchain (AGP, R8,
lint, Kotlin compiler, Robolectric...). Optionally the `:app:dependencies --configuration releaseRuntimeClasspath`
report labels each finding "runtime (ships in APK)" or "build/test only".

Policy (documented in README "Android app"):
  * HIGH or CRITICAL fails the job; so does a finding with no severity at all (fail closed).
    Severity = the group's CVSS max_severity (>= 9.0 CRITICAL, >= 7.0 HIGH, >= 4.0 MEDIUM, else LOW) and, when
    OSV has no score, the advisory's database_specific.severity label.
  * MEDIUM/LOW are printed as warnings.
  * android/osv-allowlist.toml can suppress a finding by id or alias; every entry needs a reason and an expiry
    date; an expired or incomplete entry fails the job.
  * The scanner must report a non-zero package count, and any scanner error fails the job.

Usage: osv_scan.py --scanner PATH --lockfile verification-metadata.xml --allowlist osv-allowlist.toml
                   [--runtime-deps release-runtime-deps.txt]
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime
import json
import re
import subprocess
import sys
import tomllib

BLOCKING = {"CRITICAL", "HIGH", "UNKNOWN"}


@dataclasses.dataclass(frozen=True)
class Finding:
    package: str
    version: str
    ids: tuple[str, ...]
    aliases: tuple[str, ...]
    severity: str
    score: float | None
    summary: str


@dataclasses.dataclass(frozen=True)
class AllowEntry:
    id: str
    reason: str
    expires: datetime.date


@dataclasses.dataclass
class Row:
    finding: Finding
    action: str  # BLOCK | WARN | ALLOWLISTED
    scope: str


@dataclasses.dataclass
class Decision:
    rows: list[Row]

    @property
    def blocked(self) -> bool:
        return any(r.action == "BLOCK" for r in self.rows)


def severity_from(score: float | None, label: str | None) -> str:
    if score is not None:
        if score >= 9.0:
            return "CRITICAL"
        if score >= 7.0:
            return "HIGH"
        if score >= 4.0:
            return "MEDIUM"
        return "LOW"
    if label:
        label = label.upper()
        return {"MODERATE": "MEDIUM"}.get(label, label) if label in {"CRITICAL", "HIGH", "MODERATE", "MEDIUM", "LOW"} \
            else "UNKNOWN"
    return "UNKNOWN"


_RANK = {"LOW": 0, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}


def findings(report: dict) -> list[Finding]:
    out = []
    for result in report.get("results") or []:
        for pkg in result.get("packages") or []:
            name = pkg["package"]["name"]
            version = pkg["package"].get("version", "")
            vulns = {v["id"]: v for v in pkg.get("vulnerabilities") or []}
            for group in pkg.get("groups") or []:
                ids = tuple(group.get("ids") or [])
                aliases = tuple(sorted(set(group.get("aliases") or []) | set(ids)
                                       | {a for i in ids for a in vulns.get(i, {}).get("aliases", [])}))
                raw = (group.get("max_severity") or "").strip()
                score = float(raw) if re.fullmatch(r"\d+(\.\d+)?", raw) else None
                labels = [vulns[i].get("database_specific", {}).get("severity") for i in ids if i in vulns]
                labels = [lb for lb in labels if isinstance(lb, str)]
                label = max(labels, key=lambda lb: _RANK.get(severity_from(None, lb), -1)) if labels else None
                summary = next((vulns[i].get("summary", "") for i in ids if i in vulns), "")
                out.append(Finding(name, version, ids, aliases, severity_from(score, label), score, summary))
    return out


def parse_gradle_dependencies(text: str) -> set[str]:
    """`group:artifact` set from a `gradle dependencies` tree (constraints `(c)` and unresolved `(n)` skipped)."""
    deps = set()
    for line in text.splitlines():
        m = re.match(r"^[|\\+\- ]*[+\\]--- (.+)$", line)
        if not m:
            continue
        entry = m.group(1).strip()
        if entry.startswith("project ") or entry.endswith("(c)") or entry.endswith("(n)"):
            continue
        coords = entry.split(" ")[0].split(":")
        if len(coords) >= 2:
            deps.add(f"{coords[0]}:{coords[1]}")
    return deps


def load_allowlist(text: str, today: datetime.date) -> tuple[list[AllowEntry], list[str]]:
    try:
        data = tomllib.loads(text)
    except tomllib.TOMLDecodeError as e:
        return [], [f"allowlist is not valid TOML: {e}"]
    entries, problems = [], []
    unknown_top = set(data) - {"ignore"}
    if unknown_top:
        problems.append(f"allowlist: unknown top-level key(s) {sorted(unknown_top)}")
    for i, raw in enumerate(data.get("ignore", []), 1):
        where = f"allowlist entry {i}"
        extra = set(raw) - {"id", "reason", "expires"}
        if extra:
            problems.append(f"{where}: unknown key(s) {sorted(extra)}")
        vid = str(raw.get("id", "")).strip()
        reason = str(raw.get("reason", "")).strip()
        expires = raw.get("expires")
        if isinstance(expires, str):
            try:
                expires = datetime.date.fromisoformat(expires)
            except ValueError:
                expires = None
        if isinstance(expires, datetime.datetime):
            expires = expires.date()
        if not vid:
            problems.append(f"{where}: id is required")
        if len(reason) < 10:
            problems.append(f"{where} ({vid}): reason is required (a real justification)")
        if not isinstance(expires, datetime.date):
            problems.append(f"{where} ({vid}): expires = YYYY-MM-DD is required")
            continue
        if expires < today:
            problems.append(f"{where} ({vid}): expired on {expires}; re-assess, upgrade or renew with a new reason")
            continue
        if vid and len(reason) >= 10:
            entries.append(AllowEntry(vid, reason, expires))
    return entries, problems


def decide(found: list[Finding], allow: list[AllowEntry], runtime: set[str] | None) -> Decision:
    allowed_ids = {e.id for e in allow}
    rows = []
    for f in found:
        if runtime is None:
            scope = "unknown scope"
        else:
            scope = "runtime (ships in APK)" if f.package in runtime else "build/test only"
        if allowed_ids & (set(f.ids) | set(f.aliases)):
            action = "ALLOWLISTED"
        elif f.severity in BLOCKING:
            action = "BLOCK"
        else:
            action = "WARN"
        rows.append(Row(f, action, scope))
    return Decision(rows)


def scanned_package_count(stderr: str) -> int | None:
    counts = [int(n) for n in re.findall(r"found (\d+) packages?", stderr)]
    return sum(counts) if counts else None


def scanner_exit_problem(code: int) -> str | None:
    # OSV-Scanner: 0 = no vulnerabilities, 1 = vulnerabilities found, anything else = error.
    return None if code in (0, 1) else f"osv-scanner failed with exit code {code} (not a scan result)"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="OSV dependency scan with a severity threshold and an allowlist.")
    ap.add_argument("--scanner", required=True)
    ap.add_argument("--lockfile", required=True)
    ap.add_argument("--allowlist", required=True)
    ap.add_argument("--runtime-deps")
    args = ap.parse_args(argv)

    problems = []
    with open(args.allowlist, encoding="utf-8") as fh:
        allow, allow_problems = load_allowlist(fh.read(), datetime.date.today())
    problems += allow_problems

    runtime = None
    if args.runtime_deps:
        with open(args.runtime_deps, encoding="utf-8") as fh:
            runtime = parse_gradle_dependencies(fh.read())
        if not runtime:
            problems.append(f"no dependencies parsed from {args.runtime_deps}")
        print(f"release runtime classpath: {len(runtime)} modules")

    version = subprocess.run([args.scanner, "--version"], capture_output=True, text=True).stdout.splitlines()
    print(version[0] if version else "osv-scanner version unknown")
    proc = subprocess.run([args.scanner, "scan", "source", "--format", "json", "--lockfile", args.lockfile],
                          capture_output=True, text=True)
    sys.stderr.write(proc.stderr)
    exit_problem = scanner_exit_problem(proc.returncode)
    count = scanned_package_count(proc.stderr)
    print(f"packages scanned: {count}")
    if exit_problem:
        problems.append(exit_problem)
    if not count:
        problems.append("osv-scanner reported no scanned packages (lockfile not parsed?)")

    rows = []
    if not exit_problem:
        try:
            found = findings(json.loads(proc.stdout))
        except (json.JSONDecodeError, KeyError) as e:
            problems.append(f"cannot parse osv-scanner JSON: {e}")
            found = []
        rows = decide(found, allow, runtime).rows

    print(f"findings: {len(rows)} (threshold: HIGH/CRITICAL/unknown severity fail; allowlist entries: {len(allow)})")
    for r in sorted(rows, key=lambda r: (r.action != "BLOCK", r.finding.package)):
        f = r.finding
        score = f"{f.score:.1f}" if f.score is not None else "-"
        line = (f"{r.action:11} {f.severity:8} cvss={score:4} {f.package}:{f.version} [{r.scope}] "
                f"{','.join(f.ids)} ({','.join(a for a in f.aliases if a.startswith('CVE-')) or 'no CVE'}) {f.summary}")
        print(line)
        if r.action == "BLOCK":
            print(f"::error::OSV {f.severity}: {f.package}:{f.version} {','.join(f.ids)} [{r.scope}]")
        elif r.action == "WARN":
            print(f"::warning::OSV {f.severity}: {f.package}:{f.version} {','.join(f.ids)} [{r.scope}]")

    for p in problems:
        print(f"::error::{p}")
    if problems or any(r.action == "BLOCK" for r in rows):
        print("OSV scan FAILED")
        return 1
    print("OSV scan OK: no HIGH/CRITICAL findings outside the allowlist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
