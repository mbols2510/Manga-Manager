"""Self-test for osv_scan.py and fetch_pinned.py (fixture JSON, local file:// downloads; no network)."""

import datetime
import hashlib
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fetch_pinned  # noqa: E402
import osv_scan  # noqa: E402

TODAY = datetime.date(2026, 11, 8)


def vuln(vid, aliases=(), db_severity=None):
    v = {"id": vid, "aliases": list(aliases), "summary": f"summary of {vid}"}
    if db_severity:
        v["database_specific"] = {"severity": db_severity}
    return v


def report(*packages):
    return {"results": [{"source": {"path": "verification-metadata.xml", "type": "lockfile"},
                         "packages": list(packages)}]}


def package(name, version, vulns, groups):
    return {"package": {"name": name, "version": version, "ecosystem": "Maven"},
            "vulnerabilities": vulns, "groups": groups}


HIGH_RUNTIME = package("com.squareup.okio:okio", "3.0.0",
                       [vuln("GHSA-aaaa-bbbb-cccc", ["CVE-2023-0001"], "MODERATE")],
                       [{"ids": ["GHSA-aaaa-bbbb-cccc"], "aliases": ["GHSA-aaaa-bbbb-cccc", "CVE-2023-0001"],
                         "max_severity": "7.5"}])
LOW_TOOL = package("com.google.guava:guava", "31.1-jre",
                   [vuln("GHSA-low0-0000-0000", [], "LOW")],
                   [{"ids": ["GHSA-low0-0000-0000"], "aliases": ["GHSA-low0-0000-0000"], "max_severity": "3.3"}])
LABEL_ONLY_CRITICAL = package("org.bouncycastle:bcprov-jdk18on", "1.70",
                              [vuln("GHSA-crit-0000-0000", [], "CRITICAL")],
                              [{"ids": ["GHSA-crit-0000-0000"], "aliases": ["GHSA-crit-0000-0000"],
                                "max_severity": ""}])
UNKNOWN = package("io.netty:netty-codec", "4.1.0",
                  [vuln("OSV-2026-1")],
                  [{"ids": ["OSV-2026-1"], "aliases": ["OSV-2026-1"], "max_severity": ""}])

RUNTIME_DEPS = """
releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- androidx.core:core-ktx:1.19.1
|    +--- androidx.annotation:annotation:1.9.1 -> 1.10.0 (*)
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.1.0 -> 2.4.21
+--- com.squareup.okhttp3:okhttp:5.5.1
|    \\--- com.squareup.okio:okio:3.0.0
+--- androidx.compose:compose-bom:2026.09.00
|    \\--- androidx.compose.ui:ui:1.10.0 (c)
+--- androidx.compose.ui:ui -> 1.10.0
+--- org.example:not-resolved:1.0 (n)
\\--- project :core
"""


class SeverityTests(unittest.TestCase):
    def test_findings_and_severity(self):
        fs = osv_scan.findings(report(HIGH_RUNTIME, LOW_TOOL, LABEL_ONLY_CRITICAL, UNKNOWN))
        by_pkg = {f.package: f for f in fs}
        self.assertEqual(by_pkg["com.squareup.okio:okio"].severity, "HIGH")  # CVSS score wins over the label
        self.assertEqual(by_pkg["com.google.guava:guava"].severity, "LOW")
        self.assertEqual(by_pkg["org.bouncycastle:bcprov-jdk18on"].severity, "CRITICAL")
        self.assertEqual(by_pkg["io.netty:netty-codec"].severity, "UNKNOWN")

    def test_severity_from_score(self):
        self.assertEqual(osv_scan.severity_from(9.8, None), "CRITICAL")
        self.assertEqual(osv_scan.severity_from(7.0, None), "HIGH")
        self.assertEqual(osv_scan.severity_from(6.9, None), "MEDIUM")
        self.assertEqual(osv_scan.severity_from(None, "MODERATE"), "MEDIUM")
        self.assertEqual(osv_scan.severity_from(None, None), "UNKNOWN")


