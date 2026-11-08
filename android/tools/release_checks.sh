#!/usr/bin/env bash
# Checks on the BUILT release APK plus the dependency vulnerability scan, shared by
# .github/workflows/android.yml (every build) and .github/workflows/android-release.yml (dry run and tag release).
# Run from android/ after `./gradlew assembleRelease`.
#
# usage: tools/release_checks.sh <out-dir> [--apk PATH] [--no-osv] [--expect-signed] [--expect-version X.Y.Z]
#
# --apk checks that APK instead of the single APK in app/build/outputs/apk/release (the signed APK);
# --no-osv skips the dependency scan (already run on the unsigned build of the same commit).
#
# Writes into <out-dir>: the release APK (app-release.apk when signed, app-release-unsigned.apk otherwise),
# <apk>.sha256 in `sha256sum -c` format, and release-runtime-deps.txt. Runs every check even when an earlier one
# failed, then exits 1 if any failed.
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "usage: tools/release_checks.sh <out-dir> [--apk PATH] [--no-osv] [--expect-signed] [--expect-version X.Y.Z]" >&2
  exit 2
fi
out=$1
shift
apk_args=()
apk=""
osv=true
while [ $# -gt 0 ]; do
  case "$1" in
    --expect-signed) apk_args+=(--expect-signed) ;;
    --apk) apk=$2; shift ;;
    --no-osv) osv=false ;;
    --expect-version) apk_args+=(--expect-version "$2"); shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
mkdir -p "$out"
status=0

if [ -z "$apk" ]; then
  shopt -s nullglob
  apks=(app/build/outputs/apk/release/*.apk)
  if [ ${#apks[@]} -ne 1 ]; then
    echo "::error::expected exactly one release APK in app/build/outputs/apk/release, found ${#apks[@]}"
    exit 1
  fi
  apk=${apks[0]}
fi

echo "== APK check: $apk"
python3 tools/apk_check.py --apk "$apk" --mapping app/build/outputs/mapping/release/mapping.txt "${apk_args[@]}" \
  || status=1

if [ "$osv" = true ]; then
echo "== Dependency vulnerability scan (OSV-Scanner over gradle/verification-metadata.xml)"
tools_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pinned-tools"
if scanner=$(python3 tools/fetch_pinned.py osv-scanner "$tools_dir"); then
  if ./gradlew --no-daemon --quiet --no-configuration-cache :app:dependencies --configuration releaseRuntimeClasspath \
      > "$out/release-runtime-deps.txt"; then
    python3 tools/osv_scan.py --scanner "$scanner" --lockfile gradle/verification-metadata.xml \
      --allowlist osv-allowlist.toml --runtime-deps "$out/release-runtime-deps.txt" || status=1
  else
    echo "::error::could not list the release runtime classpath"
    status=1
  fi
else
  echo "::error::could not fetch the pinned osv-scanner"
  status=1
fi
else
  echo "== Dependency vulnerability scan skipped (--no-osv)"
fi

echo "== SHA-256"
name=$(basename "$apk")
if [ "$(realpath "$apk")" != "$(realpath -m "$out/$name")" ]; then cp "$apk" "$out/$name"; fi
(cd "$out" && sha256sum "$name" > "$name.sha256" && cat "$name.sha256")

if [ "$status" -ne 0 ]; then
  echo "Release checks FAILED"
fi
exit "$status"
