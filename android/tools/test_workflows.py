"""Static security invariants of the Android workflows, on the PARSED YAML.

The signed tag path cannot run without publishing, so it is pinned down here (and by actionlint). The checker
functions return violations; MutationTests apply realistic weakenings to an in-memory copy of each workflow and
assert that the checker rejects every one, so weakening the checker itself is caught too.

Needs PyYAML (preinstalled on GitHub's ubuntu runners). A missing module is an error, never a skip.
Run: python3 -m unittest discover -s android/tools -p 'test_*.py' -v
"""

import copy
import os
import re
import unittest

import yaml

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
WF = os.path.join(ROOT, ".github", "workflows")
TOOLS = os.path.dirname(os.path.abspath(__file__))

READ = {"contents": "read"}
WRITE = {"contents": "write"}
TAG_EXPR = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/android-v')"
RELEASE_IF = "env.RELEASE == 'true'"
SIGN_STEP = "Sign the release APK with apksigner (the only step with secrets)"
SIGN_RUN = "tools/sign_apk.sh build/release-out/app-release-unsigned.apk build/release-signed/app-release.apk"
SIGN_ENV = {
    "KEYSTORE_BASE64": "${{ secrets.ANDROID_KEYSTORE_BASE64 }}",
    "ANDROID_KEYSTORE_PASSWORD": "${{ secrets.ANDROID_KEYSTORE_PASSWORD }}",
    "ANDROID_KEY_ALIAS": "${{ secrets.ANDROID_KEY_ALIAS }}",
    "ANDROID_KEY_PASSWORD": "${{ secrets.ANDROID_KEY_PASSWORD }}",
}
BANNED_TRIGGERS = {"pull_request_target", "workflow_run", "issue_comment", "schedule", "repository_dispatch"}
DOCKER_GUARD = "github.event_name != 'release' || startsWith(github.event.release.tag_name, 'v')"