class DecideTests(unittest.TestCase):
    def decide(self, rep, allow=None, runtime=None):
        return osv_scan.decide(osv_scan.findings(rep), allow or [], runtime)

    def test_clean_passes(self):
        self.assertFalse(self.decide(report()).blocked)

    def test_high_blocks(self):
        d = self.decide(report(HIGH_RUNTIME))
        self.assertTrue(d.blocked)
        self.assertEqual(d.rows[0].action, "BLOCK")

    def test_low_warns_only(self):
        d = self.decide(report(LOW_TOOL))
        self.assertFalse(d.blocked)
        self.assertEqual(d.rows[0].action, "WARN")

    def test_unknown_severity_fails_closed(self):
        self.assertTrue(self.decide(report(UNKNOWN)).blocked)

    def test_label_only_critical_blocks(self):
        self.assertTrue(self.decide(report(LABEL_ONLY_CRITICAL)).blocked)

    def test_allowlisted_by_alias_passes(self):
        entry = osv_scan.AllowEntry("CVE-2023-0001", "com.squareup.okio:okio", "not reachable: we never call the vulnerable API", TODAY)
        d = self.decide(report(HIGH_RUNTIME), allow=[entry])
        self.assertFalse(d.blocked)
        self.assertEqual(d.rows[0].action, "ALLOWLISTED")

    def test_allowlist_is_scoped_to_its_package(self):
        entry = osv_scan.AllowEntry("CVE-2023-0001", "com.google.guava:guava", "build-time only, no fix yet", TODAY)
        self.assertTrue(self.decide(report(HIGH_RUNTIME), allow=[entry]).blocked)

    def test_runtime_label(self):
        runtime = osv_scan.parse_gradle_dependencies(RUNTIME_DEPS)
        d = self.decide(report(HIGH_RUNTIME, LOW_TOOL), runtime=runtime)
        scopes = {r.finding.package: r.scope for r in d.rows}
        self.assertEqual(scopes["com.squareup.okio:okio"], "runtime (ships in APK)")
        self.assertEqual(scopes["com.google.guava:guava"], "build/test only")


class AllowlistTests(unittest.TestCase):
    def test_empty_file_ok(self):
        self.assertEqual(osv_scan.load_allowlist("# nothing\n", TODAY), ([], []))

    def test_valid_entry(self):
        text = '[[ignore]]\nid = "GHSA-x"\npackage = "g:a"\nreason = "build-time only, no fix released yet"\nexpires = 2027-01-31\n'
        entries, problems = osv_scan.load_allowlist(text, TODAY)
        self.assertEqual(problems, [])
        self.assertEqual(entries[0].expires, datetime.date(2027, 1, 31))

    def test_missing_reason_rejected(self):
        _, problems = osv_scan.load_allowlist('[[ignore]]\nid = "GHSA-x"\npackage = "g:a"\nexpires = 2027-01-31\n', TODAY)
        self.assertTrue(any("reason" in p for p in problems))

    def test_missing_expiry_rejected(self):
        _, problems = osv_scan.load_allowlist('[[ignore]]\nid = "GHSA-x"\npackage = "g:a"\nreason = "because of reasons"\n', TODAY)
        self.assertTrue(any("expires" in p for p in problems))

    def test_expired_entry_rejected(self):
        text = '[[ignore]]\nid = "GHSA-x"\npackage = "g:a"\nreason = "build-time only, no fix"\nexpires = 2026-11-07\n'
        entries, problems = osv_scan.load_allowlist(text, TODAY)
        self.assertTrue(any("expired" in p for p in problems))
        self.assertEqual(entries, [])

    def test_missing_package_rejected(self):
        text = '[[ignore]]\nid = "GHSA-x"\nreason = "build-time only, no fix"\nexpires = 2027-01-01\n'
        entries, problems = osv_scan.load_allowlist(text, TODAY)
        self.assertTrue(any("package" in p for p in problems))
        self.assertEqual(entries, [])

    def test_unknown_key_rejected(self):
        text = '[[ignore]]\nid = "GHSA-x"\nreason = "build-time only"\nexpires = 2027-01-01\nseverity = "LOW"\n'
        _, problems = osv_scan.load_allowlist(text, TODAY)
        self.assertTrue(any("unknown key" in p for p in problems))

    def test_repo_allowlist_is_valid(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "osv-allowlist.toml")
        with open(path, encoding="utf-8") as fh:
            _, problems = osv_scan.load_allowlist(fh.read(), datetime.date.today())
        self.assertEqual(problems, [])


