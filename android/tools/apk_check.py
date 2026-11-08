#!/usr/bin/env python3
"""Checks the BUILT release APK (spec 7.4/7.7/8): what ships, not the Gradle DSL.

Used by .github/workflows/android.yml and .github/workflows/android-release.yml.
Python 3 standard library only; the Android SDK build-tools on the runner do the APK parsing:
  aapt2 dump xmltree / dump badging   manifest as packaged
  dexdump -d                           disassembly of every classes*.dex
  apksigner verify --print-certs       signature (required with --expect-signed)

Fails unless:
  (a) the APK is not debuggable (binary manifest attribute AND aapt2 badging);
  (b) the requested permissions equal the allowlist exactly (same set as MergedManifestChecker,
      src/test/.../manifest/MergedManifestChecker.kt: keep both in sync);
  (c) no android.util.Log invocation survives R8 in the app's own classes. Scope: classes whose
      ORIGINAL name (R8 mapping.txt) is in the app package, plus any dex class still named in it.
      Library code (androidx, OkHttp, Coil...) may keep Log references that the -assumenosideeffects
      rule cannot remove (calls whose result is used, e.g. Log.isLoggable in a condition); those are
      counted and printed, not failed. Code that R8 inlines from the app into a library class would
      escape this attribution; the app source has no logging at all (spec 7.4), so this is a backstop;
  (d) minSdkVersion is 26 (manifest and badging);
  (e) the package is the release application id (and versionName matches --expect-version if given);
  (f) with --expect-signed: apksigner verifies the signature.
Printed, not failed: android:usesCleartextTraffic (declared true on purpose, see the manifest), APK size,
SHA-256, build-tools versions, signer certificates.

Self-test: python3 -m unittest discover -s android/tools -p 'test_*.py' -v
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

APP_PACKAGE = "com.mbols2510.mangamanager"
EXPECTED_MIN_SDK = 26
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def allowed_permissions(app_package: str) -> set[str]:
    """Must equal MergedManifestChecker.allowedPermissions (Ruling 12b)."""
    return {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        f"{app_package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }


# ---------------------------------------------------------------- parsing tool output

_ELEMENT = re.compile(r"^\s*E: ([\w.-]+)")
_ATTR = re.compile(r"^\s*A: (?:" + re.escape(ANDROID_NS) + r":)?([\w.-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$")


def _attr_value(raw: str) -> str:
    raw = raw.strip()
    m = re.match(r'^"((?:[^"\\]|\\.)*)"', raw)
    if m:
        return m.group(1)
    return re.sub(r"\s*\(Raw: .*\)$", "", raw)


def _truthy(value: str) -> bool:
    v = value.strip().lower()
    return v in ("true", "(boolean) true", "0xffffffff", "-1", "1")


def parse_xmltree(text: str) -> dict:
    """Parses `aapt2 dump xmltree --file AndroidManifest.xml`. Attributes belong to the last element seen."""
    result = {"package": None, "version_name": None, "min_sdk": None, "target_sdk": None,
              "debuggable": False, "cleartext": None, "permissions": []}
    element = None
    for line in text.splitlines():
        em = _ELEMENT.match(line)
        if em:
            element = em.group(1)
            continue
        am = _ATTR.match(line)
        if not am or element is None:
            continue
        name, value = am.group(1), _attr_value(am.group(2))
        if element == "manifest":
            if name == "package":
                result["package"] = value
            elif name == "versionName":
                result["version_name"] = value
        elif element == "uses-sdk":
            if name in ("minSdkVersion", "targetSdkVersion") and re.fullmatch(r"\d+", value):
                result["min_sdk" if name == "minSdkVersion" else "target_sdk"] = int(value)
        elif element in ("uses-permission", "uses-permission-sdk-23") and name == "name":
            result["permissions"].append(value)
        elif element == "application":
            if name == "debuggable" and _truthy(value):
                result["debuggable"] = True
            elif name == "usesCleartextTraffic":
                result["cleartext"] = "true" if _truthy(value) else value
    return result


def parse_badging(text: str) -> dict:
    """Parses `aapt2 dump badging`."""
    result = {"package": None, "version_name": None, "min_sdk": None, "debuggable": False, "permissions": []}
    for line in text.splitlines():
        if line.startswith("package:"):
            m = re.search(r"\bname='([^']*)'", line)
            result["package"] = m.group(1) if m else None
            m = re.search(r"\bversionName='([^']*)'", line)
            result["version_name"] = m.group(1) if m else None
        elif line.startswith("sdkVersion:") or line.startswith("minSdkVersion:"):
            m = re.search(r"'(\d+)'", line)
            if m:
                result["min_sdk"] = int(m.group(1))
        elif line.startswith("uses-permission:") or line.startswith("uses-permission-sdk-23:"):
            m = re.search(r"name='([^']*)'", line)
            if m:
                result["permissions"].append(m.group(1))
        elif line.strip() == "application-debuggable":
            result["debuggable"] = True
    return result


def mapping_classes(mapping: str) -> dict[str, str]:
    """R8 mapping.txt class lines `original -> obfuscated:` as {obfuscated dex descriptor: original}."""
    classes = {}
    for line in mapping.splitlines():
        if not line or line[0] in " #\t":
            continue
        m = re.match(r"^(\S+) -> (\S+):$", line)
        if m:
            classes["L" + m.group(2).replace(".", "/") + ";"] = m.group(1)
    return classes


def our_dex_classes(mapping: str, app_package: str) -> set[str]:
    prefix = app_package + "."
    return {desc for desc, orig in mapping_classes(mapping).items() if orig.startswith(prefix)}


_CLASS_DESC = re.compile(r"^\s*Class descriptor\s*:\s*'(L[^']+;)'")
_LOG_INVOKE = re.compile(r"\binvoke-[\w/-]+ \{[^}]*\}, Landroid/util/Log;(?:\.|->)([\w$<>]+)")


def log_invocations(dexdumps: list[str]) -> list[tuple[str, str]]:
    """(dex class descriptor, Log method) for every invoke of android.util.Log in `dexdump -d` output."""
    calls = []
    for text in dexdumps:
        current = None
        for line in text.splitlines():
            cm = _CLASS_DESC.match(line)
            if cm:
                current = cm.group(1)
                continue
            lm = _LOG_INVOKE.search(line)
            if lm:
                calls.append((current or "<unknown>", lm.group(1)))
    return calls


# ---------------------------------------------------------------- evaluation


@dataclasses.dataclass
class Report:
    problems: list[str] = dataclasses.field(default_factory=list)
    info: list[str] = dataclasses.field(default_factory=list)


def evaluate(*, xmltree: str, badging: str, mapping: str, dexdumps: list[str], app_package: str = APP_PACKAGE,
             expect_version: str | None = None) -> Report:
    r = Report()
    m = parse_xmltree(xmltree)
    b = parse_badging(badging)

    # (e) package / version
    for source, pkg in (("manifest", m["package"]), ("badging", b["package"])):
        if pkg != app_package:
            r.problems.append(f"{source}: package is {pkg!r}, expected {app_package!r}")
    r.info.append(f"package={m['package']} versionName={m['version_name']} targetSdk={m['target_sdk']}")
    if expect_version is not None:
        for source, ver in (("manifest", m["version_name"]), ("badging", b["version_name"])):
            if ver != expect_version:
                r.problems.append(f"{source}: versionName is {ver!r}, expected {expect_version!r} (from the tag)")

    # (a) debuggable
    if m["debuggable"]:
        r.problems.append("manifest: application is debuggable (android:debuggable=true)")
    if b["debuggable"]:
        r.problems.append("badging: application-debuggable is set")
    r.info.append(f"debuggable: manifest={m['debuggable']} badging={b['debuggable']}")

    # (b) permissions: union of both views must equal the allowlist
    requested = set(m["permissions"]) | set(b["permissions"])
    allowed = allowed_permissions(app_package)
    for p in sorted(requested - allowed):
        r.problems.append(f"permission not in allowlist: {p}")
    for p in sorted(allowed - requested):
        r.problems.append(f"allowlisted permission missing (allowlist out of date?): {p}")
    r.info.append("uses-permission: " + ", ".join(sorted(requested)))

    # (d) minSdk
    for source, sdk in (("manifest", m["min_sdk"]), ("badging", b["min_sdk"])):
        if sdk != EXPECTED_MIN_SDK:
            r.problems.append(f"{source}: minSdkVersion is {sdk!r}, expected {EXPECTED_MIN_SDK}")
    r.info.append(f"minSdkVersion: manifest={m['min_sdk']} badging={b['min_sdk']}")

    # (e) cleartext: printed only
    r.info.append(f"usesCleartextTraffic={m['cleartext']} (declared true on purpose; UrlPolicy gates plain HTTP)")

    # (c) Log calls
    classes = mapping_classes(mapping)
    if not classes:
        r.problems.append("R8 mapping.txt is empty or missing: cannot attribute dex classes (is R8 on?)")
    if not dexdumps or not any(_CLASS_DESC.search(line) for d in dexdumps for line in d.splitlines()):
        r.problems.append("no classes found in dex disassembly (no classes*.dex or dexdump failed)")
    ours = our_dex_classes(mapping, app_package)
    own_prefix = "L" + app_package.replace(".", "/") + "/"
    calls = log_invocations(dexdumps)
    for desc, method in calls:
        if desc in ours or desc.startswith(own_prefix):
            original = classes.get(desc, desc)
            r.problems.append(f"android.util.Log.{method} invoked in app class {original} ({desc}) after R8")
    per_method = collections.Counter(method for _, method in calls)
    r.info.append(f"app classes in mapping: {len(ours)}; dex classes in mapping: {len(classes)}")
    r.info.append("android.util.Log invocations left in library code (not failed): "
                  + (", ".join(f"{k}={v}" for k, v in sorted(per_method.items())) or "none"))
    return r


def signature_problems(*, expect_signed: bool, verify_exit: int, verify_output: str) -> list[str]:
    if not expect_signed:
        return []
    if verify_exit != 0 or not re.search(r"^Verifies\b", verify_output, re.M):
        return [f"apksigner verify failed (exit {verify_exit}); a signed release APK is required"]
    return []


# ---------------------------------------------------------------- running the SDK tools


def _version_key(name: str):
    return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", name)]


def find_build_tools(explicit: str | None) -> str:
    if explicit:
        return explicit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("apk_check: ANDROID_HOME is not set and --build-tools not given")
    root = os.path.join(sdk, "build-tools")
    versions = sorted(os.listdir(root), key=_version_key)
    print(f"build-tools available: {' '.join(versions)}")
    usable = [v for v in versions
              if all(os.path.exists(os.path.join(root, v, t)) for t in ("aapt2", "dexdump", "apksigner"))]
    if not usable:
        sys.exit("apk_check: no build-tools version has aapt2, dexdump and apksigner")
    print(f"build-tools used: {usable[-1]}")
    return os.path.join(root, usable[-1])


def _run(cmd: list[str]) -> tuple[int, str]:
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return p.returncode, p.stdout


def _must(cmd: list[str]) -> str:
    code, out = _run(cmd)
    if code != 0:
        sys.exit(f"apk_check: {' '.join(cmd)} failed with exit {code}:\n{out}")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Checks the built release APK.")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--mapping", required=True, help="R8 mapping.txt of the same build")
    ap.add_argument("--build-tools", help="build-tools directory (default: newest under $ANDROID_HOME)")
    ap.add_argument("--app-package", default=APP_PACKAGE)
    ap.add_argument("--expect-version", help="versionName the APK must carry (release tag)")
    ap.add_argument("--expect-signed", action="store_true", help="fail unless apksigner verifies the APK")
    args = ap.parse_args(argv)

    bt = find_build_tools(args.build_tools)
    aapt2, dexdump, apksigner = (os.path.join(bt, t) for t in ("aapt2", "dexdump", "apksigner"))
    print("aapt2 " + _run([aapt2, "version"])[1].strip())

    xmltree = _must([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", args.apk])
    badging = _must([aapt2, "dump", "badging", args.apk])
    print("---- aapt2 dump xmltree: application element (head) ----")
    lines = xmltree.splitlines()
    start = next((i for i, ln in enumerate(lines) if ln.strip().startswith("E: application")), 0)
    print("\n".join(lines[start:start + 14]))
    mapping = ""
    if os.path.exists(args.mapping):
        with open(args.mapping, encoding="utf-8") as fh:
            mapping = fh.read()

    dexdumps = []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(args.apk) as z:
        dex_names = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        print(f"dex files: {' '.join(dex_names) or 'none'}")
        for name in dex_names:
            path = z.extract(name, tmp)
            dexdumps.append(_must([dexdump, "-d", path]))

    report = evaluate(xmltree=xmltree, badging=badging, mapping=mapping, dexdumps=dexdumps,
                      app_package=args.app_package, expect_version=args.expect_version)

    code, verify_out = _run([apksigner, "verify", "--print-certs", "--verbose", args.apk])
    report.problems += signature_problems(expect_signed=args.expect_signed, verify_exit=code,
                                          verify_output=verify_out)
    signed = code == 0
    report.info.append(f"signature: {'verified' if signed else 'unsigned or invalid'} (apksigner exit {code})")
    if signed:
        report.info += [ln for ln in verify_out.splitlines()
                        if "certificate" in ln.lower() or ln.startswith("Verified using")]

    with open(args.apk, "rb") as fh:
        digest = hashlib.sha256(fh.read()).hexdigest()
    size = os.path.getsize(args.apk)
    report.info.append(f"APK size: {size} bytes ({size / 1048576:.2f} MiB)")
    report.info.append(f"APK sha256: {digest}")

    print("---- APK check ----")
    for line in report.info:
        print(f"  {line}")
    if report.problems:
        for p in report.problems:
            print(f"::error::APK check: {p}")
        print(f"APK check FAILED: {len(report.problems)} problem(s) in {args.apk}")
        return 1
    print(f"APK check OK: {args.apk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
