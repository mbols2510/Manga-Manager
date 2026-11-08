"""Static security invariants of the Android workflows, on the PARSED YAML.

The signed tag path cannot run without publishing, so it is pinned down here (and by actionlint). The checker
functions return violations; MutationTests apply realistic weakenings to an in-memory deep copy of each workflow
and assert that the checker rejects every one, so weakening the checker itself is caught too.

Release topology (android-release.yml): build (no secrets) -> sign (fresh runner; the only job that may reference
secrets, in exactly one step; a throwaway-key rehearsal in dry runs) -> publish (tag only, contents: write).

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
RELEASE_ENV = "${{ " + TAG_EXPR + " }}"
RELEASE_IF = "env.RELEASE == 'true'"
DRY_IF = "env.RELEASE != 'true'"

B_CHECK_DRY = "tools/release_checks.sh build/release-out"
B_CHECK_REL = 'tools/release_checks.sh build/release-out --expect-version "$EXPECT_VERSION"'
UNSIGNED_ARTIFACT = "android-release-unsigned"
UNSIGNED_PATHS = [
    "android/build/release-out/app-release-unsigned.apk",
    "android/build/release-out/app-release-unsigned.apk.sha256",
    "android/build/release-out/mapping.txt",
    "android/build/release-out/mapping.txt.sha256",
]
SIGNED_ARTIFACT = "android-release"
SIGNED_PATHS = ["signed/app-release.apk", "signed/app-release.apk.sha256"]

SIGN_STEP = "Sign the release APK with apksigner (the only step with secrets)"
SIGN_RUN = "android/tools/sign_apk.sh unsigned/app-release-unsigned.apk signed/app-release.apk"
SIGN_ENV = {
    "KEYSTORE_BASE64": "${{ secrets.ANDROID_KEYSTORE_BASE64 }}",
    "ANDROID_KEYSTORE_PASSWORD": "${{ secrets.ANDROID_KEYSTORE_PASSWORD }}",
    "ANDROID_KEY_ALIAS": "${{ secrets.ANDROID_KEY_ALIAS }}",
    "ANDROID_KEY_PASSWORD": "${{ secrets.ANDROID_KEY_PASSWORD }}",
}
VERIFY_RUN = "sha256sum -c app-release-unsigned.apk.sha256 mapping.txt.sha256"
SIGNED_CHECK = ('python3 android/tools/apk_check.py --apk signed/app-release.apk --mapping unsigned/mapping.txt '
                '--build-tools "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION" --expect-signed')
SIGNED_CHECK_REL = SIGNED_CHECK + ' --expect-version "$EXPECT_VERSION"'
SHA_RUN = "sha256sum app-release.apk > app-release.apk.sha256 && cat app-release.apk.sha256"
INVARIANTS_RUN = "python3 -m unittest discover -s android/tools -p 'test_workflows.py' -v"

BANNED_TRIGGERS = {"pull_request_target", "workflow_run", "issue_comment", "schedule", "repository_dispatch",
                   "workflow_call"}
DOCKER_GUARD = "github.event_name != 'release' || startsWith(github.event.release.tag_name, 'v')"
SWALLOW = re.compile(r"\|\|\s*(true|:|exit 0)\b|\bset \+e\b")
EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.S)


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


def secret_uses(obj):
    """Every place `obj` touches the secrets context: the identifier `secrets` inside any ${{ }} expression
    (secrets.X, secrets['X'], toJSON(secrets)...) and any mapping key named `secrets` (secrets: inherit)."""
    found = []

    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if str(k) == "secrets":
                    found.append(f"key secrets: {v!r}")
                walk(k)
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
        elif isinstance(o, str):
            for expr in EXPRESSION.findall(o):
                if re.search(r"\bsecrets\b", expr):
                    found.append("${{" + expr + "}}")

    walk(obj)
    return found


def lines(value):
    return [ln.strip() for ln in str(value or "").splitlines() if ln.strip()]


def needs(job):
    n = job.get("needs")
    return [n] if isinstance(n, str) else list(n or [])


def common_problems(wf, name):
    """Rules for every Android workflow."""
    p = []
    if wf.get("permissions") != READ:
        p.append(f"{name}: workflow permissions must be exactly {READ}, got {wf.get('permissions')}")
    for t in sorted(BANNED_TRIGGERS & set(triggers(wf))):
        p.append(f"{name}: banned trigger {t}")
    if "secrets" in wf:
        p.append(f"{name}: no workflow-level secrets key")
    for job_id, job in (wf.get("jobs") or {}).items():
        if "uses" in job:
            p.append(f"{name}/{job_id}: job-level uses: (reusable workflow) is not allowed")
        if "secrets" in job:
            p.append(f"{name}/{job_id}: job-level secrets: is not allowed")
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
        if re.search(r"\bsecrets\b", run):
            p.append(f"{name}/{job_id}/{step.get('name')}: secrets inside run:")
    return p


def nonrelease_problems(wf, name):
    """android.yml and workflow-lint.yml: read-only everywhere, no secrets at all, checks cannot be swallowed."""
    p = common_problems(wf, name)
    for job_id, job in (wf.get("jobs") or {}).items():
        if "permissions" in job and job["permissions"] != READ:
            p.append(f"{name}/{job_id}: job permissions must be {READ}")
        if "continue-on-error" in job:
            p.append(f"{name}/{job_id}: continue-on-error is not allowed")
    for use in secret_uses(wf):
        p.append(f"{name}: must not use secrets ({use})")
    for job_id, step in all_steps(wf):
        run = str(step.get("run", ""))
        if "continue-on-error" in step:
            p.append(f"{name}/{job_id}/{step.get('name')}: continue-on-error is not allowed")
        if re.search(r"gradlew|release_checks\.sh|unittest|actionlint|ACTIONLINT", run) and SWALLOW.search(run):
            p.append(f"{name}/{job_id}/{step.get('name')}: a check step must not swallow failures")
    return p


def lint_problems(wf):
    p = nonrelease_problems(wf, "workflow-lint.yml")
    steps = [s for _, s in all_steps(wf) if "test_workflows.py" in str(s.get("run", ""))]
    if len(steps) != 1 or str(steps[0].get("run", "")).strip() != INVARIANTS_RUN or "if" in steps[0]:
        p.append(f"workflow-lint.yml: one invariants step running exactly {INVARIANTS_RUN!r}, unconditionally")
    return p


def _index(steps, pred):
    return next((i for i, s in enumerate(steps) if pred(s)), None)


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
    if set(jobs) != {"build", "sign", "publish"}:
        p.append(f"{name}: jobs must be exactly build, sign, publish; got {sorted(jobs)}")
        return p
    build, sign, publish = jobs["build"], jobs["sign"], jobs["publish"]

    # Nothing may swallow a failure anywhere in this workflow.
    for job_id, job in jobs.items():
        if "continue-on-error" in job:
            p.append(f"{name}/{job_id}: continue-on-error is not allowed")
        for s in job.get("steps") or []:
            if "continue-on-error" in s:
                p.append(f"{name}/{job_id}/{s.get('name')}: continue-on-error is not allowed")
            if SWALLOW.search(str(s.get("run", ""))):
                p.append(f"{name}/{job_id}/{s.get('name')}: must not swallow failures (|| true, set +e)")

    # Topology, permissions, conditions.
    for job_id, job in (("build", build), ("sign", sign)):
        if job.get("permissions") != READ:
            p.append(f"{name}/{job_id}: permissions must be exactly {READ}")
        if "if" in job:
            p.append(f"{name}/{job_id}: must run in both modes (no job if:)")
    if publish.get("permissions") != WRITE:
        p.append(f"{name}/publish: permissions must be exactly {WRITE}")
    if publish.get("if") != TAG_EXPR:
        p.append(f"{name}/publish: if must be exactly {TAG_EXPR!r}, got {publish.get('if')!r}")
    if needs(build) != [] or needs(sign) != ["build"] or needs(publish) != ["sign"]:
        p.append(f"{name}: needs must be exactly build -> sign -> publish")

    # Secrets: only the sign job's signing step, with exactly the four secrets in its env.
    for job_id, job in jobs.items():
        for key, value in job.items():
            if key != "steps" and secret_uses(value):
                p.append(f"{name}/{job_id}: secrets outside a step ({key})")
        for s in job.get("steps") or []:
            if secret_uses(s) and not (job_id == "sign" and s.get("name") == SIGN_STEP):
                p.append(f"{name}/{job_id}/{s.get('name')}: secrets are only allowed in {SIGN_STEP!r}")

    # Keystore paths stay under $RUNNER_TEMP, everywhere.
    for job_id, s in all_steps(wf):
        for path in re.findall(r"[^\s\"'=>]*\.(?:keystore|jks)\b", str(s.get("run", ""))):
            if not path.startswith("$RUNNER_TEMP/"):
                p.append(f"{name}/{job_id}/{s.get('name')}: keystore path outside $RUNNER_TEMP: {path}")

    p += _build_problems(name, build)
    p += _sign_problems(name, sign)
    p += _publish_problems(name, publish)
    return p


def _build_problems(name, build):
    p = []
    if build.get("env") != {"RELEASE": RELEASE_ENV}:
        p.append(f"{name}/build: env must be exactly RELEASE derived from the tag event")
    steps = build.get("steps") or []
    runs = [str(s.get("run", "")) for s in steps]
    gradle = [i for i, r in enumerate(runs) if "gradlew" in r and "assembleRelease" in r]
    if len(gradle) != 1 or "if" in steps[gradle[0]] or "env" in steps[gradle[0]]:
        p.append(f"{name}/build: exactly one release Gradle build, in both modes, without env or if")
    if any("--no-osv" in r for r in runs):
        p.append(f"{name}/build: the OSV scan must not be skipped in the build job")
    if any("sign_apk" in r or "apksigner" in r for r in runs):
        p.append(f"{name}/build: no signing in the build job")
    i_rel = _index(steps, lambda s: str(s.get("run", "")).strip() == B_CHECK_REL and s.get("if") == RELEASE_IF)
    i_dry = _index(steps, lambda s: str(s.get("run", "")).strip() == B_CHECK_DRY and s.get("if") == DRY_IF)
    if i_rel is None:
        p.append(f"{name}/build: release-mode step {B_CHECK_REL!r} (APK checks + OSV) with if {RELEASE_IF!r} missing")
    if i_dry is None:
        p.append(f"{name}/build: dry-run step {B_CHECK_DRY!r} with if {DRY_IF!r} missing")
    uploads = [i for i, s in enumerate(steps) if str(s.get("uses", "")).startswith("actions/upload-artifact@")]
    if len(uploads) != 1:
        p.append(f"{name}/build: exactly one artifact upload")
        return p
    up = steps[uploads[0]]
    w = up.get("with") or {}
    if "if" in up or w.get("name") != UNSIGNED_ARTIFACT or lines(w.get("path")) != UNSIGNED_PATHS \
            or w.get("if-no-files-found") != "error":
        p.append(f"{name}/build: upload must be {UNSIGNED_ARTIFACT!r} with exactly {UNSIGNED_PATHS}, in both modes")
    for label, i in (("gradle build", gradle[0] if gradle else None), ("release check", i_rel), ("dry check", i_dry)):
        if i is not None and i > uploads[0]:
            p.append(f"{name}/build: {label} must come before the upload")
    return p


def _sign_problems(name, sign):
    p = []
    env = sign.get("env") or {}
    if set(env) != {"RELEASE", "BUILD_TOOLS_VERSION"} or env.get("RELEASE") != RELEASE_ENV or \
            not re.fullmatch(r"\d+\.\d+\.\d+", str(env.get("BUILD_TOOLS_VERSION", ""))):
        p.append(f"{name}/sign: env must be exactly RELEASE and a pinned BUILD_TOOLS_VERSION X.Y.Z")
    steps = sign.get("steps") or []
    for s in steps:
        uses, run = str(s.get("uses", "")), str(s.get("run", ""))
        if re.search(r"\bgradlew?\b", run) or any(a in uses for a in ("setup-java", "setup-gradle", "actions/cache")):
            p.append(f"{name}/sign/{s.get('name')}: the sign job must not run Gradle or set up Java/Gradle/caches")
        if s.get("working-directory") or "cd " in run:
            pass
    checkouts = [s for s in steps if str(s.get("uses", "")).startswith("actions/checkout@")]
    if len(checkouts) != 1 or (checkouts[0].get("with") or {}).get("sparse-checkout") != "android/tools":
        p.append(f"{name}/sign: exactly one sparse checkout of android/tools")
    names = [s.get("name") for s in steps]

    def idx(pred):
        return _index(steps, pred)

    i_dl = idx(lambda s: str(s.get("uses", "")).startswith("actions/download-artifact@")
               and (s.get("with") or {}).get("name") == UNSIGNED_ARTIFACT
               and (s.get("with") or {}).get("path") == "unsigned")
    i_verify = idx(lambda s: str(s.get("run", "")).strip() == VERIFY_RUN and s.get("working-directory") == "unsigned"
                   and "if" not in s)
    i_sign = idx(lambda s: s.get("name") == SIGN_STEP)
    i_rehearse = idx(lambda s: "sign_apk.sh" in str(s.get("run", "")) and s.get("name") != SIGN_STEP)
    i_remove = idx(lambda s: 'rm -f "$RUNNER_TEMP/release.keystore"' in str(s.get("run", "")))
    i_chk_rel = idx(lambda s: str(s.get("run", "")).strip() == SIGNED_CHECK_REL and s.get("if") == RELEASE_IF)
    i_chk_dry = idx(lambda s: str(s.get("run", "")).strip() == SIGNED_CHECK and s.get("if") == DRY_IF)
    i_sha = idx(lambda s: str(s.get("run", "")).strip() == SHA_RUN and s.get("working-directory") == "signed"
                and "if" not in s)
    uploads = [i for i, s in enumerate(steps) if str(s.get("uses", "")).startswith("actions/upload-artifact@")]

    if i_dl is None:
        p.append(f"{name}/sign: download {UNSIGNED_ARTIFACT!r} into unsigned/ missing")
    if i_verify is None:
        p.append(f"{name}/sign: checksum verification {VERIFY_RUN!r} in unsigned/ missing")
    if i_sign is None:
        p.append(f"{name}/sign: step {SIGN_STEP!r} missing")
    else:
        s = steps[i_sign]
        if s.get("if") != RELEASE_IF:
            p.append(f"{name}/sign/{SIGN_STEP}: if must be exactly {RELEASE_IF!r}, got {s.get('if')!r}")
        if s.get("env") != SIGN_ENV:
            p.append(f"{name}/sign/{SIGN_STEP}: env must be exactly the four signing secrets")
        if str(s.get("run", "")).strip() != SIGN_RUN:
            p.append(f"{name}/sign/{SIGN_STEP}: must only run {SIGN_RUN!r}")
    if i_rehearse is None or steps[i_rehearse].get("if") != DRY_IF or secret_uses(steps[i_rehearse]):
        p.append(f"{name}/sign: a secret-free signing rehearsal with if {DRY_IF!r} is required")
    if i_remove is None or steps[i_remove].get("if") != "always()":
        p.append(f"{name}/sign: one keystore removal step with if exactly always()")
    if i_chk_rel is None or i_chk_dry is None:
        p.append(f"{name}/sign: signed-APK checks (apk_check.py --expect-signed, pinned build-tools) missing")
    if i_sha is None:
        p.append(f"{name}/sign: SHA-256 of the signed APK missing")
    order = [i_dl, i_verify, i_sign, i_remove, i_chk_rel, i_sha]
    if None not in order and order != sorted(order):
        p.append(f"{name}/sign: order must be download, verify, sign, remove keystore, check, SHA-256")
    if None not in (i_verify, i_rehearse) and i_rehearse < i_verify:
        p.append(f"{name}/sign: the rehearsal must come after the checksum verification")
    if len(uploads) != 1:
        p.append(f"{name}/sign: exactly one artifact upload")
    else:
        up = steps[uploads[0]]
        w = up.get("with") or {}
        if up.get("if") != RELEASE_IF or w.get("name") != SIGNED_ARTIFACT or lines(w.get("path")) != SIGNED_PATHS \
                or w.get("if-no-files-found") != "error":
            p.append(f"{name}/sign: upload must be {SIGNED_ARTIFACT!r} with exactly {SIGNED_PATHS}, release only")
        if None not in (i_chk_rel, i_sha) and uploads[0] < max(i_chk_rel, i_sha):
            p.append(f"{name}/sign: upload must come after the signed checks and the SHA-256")
    del names
    return p


def _publish_problems(name, publish):
    p = []
    steps = publish.get("steps") or []
    for s in steps:
        if str(s.get("uses", "")).startswith("actions/checkout@") or re.search(r"gradlew|\.sh\b", str(s.get("run", ""))):
            p.append(f"{name}/publish: must not check out or run project code")
    dls = [s for s in steps if str(s.get("uses", "")).startswith("actions/download-artifact@")]
    if len(dls) != 1 or (dls[0].get("with") or {}).get("name") != SIGNED_ARTIFACT:
        p.append(f"{name}/publish: must download exactly the {SIGNED_ARTIFACT!r} artifact")
    run = "\n".join(str(s.get("run", "")) for s in steps)
    if "sha256sum -c app-release.apk.sha256" not in run:
        p.append(f"{name}/publish: must re-verify the SHA-256")
    return p


def sign_script_problems(text):
    p = []
    if 'ks="$RUNNER_TEMP/release.keystore"' not in text:
        p.append("sign_apk.sh: keystore must be written to $RUNNER_TEMP/release.keystore")
    if not text.splitlines()[1:] or "set -euo pipefail" not in text:
        p.append("sign_apk.sh: set -euo pipefail missing")
    if "umask 077" not in text:
        p.append("sign_apk.sh: umask 077 missing")
    if "trap 'rm -f \"$ks\"' EXIT" not in text:
        p.append("sign_apk.sh: keystore removal trap missing")
    if "--ks-pass env:ANDROID_KEYSTORE_PASSWORD" not in text or "--key-pass env:ANDROID_KEY_PASSWORD" not in text:
        p.append("sign_apk.sh: passwords must be passed to apksigner as env:VAR references")
    if re.search(r"pass:", text):
        p.append("sign_apk.sh: no pass: arguments (password on the command line)")
    if re.search(r"(echo|printf)[^\n]*\$\{?(ANDROID_KEY(STORE)?_PASSWORD|KEYSTORE_BASE64)", text.replace(
            "printf '%s' \"$KEYSTORE_BASE64\" | base64 -d", "")) or re.search(r"set -\w*x|set \+e", text):
        p.append("sign_apk.sh: must never print a secret or disable errexit")
    if 'bt="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"' not in text or "sort -V" in text:
        p.append("sign_apk.sh: must use exactly $ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION (no version picking)")
    if "zipalign" not in text or "verify --print-certs" not in text:
        p.append("sign_apk.sh: must check alignment and verify the signature")
    if "grep -q '^ERROR'" not in text or "grep '^WARNING'" not in text:
        p.append("sign_apk.sh: must print apksigner WARNING lines and fail on ERROR lines")
    return p


def docker_problems(wf):
    job = (wf.get("jobs") or {}).get("publish") or {}
    if job.get("if") != DOCKER_GUARD:
        return [f"docker-publish.yml/publish: if must be exactly {DOCKER_GUARD!r}, got {job.get('if')!r}"]
    return []


class CurrentWorkflows(unittest.TestCase):
    def test_release_workflow(self):
        self.assertEqual(release_problems(load("android-release.yml")), [])

    def test_android_workflow(self):
        self.assertEqual(nonrelease_problems(load("android.yml"), "android.yml"), [])

    def test_lint_workflow_and_invariants_run_in_ci(self):
        self.assertEqual(lint_problems(load("workflow-lint.yml")), [])

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

    def test_secret_detection_helper(self):
        for text in ("${{ secrets.X }}", "${{ secrets['X'] }}", "${{ toJSON(secrets) }}", "${{secrets}}",
                     "a ${{ format('{0}', secrets.Y) }} b"):
            self.assertTrue(secret_uses({"env": {"A": text}}), text)
        self.assertTrue(secret_uses({"secrets": "inherit"}))
        for text in ("${{ github.ref }}", "the only step with secrets", "${{ env.SECRETS_DIR }}"):
            self.assertFalse(secret_uses({"name": text}), text)


def _job(wf, job):
    return wf["jobs"][job]


def _step(wf, job, pred):
    if isinstance(pred, str):
        name = pred
        pred = lambda s: s.get("name") == name  # noqa: E731
    return next(s for s in wf["jobs"][job]["steps"] if pred(s))


def _gradle(wf):
    return _step(wf, "build", lambda s: "assembleRelease" in str(s.get("run", "")))


def _on(wf):
    return wf["on"] if "on" in wf else wf[True]


def _upload(wf, job):
    return _step(wf, job, lambda s: str(s.get("uses", "")).startswith("actions/upload-artifact@"))


def _drop(wf, job, pred):
    wf["jobs"][job]["steps"] = [s for s in wf["jobs"][job]["steps"] if not pred(s)]


def _run_is(text):
    return lambda s: str(s.get("run", "")).strip() == text


RELEASE_MUTATIONS = {
    # Round 1 (reviewer's four plus more), ported to the three-job topology:
    "publish if widened to dispatch": lambda wf: _job(wf, "publish").update(
        {"if": TAG_EXPR + " || github.event_name == 'workflow_dispatch'"}),
    "top-level actions: write": lambda wf: wf["permissions"].update({"actions": "write"}),
    "top-level id-token: write": lambda wf: wf["permissions"].update({"id-token": "write"}),
    "build job id-token: write": lambda wf: _job(wf, "build")["permissions"].update({"id-token": "write"}),
    "workflow_run trigger": lambda wf: _on(wf).update({"workflow_run": {"workflows": ["Android"]}}),
    "pull_request trigger": lambda wf: _on(wf).update({"pull_request": None}),
    "pull_request_target trigger": lambda wf: _on(wf).update({"pull_request_target": None}),
    "issue_comment trigger": lambda wf: _on(wf).update({"issue_comment": None}),
    "schedule trigger": lambda wf: _on(wf).update({"schedule": [{"cron": "0 0 * * *"}]}),
    "workflow_call trigger": lambda wf: _on(wf).update({"workflow_call": None}),
    "dispatch input": lambda wf: _on(wf).update({"workflow_dispatch": {"inputs": {"sign": {"type": "boolean"}}}}),
    "any tag": lambda wf: _on(wf)["push"].update({"tags": ["*"]}),
    "any branch": lambda wf: _on(wf)["push"].update({"branches": ["**"]}),
    "sign contents: write": lambda wf: _job(wf, "sign").update({"permissions": WRITE}),
    "publish extra permission": lambda wf: _job(wf, "publish")["permissions"].update({"packages": "write"}),
    "publish needs build only": lambda wf: _job(wf, "publish").update({"needs": ["build"]}),
    "sign does not need build": lambda wf: _job(wf, "sign").pop("needs"),
    "publish checks out code": lambda wf: _job(wf, "publish")["steps"].insert(
        0, {"uses": "actions/checkout@v7.0.1", "with": {"persist-credentials": False}}),
    "signing step runs always": lambda wf: _step(wf, "sign", SIGN_STEP).update({"if": "always()"}),
    "signing step widened": lambda wf: _step(wf, "sign", SIGN_STEP).update({"if": RELEASE_IF + " || true"}),
    "signing step extra command": lambda wf: _step(wf, "sign", SIGN_STEP).update({"run": SIGN_RUN + "\nmake"}),
    "secret in gradle build env": lambda wf: _gradle(wf).update(
        {"env": {"ANDROID_KEY_PASSWORD": "${{ secrets.ANDROID_KEY_PASSWORD }}"}}),
    "gradle build in release mode only": lambda wf: _gradle(wf).update({"if": RELEASE_IF}),
    "secret in another step": lambda wf: _step(wf, "build", "Mode").update(
        {"env": {"X": "${{ secrets.ANDROID_KEY_ALIAS }}"}}),
    "secret in publish": lambda wf: _job(wf, "publish").update({"env": {"X": "${{ secrets.ANDROID_KEY_ALIAS }}"}}),
    "workflow-level secret env": lambda wf: wf.update({"env": {"X": "${{ secrets.ANDROID_KEYSTORE_BASE64 }}"}}),
    "expression in run": lambda wf: _step(wf, "build", "Mode").update({"run": "echo ${{ github.ref_name }}"}),
    "secret in run": lambda wf: _step(wf, "build", "Mode").update({"run": "echo secrets.ANDROID_KEY_ALIAS"}),
    "keystore into workspace": lambda wf: _step(wf, "build", "Mode").update(
        {"run": "printf '%s' \"$K\" | base64 -d > app/release.keystore"}),
    "keystore removal not always": lambda wf: _step(
        wf, "sign", lambda s: 'rm -f "$RUNNER_TEMP/release.keystore"' in str(s.get("run", ""))).update(
        {"if": RELEASE_IF}),
    "persist credentials": lambda wf: _job(wf, "build")["steps"][0]["with"].update({"persist-credentials": True}),
    "unpinned action": lambda wf: _job(wf, "build")["steps"][0].update({"uses": "actions/checkout@v7"}),
    "release flag from dispatch": lambda wf: _job(wf, "build").update(
        {"env": {"RELEASE": "${{ github.event_name == 'workflow_dispatch' }}"}}),
    "unsigned APK uploaded to release": lambda wf: _upload(wf, "sign")["with"].update(
        {"path": "unsigned/app-release-unsigned.apk"}),
    "no signed re-check": lambda wf: _drop(wf, "sign", _run_is(SIGNED_CHECK_REL)),
    # Round 2, review A(1): secrets via other expression forms, on any non-signing step or in android.yml.
    "toJSON(secrets) on the release check step": lambda wf: _step(wf, "build", _run_is(B_CHECK_REL)).update(
        {"env": {"ALL": "${{ toJSON(secrets) }}"}}),
    "secrets[...] on the gradle build": lambda wf: _gradle(wf).update(
        {"env": {"P": "${{ secrets['ANDROID_KEY_PASSWORD'] }}"}}),
    "secrets in the sign job env": lambda wf: _job(wf, "sign")["env"].update({"P": "${{ secrets.ANDROID_KEY_ALIAS }}"}),
    "secrets on the rehearsal": lambda wf: _step(wf, "sign", lambda s: "sign_apk.sh" in str(s.get("run", ""))
                                                 and s.get("name") != SIGN_STEP)["env"].update(
        {"ANDROID_KEY_PASSWORD": "${{ secrets.ANDROID_KEY_PASSWORD }}"}),
    "secrets in a step if": lambda wf: _step(wf, "build", "Mode").update({"if": "${{ secrets.X != '' }}"}),
    # A(2): reusable workflows and inherited secrets.
    "job-level uses with secrets: inherit": lambda wf: wf["jobs"].update(
        {"publish": {"permissions": WRITE, "needs": ["sign"], "if": TAG_EXPR,
                     "uses": "./.github/workflows/x.yml", "secrets": "inherit"}}),
    "job-level secrets key": lambda wf: _job(wf, "publish").update({"secrets": "inherit"}),
    "workflow-level secrets key": lambda wf: wf.update({"secrets": {"X": {"required": True}}}),
    # A(3): the release unsigned-check step (APK checks + OSV) cannot be dropped, skipped or reordered.
    "release unsigned check deleted": lambda wf: _drop(wf, "build", _run_is(B_CHECK_REL)),
    "release unsigned check skips OSV": lambda wf: _step(wf, "build", _run_is(B_CHECK_REL)).update(
        {"run": B_CHECK_REL + " --no-osv"}),
    "release unsigned check after upload": lambda wf: _job(wf, "build")["steps"].append(
        _job(wf, "build")["steps"].pop(_index(_job(wf, "build")["steps"], _run_is(B_CHECK_REL)))),
    "dry-run unsigned check deleted": lambda wf: _drop(wf, "build", _run_is(B_CHECK_DRY)),
    # A(4): failures cannot be swallowed.
    "continue-on-error on signed check": lambda wf: _step(wf, "sign", _run_is(SIGNED_CHECK_REL)).update(
        {"continue-on-error": True}),
    "continue-on-error on a job": lambda wf: _job(wf, "sign").update({"continue-on-error": True}),
    "|| true on the release check": lambda wf: _step(wf, "build", _run_is(B_CHECK_REL)).update(
        {"run": B_CHECK_REL + " || true"}),
    "set +e in the signing rehearsal": lambda wf: _step(
        wf, "sign", lambda s: "sign_apk.sh" in str(s.get("run", "")) and s.get("name") != SIGN_STEP).update(
        {"run": "set +e\n" + str(_step(wf, "sign", lambda s: "keytool" in str(s.get("run", ""))).get("run"))}),
    # A(5): exact upload paths.
    "extra file in the signed upload": lambda wf: _upload(wf, "sign")["with"].update(
        {"path": "\n".join(SIGNED_PATHS + ["unsigned/mapping.txt"])}),
    "signed upload by glob": lambda wf: _upload(wf, "sign")["with"].update({"path": "signed/*"}),
    "unsigned upload extra file": lambda wf: _upload(wf, "build")["with"].update(
        {"path": "\n".join(UNSIGNED_PATHS + ["android/app/build/outputs/apk/release/output-metadata.json"])}),
    # B: isolation of the signing job.
    "gradle in the sign job": lambda wf: _job(wf, "sign")["steps"].insert(1, {"name": "x", "run": "./gradlew tasks"}),
    "setup-java in the sign job": lambda wf: _job(wf, "sign")["steps"].insert(
        1, {"uses": "actions/setup-java@v6.1.0", "with": {"distribution": "temurin", "java-version": "17"}}),
    "setup-gradle in the sign job": lambda wf: _job(wf, "sign")["steps"].insert(
        1, {"uses": "gradle/actions/setup-gradle@v6.4.0"}),
    "cache in the sign job": lambda wf: _job(wf, "sign")["steps"].insert(
        1, {"uses": "actions/cache@v4.2.0", "with": {"path": "~/.gradle", "key": "k"}}),
    "full checkout in the sign job": lambda wf: _step(
        wf, "sign", lambda s: str(s.get("uses", "")).startswith("actions/checkout@"))["with"].pop("sparse-checkout"),
    "build-tools not pinned": lambda wf: _job(wf, "sign")["env"].update({"BUILD_TOOLS_VERSION": "latest"}),
    "no checksum verification": lambda wf: _drop(wf, "sign", _run_is(VERIFY_RUN)),
    "signing before verification": lambda wf: _job(wf, "sign")["steps"].insert(
        0, _job(wf, "sign")["steps"].pop(_index(_job(wf, "sign")["steps"], lambda s: s.get("name") == SIGN_STEP))),
    "signing in the build job": lambda wf: _job(wf, "build")["steps"].append(
        {"name": "sign", "if": RELEASE_IF, "run": SIGN_RUN}),
    "secrets in the build job signing": lambda wf: _job(wf, "build")["steps"].append(
        {"name": SIGN_STEP, "if": RELEASE_IF, "env": dict(SIGN_ENV), "run": SIGN_RUN}),
    "sign job if removed rehearsal": lambda wf: _drop(
        wf, "sign", lambda s: "sign_apk.sh" in str(s.get("run", "")) and s.get("name") != SIGN_STEP),
    "signed upload in dry runs": lambda wf: _upload(wf, "sign").pop("if"),
    "publish downloads unsigned": lambda wf: _step(
        wf, "publish", lambda s: str(s.get("uses", "")).startswith("actions/download-artifact@"))["with"].update(
        {"name": UNSIGNED_ARTIFACT}),
    "publish runs a script": lambda wf: _job(wf, "publish")["steps"].insert(
        1, {"name": "x", "run": "android/tools/sign_apk.sh a b"}),
}

SIGN_SCRIPT_MUTATIONS = {
    "keystore in workspace": lambda t: t.replace('ks="$RUNNER_TEMP/release.keystore"', 'ks="release.keystore"'),
    "password on command line": lambda t: t.replace("--ks-pass env:ANDROID_KEYSTORE_PASSWORD",
                                                    '--ks-pass pass:"$ANDROID_KEYSTORE_PASSWORD"'),
    "password echoed": lambda t: t + '\necho "$ANDROID_KEY_PASSWORD"\n',
    "keystore echoed": lambda t: t + '\necho "$KEYSTORE_BASE64"\n',
    "xtrace": lambda t: t.replace("set -euo pipefail", "set -xeuo pipefail"),
    "errexit off": lambda t: t + "\nset +e\n",
    "no strict mode": lambda t: t.replace("set -euo pipefail", "set -u"),
    "no trap": lambda t: t.replace("trap 'rm -f \"$ks\"' EXIT", ""),
    "no umask": lambda t: t.replace("umask 077", ""),
    "no verify": lambda t: t.replace("verify --print-certs", "verify"),
    "build-tools picked by sort -V": lambda t: t.replace(
        'bt="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"', 'bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)'),
    "apksigner warnings dropped": lambda t: t.replace("grep '^WARNING'", "grep -v '^WARNING'"),
    "apksigner errors ignored": lambda t: t.replace("grep -q '^ERROR'", "grep -q '^NOTHING'"),
}

NONRELEASE_MUTATIONS = {
    "id-token write": lambda wf: wf["permissions"].update({"id-token": "write"}),
    "job write": lambda wf: next(iter(wf["jobs"].values())).update({"permissions": WRITE}),
    "workflow_run": lambda wf: _on(wf).update({"workflow_run": {"workflows": ["x"]}}),
    "pull_request_target": lambda wf: _on(wf).update({"pull_request_target": None}),
    "secrets.X": lambda wf: next(iter(wf["jobs"].values())).update({"env": {"X": "${{ secrets.Y }}"}}),
    "toJSON(secrets)": lambda wf: next(iter(wf["jobs"].values())).update({"env": {"X": "${{ toJSON(secrets) }}"}}),
    "secrets['X'] on a step": lambda wf: next(iter(wf["jobs"].values()))["steps"][-1].update(
        {"env": {"X": "${{ secrets['Y'] }}"}}),
    "job-level uses + secrets inherit": lambda wf: wf["jobs"].update(
        {"reuse": {"uses": "./.github/workflows/x.yml", "secrets": "inherit"}}),
    "continue-on-error on a job": lambda wf: next(iter(wf["jobs"].values())).update({"continue-on-error": True}),
    "continue-on-error on a step": lambda wf: next(iter(wf["jobs"].values()))["steps"][-1].update(
        {"continue-on-error": True}),
}

ANDROID_MUTATIONS = {
    "|| true on the release checks": lambda wf: _step(
        wf, "build", lambda s: "release_checks.sh" in str(s.get("run", ""))).update(
        {"run": "tools/release_checks.sh build/release-checks || true"}),
    "set +e on the gradle build": lambda wf: _step(
        wf, "build", lambda s: "gradlew" in str(s.get("run", "")) and "assembleRelease" in str(s.get("run", ""))
    ).update({"run": "set +e\n./gradlew assembleRelease"}),
}

LINT_MUTATIONS = {
    "|| true on invariants": lambda wf: _step(wf, "actionlint", lambda s: "test_workflows.py" in str(s.get("run", "")))
    .update({"run": INVARIANTS_RUN + " || true"}),
    "continue-on-error on invariants": lambda wf: _step(
        wf, "actionlint", lambda s: "test_workflows.py" in str(s.get("run", ""))).update({"continue-on-error": True}),
    "invariants step conditional": lambda wf: _step(
        wf, "actionlint", lambda s: "test_workflows.py" in str(s.get("run", ""))).update({"if": "false"}),
    "invariants step removed": lambda wf: _drop(wf, "actionlint", lambda s: "test_workflows.py" in str(s.get("run", ""))),
    "|| true on actionlint": lambda wf: _step(wf, "actionlint", lambda s: '"$ACTIONLINT" -color' in str(s.get("run", "")))
    .update({"run": '"$ACTIONLINT" -color || true'}),
}


class MutationTests(unittest.TestCase):
    def _assert_rejected(self, original, mutations, checker):
        for label, mutate in mutations.items():
            with self.subTest(label):
                wf = copy.deepcopy(original)
                mutate(wf)
                self.assertNotEqual(wf, original, f"mutation {label!r} did not change anything")
                self.assertTrue(checker(wf), f"checker accepted mutation {label!r}")

    def test_release_checker_rejects_every_weakening(self):
        self._assert_rejected(load("android-release.yml"), RELEASE_MUTATIONS, release_problems)

    def test_nonrelease_checker_rejects_weakenings(self):
        for name in ("android.yml", "workflow-lint.yml"):
            self._assert_rejected(load(name), NONRELEASE_MUTATIONS, lambda wf, n=name: nonrelease_problems(wf, n))

    def test_android_checker_rejects_swallowed_checks(self):
        self._assert_rejected(load("android.yml"), ANDROID_MUTATIONS,
                              lambda wf: nonrelease_problems(wf, "android.yml"))

    def test_lint_checker_rejects_weakened_invariants_step(self):
        self._assert_rejected(load("workflow-lint.yml"), LINT_MUTATIONS, lint_problems)

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
