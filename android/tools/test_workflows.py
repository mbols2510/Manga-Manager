"""Static security invariants of the Android workflows (the signed tag path cannot run without publishing, so
it is checked here and by actionlint). Text-based on purpose: the standard library has no YAML parser.

Run: python3 -m unittest discover -s android/tools -p 'test_*.py' -v
"""

import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
WF = os.path.join(ROOT, ".github", "workflows")
ANDROID_WORKFLOWS = ["android.yml", "android-release.yml", "workflow-lint.yml"]


def read(name):
    with open(os.path.join(WF, name), encoding="utf-8") as fh:
        return fh.read()


def steps(text):
    """Each `- ` list item at step indentation (6 spaces) under a job's steps, as text blocks."""
    blocks, current = [], None
    for line in text.splitlines():
        if re.match(r"^ {6}- ", line):
            if current is not None:
                blocks.append("\n".join(current))
            current = [line]
        elif current is not None and (line.startswith("        ") or not line.strip()):
            current.append(line)
        elif current is not None:
            blocks.append("\n".join(current))
            current = None
    if current is not None:
        blocks.append("\n".join(current))
    return blocks


def run_scripts(text):
    """Bodies of `run: |` blocks and single-line `run:` values."""
    lines, out, i = text.splitlines(), [], 0
    while i < len(lines):
        m = re.match(r"^(\s*)(?:- )?run: ?(.*)$", lines[i])
        if m:
            indent, value = len(m.group(1)), m.group(2)
            if value.strip() in ("|", ">"):
                body, i = [], i + 1
                while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
                    body.append(lines[i])
                    i += 1
                out.append("\n".join(body))
                continue
            out.append(value)
        i += 1
    return out


def jobs(text):
    """{job id: block text} for top-level jobs (2-space indentation under `jobs:`)."""
    body = text.split("\njobs:\n", 1)[1]
    result, name, buf = {}, None, []
    for line in body.splitlines():
        m = re.match(r"^  ([\w-]+):\s*$", line)
        if m:
            if name:
                result[name] = "\n".join(buf)
            name, buf = m.group(1), []
        elif name:
            buf.append(line)
    if name:
        result[name] = "\n".join(buf)
    return result


class AllAndroidWorkflows(unittest.TestCase):
    def test_actions_pinned_to_exact_tags(self):
        for name in ANDROID_WORKFLOWS:
            for uses in re.findall(r"uses: (\S+)", read(name)):
                self.assertRegex(uses, r"^[\w.-]+/[\w./-]+@v\d+\.\d+\.\d+$", f"{name}: {uses}")

    def test_checkout_does_not_persist_credentials(self):
        for name in ANDROID_WORKFLOWS:
            for step in steps(read(name)):
                if "actions/checkout@" in step:
                    self.assertIn("persist-credentials: false", step, name)

    def test_no_expressions_inside_run_scripts(self):
        for name in ANDROID_WORKFLOWS:
            for script in run_scripts(read(name)):
                self.assertNotIn("${{", script, f"{name}: pass values through env:, not into run:\n{script}")

    def test_default_permissions_read_only(self):
        for name in ANDROID_WORKFLOWS:
            self.assertIn("\npermissions:\n  contents: read\n", read(name), name)


class ReleaseWorkflow(unittest.TestCase):
    text = read("android-release.yml")
    TAG_GUARD = "env.RELEASE == 'true'"
    TAG_EXPR = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/android-v')"

    def test_triggers(self):
        on = self.text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertNotIn("pull_request", on)
        self.assertIn('tags: ["android-v*"]', on)
        self.assertNotIn("inputs:", on)  # no input can switch the mode

    def test_release_flag_is_derived_from_the_tag_event_only(self):
        self.assertIn("RELEASE: ${{ " + self.TAG_EXPR + " }}", self.text)

    def test_secrets_only_in_tag_guarded_steps(self):
        seen = 0
        for step in steps(self.text):
            if "secrets." in step:
                seen += 1
                self.assertIn(f"if: {self.TAG_GUARD}", step, step)
        self.assertEqual(seen, 2)  # keystore write + signed build
        self.assertNotIn("secrets.", "\n".join(run_scripts(self.text)))

    def test_only_publish_job_can_write_and_it_is_tag_guarded(self):
        js = jobs(self.text)
        self.assertEqual(set(js), {"build", "verify-artifact", "publish"})
        writers = [j for j, body in js.items() if "contents: write" in body]
        self.assertEqual(writers, ["publish"])
        self.assertIn(f"if: {self.TAG_EXPR}", js["publish"])
        self.assertNotIn("actions/checkout", js["publish"])  # publishes the built artifact, runs no project code
        for j in ("build", "verify-artifact"):
            self.assertIn("contents: read", js[j])

    def test_keystore_outside_workspace_and_removed(self):
        self.assertIn('> "$RUNNER_TEMP/release.keystore"', self.text)
        self.assertIn("ANDROID_KEYSTORE_PATH: ${{ runner.temp }}/release.keystore", self.text)
        remove = [s for s in steps(self.text) if 'rm -f "$RUNNER_TEMP/release.keystore"' in s]
        self.assertEqual(len(remove), 1)
        self.assertIn("if: always()", remove[0])

    def test_signed_path_verifies_signature_and_version(self):
        self.assertIn("tools/release_checks.sh build/release-out --expect-signed --expect-version", self.text)

    def test_release_notes_have_checksum_and_debug_warning(self):
        publish = jobs(self.text)["publish"]
        self.assertIn("SHA-256", publish)
        self.assertIn("debug APK", publish)
        self.assertIn("never be used with a real device token", publish)
        self.assertIn("app-release.apk app-release.apk.sha256", publish)


if __name__ == "__main__":
    unittest.main()