class GradleDepsTests(unittest.TestCase):
    def test_parse(self):
        deps = osv_scan.parse_gradle_dependencies(RUNTIME_DEPS)
        self.assertIn("androidx.core:core-ktx", deps)
        self.assertIn("androidx.annotation:annotation", deps)
        self.assertIn("org.jetbrains.kotlin:kotlin-stdlib", deps)
        self.assertIn("androidx.compose.ui:ui", deps)
        self.assertNotIn("org.example:not-resolved", deps)
        self.assertFalse(any(d.startswith("project") for d in deps))


class ScannerOutputTests(unittest.TestCase):
    def test_package_count_parsed(self):
        err = "Scanned /x/android/gradle/verification-metadata.xml file and found 564 packages\n"
        self.assertEqual(osv_scan.scanned_package_count(err), 564)
        self.assertIsNone(osv_scan.scanned_package_count("nothing here"))

    def test_scanner_error_exit_fails(self):
        self.assertTrue(osv_scan.scanner_exit_problem(127))
        self.assertTrue(osv_scan.scanner_exit_problem(128))
        self.assertIsNone(osv_scan.scanner_exit_problem(0))
        self.assertIsNone(osv_scan.scanner_exit_problem(1))

    def test_json_roundtrip(self):
        fs = osv_scan.findings(json.loads(json.dumps(report(HIGH_RUNTIME))))
        self.assertEqual(fs[0].ids, ("GHSA-aaaa-bbbb-cccc",))
        self.assertIn("CVE-2023-0001", fs[0].aliases)


class FetchPinnedTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.src = os.path.join(self.tmp.name, "src")
        os.mkdir(self.src)

    def _file(self, name, data):
        path = os.path.join(self.src, name)
        with open(path, "wb") as fh:
            fh.write(data)
        return path

    def test_binary_with_matching_checksum(self):
        data = b"#!/bin/sh\necho hi\n"
        path = self._file("tool", data)
        spec = fetch_pinned.Pinned("t", "1", "file://" + path, hashlib.sha256(data).hexdigest(), "tool", None)
        out = fetch_pinned.fetch(spec, os.path.join(self.tmp.name, "out"))
        self.assertTrue(os.access(out, os.X_OK))

    def test_checksum_mismatch_rejected_and_nothing_left(self):
        path = self._file("tool", b"tampered")
        dest = os.path.join(self.tmp.name, "out")
        spec = fetch_pinned.Pinned("t", "1", "file://" + path, "0" * 64, "tool", None)
        with self.assertRaises(fetch_pinned.ChecksumError):
            fetch_pinned.fetch(spec, dest)
        self.assertFalse(os.path.exists(os.path.join(dest, "tool")))

    def test_tar_member_extracted(self):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            payload = b"binary"
            info = tarfile.TarInfo("actionlint")
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
        data = buf.getvalue()
        path = self._file("a.tar.gz", data)
        spec = fetch_pinned.Pinned("t", "1", "file://" + path, hashlib.sha256(data).hexdigest(), "actionlint",
                                   "actionlint")
        out = fetch_pinned.fetch(spec, os.path.join(self.tmp.name, "out"))
        with open(out, "rb") as fh:
            self.assertEqual(fh.read(), b"binary")

    def test_pins_are_exact(self):
        for spec in fetch_pinned.PINNED.values():
            self.assertRegex(spec.sha256, r"^[0-9a-f]{64}$")
            self.assertIn(f"/download/v{spec.version}/", spec.url)
            self.assertTrue(spec.url.startswith("https://github.com/"))


if __name__ == "__main__":
    unittest.main()