def load(name):
    with open(os.path.join(WF, name), encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def triggers(wf):
    # YAML 1.1 reads the bare key `on` as boolean True.
    on = wf.get("on", wf.get(True))
    if isinstance(on, str):
        return {on: None}
    if isinstance(on, list):
        return {k: None for k in on}
    return dict(on or {})


def all_steps(wf):
    for job_id, job in (wf.get("jobs") or {}).items():
        for step in job.get("steps") or []:
            yield job_id, step


def dump(obj):
    return yaml.safe_dump(obj)


def common_problems(wf, name):
    """Rules for every Android workflow."""
    p = []
    if wf.get("permissions") != READ:
        p.append(f"{name}: workflow permissions must be exactly {READ}, got {wf.get('permissions')}")
    for t in sorted(BANNED_TRIGGERS & set(triggers(wf))):
        p.append(f"{name}: banned trigger {t}")
    for job_id, step in all_steps(wf):
        uses = step.get("uses")
        if uses and not re.fullmatch(r"[\w.-]+/[\w./-]+@v\d+\.\d+\.\d+", uses):
            p.append(f"{name}/{job_id}: action not pinned to an exact tag: {uses}")
        if uses and uses.startswith("actions/checkout@") and \
                (step.get("with") or {}).get("persist-credentials") is not False:
            p.append(f"{name}/{job_id}: checkout must set persist-credentials: false")
        run = str(step.get("run", ""))
        if "${{" in run:
            p.append(f"{name}/{job_id}/{step.get('name')}: expression inside run:")
        if "secrets." in run:
            p.append(f"{name}/{job_id}/{step.get('name')}: secret inside run:")
    return p


def nonrelease_problems(wf, name):
    """android.yml and workflow-lint.yml: read-only everywhere, no secrets at all."""
    p = common_problems(wf, name)
    for job_id, job in (wf.get("jobs") or {}).items():
        if "permissions" in job and job["permissions"] != READ:
            p.append(f"{name}/{job_id}: job permissions must be {READ}")
    if "secrets." in dump(wf):
        p.append(f"{name}: must not reference secrets")
    return p


def release_problems(wf):
    name = "android-release.yml"
    p = common_problems(wf, name)

    # Triggers: tag push (plus path-limited feature/** dry runs) and a manual dry run without inputs.
    on = triggers(wf)
    if set(on) != {"push", "workflow_dispatch"}:
        p.append(f"{name}: triggers must be exactly push + workflow_dispatch, got {sorted(map(str, on))}")
    push = on.get("push") or {}
    if push.get("tags") != ["android-v*"]:
        p.append(f"{name}: push tags must be exactly ['android-v*']")
    if push.get("branches") != ["feature/**"]:
        p.append(f"{name}: push branches must be exactly ['feature/**'] (dry runs)")
    if set(push) - {"tags", "branches", "paths"}:
        p.append(f"{name}: unexpected push filters {sorted(set(push) - {'tags', 'branches', 'paths'})}")
    if on.get("workflow_dispatch") not in (None, {}):
        p.append(f"{name}: workflow_dispatch must have no inputs")
    if "env" in wf:
        p.append(f"{name}: no workflow-level env")

    jobs = wf.get("jobs") or {}
    if set(jobs) != {"build", "verify-artifact", "publish"}:
        p.append(f"{name}: jobs must be exactly build, verify-artifact, publish")
        return p
    for job_id in ("build", "verify-artifact"):
        if jobs[job_id].get("permissions") != READ:
            p.append(f"{name}/{job_id}: permissions must be exactly {READ}")
        if "if" in jobs[job_id]:
            p.append(f"{name}/{job_id}: must run in both modes (no job if:)")
    publish = jobs["publish"]
    if publish.get("permissions") != WRITE:
        p.append(f"{name}/publish: permissions must be exactly {WRITE}")
    if publish.get("if") != TAG_EXPR:
        p.append(f"{name}/publish: if must be exactly {TAG_EXPR!r}, got {publish.get('if')!r}")
    if publish.get("needs") != ["build", "verify-artifact"]:
        p.append(f"{name}/publish: needs must be [build, verify-artifact]")
    for step in publish.get("steps") or []:
        if str(step.get("uses", "")).startswith("actions/checkout@") or "gradlew" in str(step.get("run", "")):
            p.append(f"{name}/publish: must not check out or build project code")
    for job_id, job in jobs.items():
        if job_id != "build" and "secrets." in dump(job):
            p.append(f"{name}/{job_id}: secrets are only allowed in the build job's signing step")

    build = jobs["build"]
    if build.get("env") != {"RELEASE": "${{ " + TAG_EXPR + " }}"}:
        p.append(f"{name}/build: env must be exactly RELEASE derived from the tag event")
    steps = build.get("steps") or []
    names = [s.get("name") for s in steps]
    secret_steps = [s for s in steps if "secrets." in dump(s)]
    if [s.get("name") for s in secret_steps] != [SIGN_STEP]:
        p.append(f"{name}/build: secrets must appear only in step {SIGN_STEP!r}, "
                 f"found in {[s.get('name') for s in secret_steps]}")
    for s in secret_steps:
        if s.get("if") != RELEASE_IF:
            p.append(f"{name}/build/{s.get('name')}: if must be exactly {RELEASE_IF!r}, got {s.get('if')!r}")
        if s.get("env") != SIGN_ENV:
            p.append(f"{name}/build/{s.get('name')}: env must be exactly the four signing secrets")
        if str(s.get("run", "")).strip() != SIGN_RUN:
            p.append(f"{name}/build/{s.get('name')}: must only run {SIGN_RUN!r}")
    for s in steps:
        run = str(s.get("run", ""))
        env = s.get("env") or {}
        if "gradlew" in run and ("secrets." in dump(s) or any(k.startswith("ANDROID_KEY") for k in env)):
            p.append(f"{name}/build/{s.get('name')}: Gradle must never run with signing secrets")
        for path in re.findall(r"[^\s\"'=>]*\.(?:keystore|jks)\b", run):
            if not path.startswith("$RUNNER_TEMP/"):
                p.append(f"{name}/build/{s.get('name')}: keystore path outside $RUNNER_TEMP: {path}")
    gradle_builds = [s for s in steps if "gradlew" in str(s.get("run", "")) and "assembleRelease" in str(s.get("run", ""))]
    if len(gradle_builds) != 1 or "if" in gradle_builds[0] or "env" in gradle_builds[0]:
        p.append(f"{name}/build: exactly one release Gradle build, in both modes, without env or if")
    elif SIGN_STEP in names and names.index(gradle_builds[0].get("name")) > names.index(SIGN_STEP):
        p.append(f"{name}/build: Gradle must finish before the signing step")
    removes = [s for s in steps if 'rm -f "$RUNNER_TEMP/release.keystore"' in str(s.get("run", ""))]
    if len(removes) != 1 or removes[0].get("if") != "always()":
        p.append(f"{name}/build: one keystore removal step with if exactly always()")
    elif SIGN_STEP in names and names.index(removes[0].get("name")) < names.index(SIGN_STEP):
        p.append(f"{name}/build: keystore removal must come after signing")
    signed_checks = [s for s in steps if "--expect-signed" in str(s.get("run", "")) and s.get("if") == RELEASE_IF]
    if not signed_checks or SIGN_STEP not in names or names.index(signed_checks[0].get("name")) < names.index(SIGN_STEP):
        p.append(f"{name}/build: the signed APK must be re-checked (--expect-signed) after signing")
    uploads = [s for s in steps if str(s.get("uses", "")).startswith("actions/upload-artifact@")]
    release_uploads = [s for s in uploads if s.get("if") == RELEASE_IF]
    path = str(release_uploads[0].get("with", {}).get("path")) if len(release_uploads) == 1 else ""
    if "release-signed" not in path or "unsigned" in path or "release-out" in path:
        p.append(f"{name}/build: the release upload must carry only the signed APK and its SHA-256")
    return p


def sign_script_problems(text):
    p = []
    if 'ks="$RUNNER_TEMP/release.keystore"' not in text:
        p.append("sign_apk.sh: keystore must be written to $RUNNER_TEMP/release.keystore")
    if "umask 077" not in text:
        p.append("sign_apk.sh: umask 077 missing")
    if "trap 'rm -f \"$ks\"' EXIT" not in text:
        p.append("sign_apk.sh: keystore removal trap missing")
    if "--ks-pass env:ANDROID_KEYSTORE_PASSWORD" not in text or "--key-pass env:ANDROID_KEY_PASSWORD" not in text:
        p.append("sign_apk.sh: passwords must be passed to apksigner as env:VAR references")
    if re.search(r"pass:", text):
        p.append("sign_apk.sh: no pass: arguments (password on the command line)")
    if re.search(r"(echo|printf)[^\n]*\$\{?(ANDROID_KEY(STORE)?_PASSWORD|KEYSTORE_BASE64)", text.replace(
            "printf '%s' \"$KEYSTORE_BASE64\" | base64 -d", "")) or re.search(r"set -\w*x", text):
        p.append("sign_apk.sh: must never print a secret")
    if "zipalign" not in text or "verify --print-certs" not in text:
        p.append("sign_apk.sh: must check alignment and verify the signature")
    return p


def docker_problems(wf):
    job = (wf.get("jobs") or {}).get("publish") or {}
    if job.get("if") != DOCKER_GUARD:
        return [f"docker-publish.yml/publish: if must be exactly {DOCKER_GUARD!r}, got {job.get('if')!r}"]
    return []


class CurrentWorkflows(unittest.TestCase):
    def test_release_workflow(self):
        self.assertEqual(release_problems(load("android-release.yml")), [])

    def test_android_and_lint_workflows(self):
        for name in ("android.yml", "workflow-lint.yml"):
            self.assertEqual(nonrelease_problems(load(name), name), [])

    def test_sign_script(self):
        with open(os.path.join(TOOLS, "sign_apk.sh"), encoding="utf-8") as fh:
            self.assertEqual(sign_script_problems(fh.read()), [])

    def test_docker_publish_skips_android_releases(self):
        self.assertEqual(docker_problems(load("docker-publish.yml")), [])

    def test_release_notes_have_checksum_and_debug_warning(self):
        run = "\n".join(str(s.get("run", "")) for s in load("android-release.yml")["jobs"]["publish"]["steps"])
        for needle in ("SHA-256", "debug APK", "never be used with a real device token",
                       "app-release.apk app-release.apk.sha256", "GITHUB_TOKEN"):
            self.assertIn(needle, run)

    def test_invariants_run_in_ci(self):
        runs = [str(s.get("run", "")) for _, s in all_steps(load("workflow-lint.yml"))]
        self.assertTrue(any("test_workflows.py" in r for r in runs))


def _step(wf, name):
    return next(s for s in wf["jobs"]["build"]["steps"] if s.get("name") == name)


def _gradle(wf):
    return next(s for s in wf["jobs"]["build"]["steps"] if "assembleRelease" in str(s.get("run", "")))


def _on(wf):
    return wf["on"] if "on" in wf else wf[True]


def _release_upload(wf):
    return next(s for s in wf["jobs"]["build"]["steps"]
                if str(s.get("uses", "")).startswith("actions/upload-artifact@") and s.get("if") == RELEASE_IF)


RELEASE_MUTATIONS = {
    # The four weakenings the substring-based version accepted (review of Task 13):
    "publish if widened to dispatch": lambda wf: wf["jobs"]["publish"].update(
        {"if": TAG_EXPR + " || github.event_name == 'workflow_dispatch'"}),
    "top-level actions: write": lambda wf: wf["permissions"].update({"actions": "write"}),
    "top-level id-token: write": lambda wf: wf["permissions"].update({"id-token": "write"}),
    "build job id-token: write": lambda wf: wf["jobs"]["build"]["permissions"].update({"id-token": "write"}),
    "workflow_run trigger": lambda wf: _on(wf).update({"workflow_run": {"workflows": ["Android"]}}),
    # More:
    "pull_request trigger": lambda wf: _on(wf).update({"pull_request": None}),
    "pull_request_target trigger": lambda wf: _on(wf).update({"pull_request_target": None}),
    "issue_comment trigger": lambda wf: _on(wf).update({"issue_comment": None}),
    "schedule trigger": lambda wf: _on(wf).update({"schedule": [{"cron": "0 0 * * *"}]}),
    "dispatch input": lambda wf: _on(wf).update({"workflow_dispatch": {"inputs": {"sign": {"type": "boolean"}}}}),
    "any tag": lambda wf: _on(wf)["push"].update({"tags": ["*"]}),
    "any branch": lambda wf: _on(wf)["push"].update({"branches": ["**"]}),
    "verify-artifact contents: write": lambda wf: wf["jobs"]["verify-artifact"].update({"permissions": WRITE}),
    "publish extra permission": lambda wf: wf["jobs"]["publish"]["permissions"].update({"packages": "write"}),
    "publish without needs": lambda wf: wf["jobs"]["publish"].update({"needs": ["build"]}),
    "publish checks out code": lambda wf: wf["jobs"]["publish"]["steps"].insert(
        0, {"uses": "actions/checkout@v7.0.1", "with": {"persist-credentials": False}}),
    "signing step runs always": lambda wf: _step(wf, SIGN_STEP).update({"if": "always()"}),
    "signing step widened": lambda wf: _step(wf, SIGN_STEP).update({"if": RELEASE_IF + " || true"}),
    "signing step extra command": lambda wf: _step(wf, SIGN_STEP).update({"run": SIGN_RUN + "\n./gradlew assemble"}),
    "secret in gradle build env": lambda wf: _gradle(wf).update(
        {"env": {"ANDROID_KEY_PASSWORD": "${{ secrets.ANDROID_KEY_PASSWORD }}"}}),
    "gradle build in release mode only": lambda wf: _gradle(wf).update({"if": RELEASE_IF}),
    "secret in another step": lambda wf: _step(wf, "Mode").update({"env": {"X": "${{ secrets.ANDROID_KEY_ALIAS }}"}}),
    "secret in publish": lambda wf: wf["jobs"]["publish"].update({"env": {"X": "${{ secrets.ANDROID_KEY_ALIAS }}"}}),
    "workflow-level secret env": lambda wf: wf.update({"env": {"X": "${{ secrets.ANDROID_KEYSTORE_BASE64 }}"}}),
    "expression in run": lambda wf: _step(wf, "Mode").update({"run": "echo ${{ github.ref_name }}"}),
    "secret in run": lambda wf: _step(wf, "Mode").update({"run": "echo secrets.ANDROID_KEY_ALIAS"}),
    "keystore into workspace": lambda wf: _step(wf, "Mode").update(
        {"run": "printf '%s' \"$K\" | base64 -d > app/release.keystore"}),
    "keystore removal not always": lambda wf: next(
        s for s in wf["jobs"]["build"]["steps"] if 'rm -f "$RUNNER_TEMP/release.keystore"' in str(s.get("run", ""))
    ).update({"if": RELEASE_IF}),
    "persist credentials": lambda wf: wf["jobs"]["build"]["steps"][0]["with"].update({"persist-credentials": True}),
    "unpinned action": lambda wf: wf["jobs"]["build"]["steps"][0].update({"uses": "actions/checkout@v7"}),
    "release flag from dispatch": lambda wf: wf["jobs"]["build"].update(
        {"env": {"RELEASE": "${{ github.event_name == 'workflow_dispatch' }}"}}),
    "unsigned APK uploaded to release": lambda wf: _release_upload(wf)["with"].update(
        {"path": "android/build/release-out/app-release-unsigned.apk"}),
    "no signed re-check": lambda wf: wf["jobs"]["build"].update({"steps": [
        s for s in wf["jobs"]["build"]["steps"] if "--expect-signed" not in str(s.get("run", ""))
        or s.get("if") != RELEASE_IF]}),
}

SIGN_SCRIPT_MUTATIONS = {
    "keystore in workspace": lambda t: t.replace('ks="$RUNNER_TEMP/release.keystore"', 'ks="release.keystore"'),
    "password on command line": lambda t: t.replace("--ks-pass env:ANDROID_KEYSTORE_PASSWORD",
                                                    '--ks-pass pass:"$ANDROID_KEYSTORE_PASSWORD"'),
    "password echoed": lambda t: t + '\necho "$ANDROID_KEY_PASSWORD"\n',
    "keystore echoed": lambda t: t + '\necho "$KEYSTORE_BASE64"\n',
    "xtrace": lambda t: t.replace("set -euo pipefail", "set -xeuo pipefail"),
    "no trap": lambda t: t.replace("trap 'rm -f \"$ks\"' EXIT", ""),
    "no umask": lambda t: t.replace("umask 077", ""),
    "no verify": lambda t: t.replace("verify --print-certs", "verify"),
}

NONRELEASE_MUTATIONS = {
    "id-token write": lambda wf: wf["permissions"].update({"id-token": "write"}),
    "job write": lambda wf: next(iter(wf["jobs"].values())).update({"permissions": WRITE}),
    "workflow_run": lambda wf: _on(wf).update({"workflow_run": {"workflows": ["x"]}}),
    "pull_request_target": lambda wf: _on(wf).update({"pull_request_target": None}),
    "secret": lambda wf: next(iter(wf["jobs"].values())).update({"env": {"X": "${{ secrets.Y }}"}}),
}


class MutationTests(unittest.TestCase):
    def test_release_checker_rejects_every_weakening(self):
        original = load("android-release.yml")
        for label, mutate in RELEASE_MUTATIONS.items():
            with self.subTest(label):
                wf = copy.deepcopy(original)
                mutate(wf)
                self.assertNotEqual(wf, original, f"mutation {label!r} did not change anything")
                self.assertTrue(release_problems(wf), f"checker accepted mutation {label!r}")

    def test_nonrelease_checker_rejects_weakenings(self):
        for name in ("android.yml", "workflow-lint.yml"):
            original = load(name)
            for label, mutate in NONRELEASE_MUTATIONS.items():
                with self.subTest(f"{name}: {label}"):
                    wf = copy.deepcopy(original)
                    mutate(wf)
                    self.assertNotEqual(wf, original)
                    self.assertTrue(nonrelease_problems(wf, name), f"{name}: checker accepted {label!r}")

    def test_sign_script_checker_rejects_weakenings(self):
        with open(os.path.join(TOOLS, "sign_apk.sh"), encoding="utf-8") as fh:
            original = fh.read()
        for label, mutate in SIGN_SCRIPT_MUTATIONS.items():
            with self.subTest(label):
                text = mutate(original)
                self.assertNotEqual(text, original)
                self.assertTrue(sign_script_problems(text), f"checker accepted {label!r}")

    def test_docker_checker_rejects_missing_or_widened_guard(self):
        original = load("docker-publish.yml")
        for value in (None, DOCKER_GUARD + " || true", "always()"):
            with self.subTest(str(value)):
                wf = copy.deepcopy(original)
                if value is None:
                    wf["jobs"]["publish"].pop("if", None)
                else:
                    wf["jobs"]["publish"]["if"] = value
                self.assertTrue(docker_problems(wf), value)


if __name__ == "__main__":
    unittest.main()
