"""Behaviour of sign_apk.sh with fake apksigner/zipalign binaries (no Android SDK needed).

Run: python3 -m unittest discover -s android/tools -p 'test_*.py' -v
"""

import base64
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "sign_apk.sh")
VERSION = "37.0.0"
PASSWORD = "pw-that-must-never-be-printed-7f3a"

FAKE_APKSIGNER = r"""#!/usr/bin/env bash
# sign: copy input to --out. verify: behaviour chosen by FAKE_VERIFY.
cmd=$1; shift
if [ "$cmd" = sign ]; then
  out=""; in=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --out) out=$2; shift ;;
      --ks|--ks-key-alias|--ks-pass|--key-pass) shift ;;
      *) in=$1 ;;
    esac
    shift
  done
  cp "$in" "$out"
  exit 0
fi
case "${FAKE_VERIFY:-ok}" in
  ok)
    echo "Verifies"
    echo "Verified using v2 scheme (APK Signature Scheme v2): true"
    echo "Number of signers: 1"
    echo "Signer #1 certificate DN: CN=Test"
    echo "WARNING: META-INF/x not protected by signature"
    exit 0 ;;
  fail)
    echo "DOES NOT VERIFY"
    echo "ERROR: APK Signature Scheme v2 signer #1: Malformed additional attribute"
    echo "WARNING: something else"
    exit 1 ;;
  error_rc0)
    echo "Verifies"
    echo "ERROR: unexpected error line with exit 0"
    exit 0 ;;
esac
"""

FAKE_ZIPALIGN = "#!/usr/bin/env bash\nexit 0\n"


class SignApkScript(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = self.tmp.name
        self.sdk = os.path.join(root, "sdk")
        bt = os.path.join(self.sdk, "build-tools", VERSION)
        os.makedirs(bt)
        for name, body in (("apksigner", FAKE_APKSIGNER), ("zipalign", FAKE_ZIPALIGN)):
            path = os.path.join(bt, name)
            with open(path, "w") as fh:
                fh.write(body)
            os.chmod(path, 0o755)
        self.runner_temp = os.path.join(root, "rt")
        os.makedirs(self.runner_temp)
        self.unsigned = os.path.join(root, "unsigned.apk")
        with open(self.unsigned, "wb") as fh:
            fh.write(b"PK fake apk")
        self.signed = os.path.join(root, "out", "app-release.apk")

    def run_script(self, verify="ok", version=VERSION):
        env = {
            "PATH": os.environ["PATH"],
            "ANDROID_HOME": self.sdk,
            "BUILD_TOOLS_VERSION": version,
            "RUNNER_TEMP": self.runner_temp,
            "KEYSTORE_BASE64": base64.b64encode(b"fake keystore").decode(),
            "ANDROID_KEYSTORE_PASSWORD": PASSWORD,
            "ANDROID_KEY_PASSWORD": PASSWORD,
            "ANDROID_KEY_ALIAS": "alias",
            "FAKE_VERIFY": verify,
        }
        p = subprocess.run(["bash", SCRIPT, self.unsigned, self.signed], env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return p.returncode, p.stdout

    def assert_keystore_removed(self):
        self.assertFalse(os.path.exists(os.path.join(self.runner_temp, "release.keystore")))

    def test_clean_run_succeeds_and_prints_summary_and_warnings(self):
        rc, out = self.run_script("ok")
        self.assertEqual(rc, 0, out)
        self.assertIn("Verifies", out)
        self.assertIn("WARNING: META-INF/x not protected by signature", out)
        self.assertIn("signed: ", out)
        self.assertNotIn(PASSWORD, out)
        self.assert_keystore_removed()

    def test_failed_verify_prints_diagnostics_and_fails(self):
        rc, out = self.run_script("fail")
        self.assertNotEqual(rc, 0, out)
        self.assertIn("DOES NOT VERIFY", out)
        self.assertIn("ERROR: APK Signature Scheme v2 signer #1: Malformed additional attribute", out)
        self.assertIn("WARNING: something else", out)
        self.assertIn("::error::apksigner verify failed", out)
        self.assertNotIn(PASSWORD, out)
        self.assert_keystore_removed()

    def test_error_line_with_exit_zero_fails(self):
        rc, out = self.run_script("error_rc0")
        self.assertNotEqual(rc, 0, out)
        self.assertIn("ERROR: unexpected error line with exit 0", out)
        self.assertIn("::error::", out)

    def test_missing_pinned_build_tools_fails(self):
        rc, out = self.run_script("ok", version="99.0.0")
        self.assertNotEqual(rc, 0, out)
        self.assertIn("pinned build-tools 99.0.0", out)
        self.assert_keystore_removed()


if __name__ == "__main__":
    unittest.main()
