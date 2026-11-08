"""Self-test for apk_check.py with fixture tool outputs (no Android SDK needed).

Run: python3 -m unittest discover -s android/tools -p 'test_*.py' -v
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import apk_check  # noqa: E402

APP = "com.mbols2510.mangamanager"
NS = "http://schemas.android.com/apk/res/android"

# Shape of `aapt2 dump xmltree --file AndroidManifest.xml <apk>` for a good release APK.
GOOD_XMLTREE = f"""N: android={NS} (line=2)
  E: manifest (line=2)
    A: {NS}:versionCode(0x0101021b)=1
    A: {NS}:versionName(0x0101021c)="0.1.0" (Raw: "0.1.0")
    A: {NS}:compileSdkVersion(0x01010572)=37
    A: package="{APP}" (Raw: "{APP}")
      E: uses-sdk (line=7)
        A: {NS}:minSdkVersion(0x0101020c)=26
        A: {NS}:targetSdkVersion(0x01010270)=37
      E: uses-permission (line=11)
        A: {NS}:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")
      E: uses-permission (line=12)
        A: {NS}:name(0x01010003)="android.permission.ACCESS_NETWORK_STATE" (Raw: "android.permission.ACCESS_NETWORK_STATE")
      E: uses-permission (line=13)
        A: {NS}:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")
      E: uses-permission (line=20)
        A: {NS}:name(0x01010003)="android.permission.WAKE_LOCK" (Raw: "android.permission.WAKE_LOCK")
      E: uses-permission (line=21)
        A: {NS}:name(0x01010003)="android.permission.RECEIVE_BOOT_COMPLETED" (Raw: "android.permission.RECEIVE_BOOT_COMPLETED")
      E: permission (line=24)
        A: {NS}:name(0x01010003)="{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        A: {NS}:protectionLevel(0x01010009)=2
      E: uses-permission (line=27)
        A: {NS}:name(0x01010003)="{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      E: application (line=29)
        A: {NS}:label(0x01010001)=@0x7f0e001b
        A: {NS}:icon(0x01010002)=@0x7f0c0000
        A: {NS}:name(0x01010003)="{APP}.MangaApp" (Raw: "{APP}.MangaApp")
        A: {NS}:allowBackup(0x01010280)=false
        A: {NS}:usesCleartextTraffic(0x010104ec)=true
        E: activity (line=40)
          A: {NS}:name(0x01010003)="{APP}.MainActivity" (Raw: "{APP}.MainActivity")
          A: {NS}:exported(0x01010010)=true
"""

GOOD_BADGING = f"""package: name='{APP}' versionCode='1' versionName='0.1.0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'26'
sdkVersion:'26'
targetSdkVersion:'37'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application-label:'Manga Manager'
application: label='Manga Manager' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
launchable-activity: name='{APP}.MainActivity'  label='' icon=''
feature-group: label=''
  uses-feature: name='android.hardware.faketouch'
  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
supports-screens: 'small' 'normal' 'large' 'xlarge'
"""

# R8 mapping: our classes are renamed; a library class keeps its own package.
GOOD_MAPPING = f"""# compiler: R8
# compiler_version: 9.4.30
{APP}.MainActivity -> {APP}.MainActivity:
    1:3:void <init>():0:2 -> <init>
{APP}.work.CheckWorker -> a.b:
    void doWork() -> a
{APP}.work.CheckWorker$$ExternalSyntheticLambda0 -> a.c:
androidx.core.app.NotificationCompat -> f2.d:
    int foo() -> a
"""

# `dexdump -d classes.dex`: AOSP dexdump prints method refs as Lpkg/Cls;.name:(sig)ret
DEXDUMP_CLEAN = """Processing 'classes.dex'...
Opened 'classes.dex', DEX version '039'
Class #0            -
  Class descriptor  : 'La/b;'
  Access flags      : 0x0011 (PUBLIC FINAL)
  Superclass        : 'Ljava/lang/Object;'
000174:                                        |[000174] a.b.a:()V
000184: 7010 0300 0000                         |0000: invoke-direct {v0}, Ljava/lang/Object;.<init>:()V // method@0003
00018a: 0e00                                   |0003: return-void
Class #1            -
  Class descriptor  : 'Lf2/d;'
  Access flags      : 0x0011 (PUBLIC FINAL)
  Superclass        : 'Ljava/lang/Object;'
000200:                                        |[000200] f2.d.a:()I
000210: 7120 0100 1000                         |0000: invoke-static {v0, v1}, Landroid/util/Log;.isLoggable:(Ljava/lang/String;I)Z // method@0001
000216: 0f00                                   |0003: return v0
"""

DEXDUMP_OUR_LOG = DEXDUMP_CLEAN + """Class #2            -
  Class descriptor  : 'La/c;'
  Access flags      : 0x1011 (PUBLIC FINAL SYNTHETIC)
  Superclass        : 'Ljava/lang/Object;'
