#!/usr/bin/env bash
# Signs an already built and checked UNSIGNED release APK with the SDK's apksigner. Gradle never sees the signing
# secrets: .github/workflows/android-release.yml builds and checks without them, and only this script's step
# carries them. Also used by the dry run's signing rehearsal with a throwaway key.
#
# usage: tools/sign_apk.sh <unsigned.apk> <signed.apk>
# env:   KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD, RUNNER_TEMP
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
for var in KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD RUNNER_TEMP; do
  if [ -z "${!var:-}" ]; then
    echo "::error::$var is not set"
    exit 1
  fi
done

ks="$RUNNER_TEMP/release.keystore"
trap 'rm -f "$ks"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$ks"

bt=""
for dir in $(ls -d "$ANDROID_HOME"/build-tools/* | sort -V); do
  if [ -x "$dir/apksigner" ] && [ -x "$dir/zipalign" ]; then bt=$dir; fi
done
if [ -z "$bt" ]; then
  echo "::error::no build-tools with apksigner and zipalign under $ANDROID_HOME"
  exit 1
fi
echo "build-tools used for signing: $(basename "$bt")"

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
"$bt/apksigner" verify --print-certs --verbose "$out" | grep -E '^(Verifies|Verified using|Number of signers|Signer #1 certificate (DN|SHA-256))'
echo "== zipalign check of the signed APK"
"$bt/zipalign" -c -P 16 4 "$out"
echo "zipalign: signed APK aligned"
echo "signed: $out"
