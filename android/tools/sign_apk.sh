#!/usr/bin/env bash
# Signs an already built and checked UNSIGNED release APK with the SDK's apksigner. Gradle never sees the signing
# secrets: .github/workflows/android-release.yml builds and checks in a secret-free job; the separate `sign` job
# (fresh runner, no Gradle) runs this script on the downloaded, checksum-verified APK. Also used by the dry run's
# signing rehearsal with a throwaway key.
#
# usage: android/tools/sign_apk.sh <unsigned.apk> <signed.apk>
# env:   KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD, RUNNER_TEMP,
#        ANDROID_HOME, BUILD_TOOLS_VERSION (exact build-tools directory to use)
#
# The keystore is decoded to $RUNNER_TEMP (outside the workspace) with umask 077 and removed on exit; the
# passwords reach apksigner as env:VAR references, never on a command line, and are never printed.
set -euo pipefail
umask 077

if [ $# -ne 2 ]; then
  echo "usage: tools/sign_apk.sh <unsigned.apk> <signed.apk>" >&2
  exit 2
fi
in=$1
out=$2
for var in KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD RUNNER_TEMP ANDROID_HOME BUILD_TOOLS_VERSION; do
  if [ -z "${!var:-}" ]; then
    echo "::error::$var is not set"
    exit 1
  fi
done

ks="$RUNNER_TEMP/release.keystore"
trap 'rm -f "$ks"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$ks"

# Exactly the pinned version (BUILD_TOOLS_VERSION, set in the workflow's sign job): never "the highest one".
bt="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -x "$bt/apksigner" ] || [ ! -x "$bt/zipalign" ]; then
  echo "::error::pinned build-tools $BUILD_TOOLS_VERSION (apksigner, zipalign) not found under $ANDROID_HOME/build-tools"
  exit 1
fi
echo "build-tools used for signing: $BUILD_TOOLS_VERSION (pinned) at $bt"

echo "== zipalign check of the unsigned APK (4-byte entries, 16 KiB pages for native libraries)"
"$bt/zipalign" -c -P 16 4 "$in"
echo "zipalign: unsigned APK aligned"

mkdir -p "$(dirname "$out")"
"$bt/apksigner" sign --ks "$ks" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$out" "$in"
rm -f "$ks"
rm -f "$out.idsig"

echo "== apksigner verify"
# Capture everything first, print the diagnostics in every case, and only then decide (fail closed).
verify_rc=0
verify_out=$("$bt/apksigner" verify --print-certs --verbose "$out" 2>&1) || verify_rc=$?
summary=$(printf '%s\n' "$verify_out" | grep -E '^(Verifies|DOES NOT VERIFY|Verified using|Number of signers|.*Signer.*certificate (DN|SHA-256 digest))' || true)
warnings=$(printf '%s\n' "$verify_out" | grep '^WARNING' || true)
errors=$(printf '%s\n' "$verify_out" | grep '^ERROR' || true)
printf '%s\n' "${summary:-apksigner: no verification summary}"
printf '%s\n' "${warnings:-apksigner: no warnings}"
if [ -n "$errors" ]; then printf '%s\n' "$errors"; fi
if [ "$verify_rc" -ne 0 ] || [ -n "$errors" ]; then
  echo "::error::apksigner verify failed (exit $verify_rc) or reported errors"
  exit 1
fi
echo "== zipalign check of the signed APK"
"$bt/zipalign" -c -P 16 4 "$out"
echo "zipalign: signed APK aligned"
echo "signed: $out"