000300:                                        |[000300] a.c.run:()V
000310: 7120 0200 1000                         |0000: invoke-static {v0, v1}, Landroid/util/Log;.d:(Ljava/lang/String;Ljava/lang/String;)I // method@0002
000316: 0e00                                   |0003: return-void
"""

# An unrenamed class of ours (no mapping entry needed) calling Log through /range.
DEXDUMP_UNMAPPED_OUR_LOG = DEXDUMP_CLEAN + f"""Class #3            -
  Class descriptor  : 'L{APP.replace('.', '/')}/Leak;'
000400:                                        |[000400] {APP}.Leak.x:()V
000410: 7720 0200 1000                         |0000: invoke-static/range {{v0 .. v1}}, Landroid/util/Log;.w:(Ljava/lang/String;Ljava/lang/String;)I // method@0002
"""


def _replace(text, old, new):
    assert old in text, old
    return text.replace(old, new)


def run_eval(xmltree=GOOD_XMLTREE, badging=GOOD_BADGING, mapping=GOOD_MAPPING, dexdump=DEXDUMP_CLEAN, **kw):
    report = apk_check.evaluate(
        xmltree=xmltree,
        badging=badging,
        mapping=mapping,
        dexdumps=[dexdump],
        app_package=APP,
        **kw,
    )
    return report


class ParseTests(unittest.TestCase):
    def test_xmltree_parsed(self):
        m = apk_check.parse_xmltree(GOOD_XMLTREE)
        self.assertEqual(m["package"], APP)
        self.assertEqual(m["min_sdk"], 26)
        self.assertEqual(m["target_sdk"], 37)
        self.assertEqual(m["version_name"], "0.1.0")
        self.assertFalse(m["debuggable"])
        self.assertEqual(m["cleartext"], "true")
        self.assertEqual(len(m["permissions"]), 6)
        self.assertIn("android.permission.WAKE_LOCK", m["permissions"])
        # <permission> declarations are not requests
        self.assertEqual(m["permissions"].count(f"{APP}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), 1)

    def test_xmltree_debuggable_variants(self):
        for value in ("true", "(boolean) true", "0xffffffff", "-1"):
            tree = _replace(GOOD_XMLTREE, "allowBackup(0x01010280)=false",
                            f"allowBackup(0x01010280)=false\n        A: {NS}:debuggable(0x0101000f)={value}")
            self.assertTrue(apk_check.parse_xmltree(tree)["debuggable"], value)

    def test_badging_parsed(self):
        b = apk_check.parse_badging(GOOD_BADGING)
        self.assertEqual(b["package"], APP)
        self.assertEqual(b["version_name"], "0.1.0")
        self.assertEqual(b["min_sdk"], 26)
        self.assertFalse(b["debuggable"])
        self.assertEqual(len(b["permissions"]), 6)

    def test_mapping_our_classes(self):
        ours = apk_check.our_dex_classes(GOOD_MAPPING, APP)
        self.assertEqual(ours, {f"L{APP.replace('.', '/')}/MainActivity;", "La/b;", "La/c;"})

    def test_log_calls_attributed_to_class(self):
        calls = apk_check.log_invocations([DEXDUMP_OUR_LOG])
        self.assertEqual(calls, [("Lf2/d;", "isLoggable"), ("La/c;", "d")])


class EvaluateTests(unittest.TestCase):
    def test_good_release_passes(self):
        r = run_eval()
        self.assertEqual(r.problems, [])
        joined = "\n".join(r.info)
        self.assertIn("usesCleartextTraffic=true", joined)
        self.assertIn("isLoggable", joined)  # library Log references are reported, not failed

    def test_debuggable_manifest_fails(self):
        tree = _replace(GOOD_XMLTREE, "allowBackup(0x01010280)=false",
                        f"allowBackup(0x01010280)=false\n        A: {NS}:debuggable(0x0101000f)=true")
        self.assertTrue(any("debuggable" in p for p in run_eval(xmltree=tree).problems))

    def test_debuggable_badging_fails(self):
        badging = GOOD_BADGING + "application-debuggable\n"
        self.assertTrue(any("debuggable" in p for p in run_eval(badging=badging).problems))

    def test_extra_permission_fails(self):
        tree = _replace(GOOD_XMLTREE, "      E: application (line=29)",
                        f"      E: uses-permission (line=28)\n        A: {NS}:name(0x01010003)=\"android.permission.CAMERA\" (Raw: \"android.permission.CAMERA\")\n      E: application (line=29)")
        problems = run_eval(xmltree=tree).problems
        self.assertIn("permission not in allowlist: android.permission.CAMERA", problems)

    def test_extra_permission_only_in_badging_fails(self):
        badging = GOOD_BADGING + "uses-permission: name='android.permission.FOREGROUND_SERVICE'\n"
        problems = run_eval(badging=badging).problems
        self.assertIn("permission not in allowlist: android.permission.FOREGROUND_SERVICE", problems)

    def test_missing_allowlisted_permission_fails(self):
        tree = GOOD_XMLTREE.replace(
            f"      E: uses-permission (line=20)\n        A: {NS}:name(0x01010003)=\"android.permission.WAKE_LOCK\" (Raw: \"android.permission.WAKE_LOCK\")\n", "")
        badging = GOOD_BADGING.replace("uses-permission: name='android.permission.WAKE_LOCK'\n", "")
        problems = run_eval(xmltree=tree, badging=badging).problems
        self.assertTrue(any("missing" in p and "WAKE_LOCK" in p for p in problems), problems)

    def test_min_sdk_wrong_fails(self):
        tree = _replace(GOOD_XMLTREE, "minSdkVersion(0x0101020c)=26", "minSdkVersion(0x0101020c)=24")
        badging = _replace(GOOD_BADGING, "sdkVersion:'26'", "sdkVersion:'24'")
        problems = run_eval(xmltree=tree, badging=badging).problems
        self.assertTrue(any("minSdkVersion" in p for p in problems), problems)

    def test_min_sdk_unparseable_fails_closed(self):
        tree = _replace(GOOD_XMLTREE, f"        A: {NS}:minSdkVersion(0x0101020c)=26\n", "")
        problems = run_eval(xmltree=tree).problems
        self.assertTrue(any("minSdkVersion" in p for p in problems), problems)

    def test_empty_tool_output_fails_closed(self):
        problems = run_eval(xmltree="", badging="").problems
        self.assertTrue(problems)

    def test_log_call_in_renamed_app_class_fails(self):
        problems = run_eval(dexdump=DEXDUMP_OUR_LOG).problems
        self.assertTrue(any("android.util.Log.d" in p and "work.CheckWorker$$ExternalSyntheticLambda0" in p for p in problems), problems)

    def test_log_call_in_unmapped_app_class_fails(self):
        problems = run_eval(dexdump=DEXDUMP_UNMAPPED_OUR_LOG).problems
        self.assertTrue(any("android.util.Log.w" in p for p in problems), problems)

    def test_missing_mapping_fails_closed(self):
        problems = run_eval(mapping="").problems
        self.assertTrue(any("mapping" in p for p in problems), problems)

    def test_no_dex_fails_closed(self):
        r = apk_check.evaluate(xmltree=GOOD_XMLTREE, badging=GOOD_BADGING, mapping=GOOD_MAPPING,
                               dexdumps=[], app_package=APP)
        self.assertTrue(any("dex" in p for p in r.problems), r.problems)

    def test_expected_version_mismatch_fails(self):
        problems = run_eval(expect_version="0.2.0").problems
        self.assertTrue(any("versionName" in p for p in problems), problems)
        self.assertEqual(run_eval(expect_version="0.1.0").problems, [])

    def test_wrong_package_fails(self):
        badging = _replace(GOOD_BADGING, f"package: name='{APP}'", f"package: name='{APP}.debug'")
        tree = _replace(GOOD_XMLTREE, f'package="{APP}"', f'package="{APP}.debug"')
        problems = run_eval(xmltree=tree, badging=badging).problems
        self.assertTrue(any("package" in p for p in problems), problems)


class AllowlistSyncTest(unittest.TestCase):
    def test_same_permissions_as_merged_manifest_checker(self):
        import re
        here = os.path.dirname(os.path.abspath(__file__))
        kt = os.path.join(here, "..", "app", "src", "test", "kotlin", "com", "mbols2510", "mangamanager",
                          "manifest", "MergedManifestChecker.kt")
        with open(kt, encoding="utf-8") as fh:
            source = fh.read()
        body = re.search(r"fun allowedPermissions\(applicationId: String\): Set<String> = setOf\((.*?)\)", source, re.S)
        self.assertIsNotNone(body, "allowedPermissions not found in MergedManifestChecker.kt")
        kotlin = {s.replace("$applicationId", APP) for s in re.findall(r'"([^"]+)"', body.group(1))}
        self.assertEqual(kotlin, apk_check.allowed_permissions(APP))


class SignatureTests(unittest.TestCase):
    def test_signature_required_and_missing_fails(self):
        problems = apk_check.signature_problems(expect_signed=True, verify_exit=1, verify_output="DOES NOT VERIFY")
        self.assertTrue(problems)

    def test_signature_required_and_present_passes(self):
        out = "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: ab"
        self.assertEqual(apk_check.signature_problems(expect_signed=True, verify_exit=0, verify_output=out), [])

    def test_unsigned_allowed_when_not_required(self):
        self.assertEqual(apk_check.signature_problems(expect_signed=False, verify_exit=1, verify_output="x"), [])


if __name__ == "__main__":
    unittest.main()
