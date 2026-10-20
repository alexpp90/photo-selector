"""Tests for scripts/verification_record.py — run with `python3 -m unittest discover scripts/tests`."""

from __future__ import annotations

import getpass
import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import verification_record as vr  # noqa: E402

# The exact line shapes scripts/run_tests.sh's record()/skip() write.
RESULTS = "\n".join([
    "PASS|gradlew testDebugUnitTest|android.yml:unit-tests|android.yml:unit-tests|",
    "WARN|gradlew lintDebug (advisory — does not fail CI)|android.yml:lint|android.yml:lint|",
    "SKIP|instrumented tests|no device/emulator attached||true",
    "SKIP|swift test (macos-desktop)|macOS native product requires Darwin runner|macos.yml:swift-test|false",
    "SKIP|Linux Desktop gates|no changes under products/linux-desktop/ (use --linux or --all to force)|linux.yml|false",
    "PASS|readme parity|repo-hygiene.yml:hygiene|repo-hygiene.yml:hygiene|",
    "PASS|requirements traceability|framework.yml:framework|framework.yml:framework|",
    "FAIL|flake8 src/ tests/|desktop.yml:lint|desktop.yml:lint|",
])


def gate(status, name, group, detail="", ci="", unrunnable=False):
    return {"status": status, "name": name, "detail": detail, "ci": ci,
            "unrunnable": unrunnable, "group": group}


def record(gates, tree="t" * 40, at="2026-10-16T08:00:00Z", **machine):
    m = {"model": "ThinkPad T14 Gen 1", "cpu": "Intel(R) Core(TM) i7-10610U", "os": "Debian GNU/Linux 13 (trixie)",
         "os_family": "debian", "arch": "x86_64", "virtualized": None}
    m.update(machine)
    return {"recorded_at": at, "args": ["--linux"], "exit_code": 0, "duration_s": 75, "machine": m,
            "environment": {"kind": "Claude Code", "source": "detected"}, "toolchains": {"Python": "3.13.5"},
            "android_devices": [], "git": {"tree": tree, "head": "h" * 40, "head_tree": "x" * 40},
            "gates": gates}


class ParseResultsTest(unittest.TestCase):
    def test_line_shapes_and_groups(self):
        gates = {g["name"]: g for g in vr.parse_results(RESULTS)}
        self.assertEqual(gates["gradlew testDebugUnitTest"]["group"], "android")
        self.assertEqual(gates["instrumented tests"]["group"], "android")
        self.assertTrue(gates["instrumented tests"]["unrunnable"])
        swift = gates["swift test (macos-desktop)"]
        self.assertEqual((swift["group"], swift["ci"], swift["unrunnable"]), ("macos", "macos.yml:swift-test", False))
        self.assertEqual(swift["detail"], "macOS native product requires Darwin runner")
        self.assertEqual(gates["requirements traceability"]["group"], "overlay")
        self.assertEqual(gates["flake8 src/ tests/"]["group"], "desktop")

    def test_old_three_field_lines_still_parse(self):
        gates = vr.parse_results("PASS|swift test (products/macos-desktop)|macos.yml:swift-test\n")
        self.assertEqual(gates[0]["group"], "macos")


class StatusTest(unittest.TestCase):
    def test_group_statuses(self):
        s = vr.statuses(vr.parse_results(RESULTS))
        self.assertEqual(s["android"], "partial")       # instrumented skipped as unrunnable
        self.assertEqual(s["macos"], "not run")          # not Darwin
        self.assertEqual(s["linux"], "not in scope")     # no changes
        self.assertEqual(s["desktop"], "failed")
        self.assertEqual(s["repo"], "passed")

    def test_partial_ci_reference_is_partial(self):
        g = [gate("PASS", "pytest (products/linux-desktop)", "linux", ci="linux.yml:test (partial — no GTK 4)")]
        self.assertEqual(vr.group_status(g), "partial")

    def test_later_skip_does_not_hide_an_earlier_pass(self):
        first = record([gate("PASS", "gradlew testDebugUnitTest", "android", ci="android.yml:unit-tests")],
                       at="2026-10-16T08:00:00Z")
        second = record([gate("SKIP", "gradlew testDebugUnitTest", "android", detail="--quick", unrunnable=True),
                         gate("PASS", "gradlew connectedDebugAndroidTest", "android",
                              ci="android.yml:instrumented-tests")],
                        at="2026-10-16T09:00:00Z")
        merged = {g["name"]: g["status"] for g in vr.merge_gates([second, first])}
        self.assertEqual(merged, {"gradlew testDebugUnitTest": "PASS", "gradlew connectedDebugAndroidTest": "PASS"})

    def test_scope_skip_dropped_when_group_ran_elsewhere(self):
        a = record([gate("SKIP", "Android gates", "android", detail="no changes under products/android/")])
        b = record([gate("PASS", "gradlew testDebugUnitTest", "android", ci="android.yml:unit-tests")],
                   at="2026-10-16T09:00:00Z")
        self.assertEqual([g["name"] for g in vr.merge_gates([a, b])], ["gradlew testDebugUnitTest"])


class RenderAndUpsertTest(unittest.TestCase):
    def test_block_carries_data_and_hides_overlay_gate_names(self):
        block = vr.render_block([record(vr.parse_results(RESULTS))])
        self.assertIn("ThinkPad T14 Gen 1", block)
        self.assertNotIn("requirements traceability", block)
        self.assertIn("Maintainer framework", block)
        data = json.loads(vr.DATA_RE.search(block).group(1))
        self.assertEqual(data["tree"], "t" * 40)
        self.assertEqual(data["groups"]["macos"], "not run")

    def test_device_suite_skipped_without_device_does_not_make_android_partial(self):
        # The device suite is on demand: a missing device is a stated gap, not a partial run.
        gates = vr.parse_results("\n".join([
            "PASS|gradlew testDebugUnitTest|android.yml:unit-tests|android.yml:unit-tests|",
            "SKIP|instrumented tests|no device/emulator attached||false",
        ]))
        self.assertEqual(vr.group_status([g for g in gates if g["group"] == "android"]), "passed")
        self.assertIn("no device/emulator attached", vr.render_block([record(gates)]))

    def test_unrequested_device_suite_points_to_android_device(self):
        gates = vr.parse_results("PASS|gradlew testDebugUnitTest|android.yml:unit-tests|android.yml:unit-tests|")
        self.assertIn("run on demand with `--android-device`", vr.render_block([record(gates)]))

    def test_upsert_replaces_inserts_and_appends(self):
        template = (f"## Verification\n\n{vr.BEGIN}\n_No record yet_ <!-- pst-verification:placeholder -->\n"
                    f"{vr.END}\n\n### Manual testing\n")
        one = vr.upsert_block(template, "mac", "MAC v1")
        self.assertNotIn("placeholder", one)
        self.assertIn("### Manual testing", one)
        two = vr.upsert_block(one, "thinkpad", "TP v1")
        three = vr.upsert_block(two, "mac", "MAC v2")
        self.assertIn("MAC v2", three)
        self.assertNotIn("MAC v1", three)
        self.assertLess(three.index("MAC v2"), three.index("TP v1"))  # position kept
        self.assertLess(three.index("TP v1"), three.index(vr.END))
        appended = vr.upsert_block("Plain description from gh pr create --fill", "mac", "MAC")
        self.assertTrue(appended.startswith("Plain description"))
        self.assertIn(vr.BEGIN, appended)


class TemplateTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]

    def test_committed_template_carries_the_markers(self):
        section = vr.template_section(self.ROOT)
        self.assertIsNotNone(section)
        self.assertIn("### Manual testing", section)

    def test_fill_description_gets_the_whole_section(self):
        body = vr.upsert_block("feat: thing\n\nCommit text.", "mac", "MAC", self.ROOT)
        self.assertTrue(body.startswith("feat: thing"))
        self.assertIn("### Not tested locally", body)
        self.assertIn("MAC", body)
        self.assertNotIn("placeholder", body)
        self.assertEqual(body.count(vr.BEGIN), 1)

    def test_manual_rows_counts_only_filled_rows(self):
        template = (self.ROOT / ".github/pull_request_template.md").read_text()
        self.assertEqual(vr.manual_rows(template), 0)
        filled = template.replace("|---|---|---|---|\n",
                                  "|---|---|---|---|\n| PhotoTok | Pixel 8 | swipe feed | ok |\n")
        self.assertEqual(vr.manual_rows(filled), 1)


class PrivacyTest(unittest.TestCase):
    def test_scrub_removes_paths_host_and_user(self):
        root = Path("/Users/someone/Coding/repo")
        with mock.patch.object(socket, "gethostname", return_value="MacBook-Air-von-Someone.local"), \
                mock.patch.object(getpass, "getuser", return_value="someone"), \
                mock.patch.object(os.path, "expanduser", return_value="/Users/someone"), \
                mock.patch.object(vr.platform, "system", return_value="Linux"):
            tokens = vr.private_tokens(root)
        text = vr.scrub({"g": ["format corpus present (/Users/someone/Coding/repo/x)", "macbook-air-von-someone",
                               "/Users/someone/.cache", "built by someone"]}, tokens)
        flat = json.dumps(text)
        self.assertNotIn("someone", flat.lower())
        self.assertIn("<repo>/x", flat)
        self.assertIn("~/.cache", flat)

    def test_machine_info_holds_no_host_or_user(self):
        info = json.dumps(vr.scrub(vr.machine_info(), vr.private_tokens(None)))
        host = socket.gethostname().split(".")[0]
        if len(host) >= 3:
            self.assertNotIn(host.lower(), info.lower())


class EvaluateTest(unittest.TestCase):
    def body(self, *records):
        blocks = "\n".join(vr.wrap_block(vr.machine_slug(r["machine"]), vr.render_block([r])) for r in records)
        return (f"## Verification\n{vr.BEGIN}\n{blocks}\n{vr.END}\n"
                "### Manual testing on real hardware\n| a | b |\n|---|---|\n")

    def titles(self, findings, level=None):
        return [t for lv, t, _ in findings if level in (None, lv)]

    def test_no_record_warns(self):
        self.assertEqual(self.titles(vr.evaluate("Some text", "t" * 40, [], False), "warning"),
                         ["No verification record"])

    def test_bot_is_exempt(self):
        self.assertEqual(self.titles(vr.evaluate("", None, None, True)), ["Bot PR"])

    def test_untested_product_warns_with_where_it_runs(self):
        linux = record([gate("PASS", "pytest (products/linux-desktop)", "linux", ci="linux.yml:test")])
        findings = vr.evaluate(self.body(linux), "t" * 40,
                               ["products/linux-desktop/src/a.py", "products/macos-desktop/src/b.swift"], False)
        warnings = [m for lv, t, m in findings if lv == "warning"]
        self.assertEqual(len(warnings), 1)
        self.assertIn("macOS Desktop", warnings[0])
        self.assertIn("needs a Mac", warnings[0])
        self.assertIn("No manual testing", self.titles(findings, "notice"))

    def test_two_machines_cover_two_products(self):
        linux = record([gate("PASS", "pytest (products/linux-desktop)", "linux", ci="linux.yml:test")])
        mac = record([gate("PASS", "swift test (products/macos-desktop)", "macos", ci="macos.yml:swift-test")],
                     model="MacBook Air (Mac16,12)", cpu="Apple M4", os="macOS 26.1", os_family="macos")
        findings = vr.evaluate(self.body(linux, mac), "t" * 40,
                               ["products/linux-desktop/a.py", "products/macos-desktop/b.swift"], False)
        self.assertEqual(self.titles(findings, "warning"), [])

    def test_stale_tree_warns(self):
        linux = record([gate("PASS", "pytest (products/linux-desktop)", "linux", ci="linux.yml:test")])
        findings = vr.evaluate(self.body(linux), "n" * 40, ["products/linux-desktop/a.py"], False)
        self.assertIn("Stale record", self.titles(findings, "warning"))
        self.assertIn("Blitzlens (Linux Desktop): not verified", self.titles(findings, "warning"))

    def test_sandbox_only_is_reported(self):
        vm = record([gate("PASS", "readme parity", "repo", ci="repo-hygiene.yml:hygiene")],
                    model=None, cpu=None, os="Ubuntu 22.04.5 LTS", os_family="ubuntu", virtualized="vm-other")
        vm["environment"] = {"kind": "Claude Cowork sandbox", "source": "detected"}
        self.assertIn("Sandbox only", self.titles(vr.evaluate(self.body(vm), "t" * 40, ["README.md"], False)))


GH_STUB = """#!{python}
import json, os, sys
args = sys.argv[1:]
with open(os.environ["GH_STUB_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["pr", "view"]:
    print(json.dumps({{"number": 7, "body": "Plain description", "headRefOid": "h" * 40,
                      "url": "https://github.com/octo/repo/pull/7"}}))
elif args[:2] == ["pr", "edit"]:
    if os.environ.get("GH_STUB_EDIT_FAILS"):
        sys.exit("GraphQL: Projects (classic) is being deprecated in favor of the new Projects "
                 "experience. (repository.pullRequest.projectCards)")
elif args[:1] == ["api"]:
    if os.environ.get("GH_STUB_API_FAILS"):
        sys.exit("HTTP 403: Resource not accessible by integration")
    body = next(a for a in args if a.startswith("body=@"))[len("body=@"):]
    with open(os.environ["GH_STUB_LOG"] + ".body", "w", encoding="utf-8") as out:
        out.write(open(body, encoding="utf-8").read())
"""


class PrSubcommandTest(unittest.TestCase):
    """`pr` against a stubbed gh: gh pr edit first, the REST PATCH when it fails."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        tmp = Path(self.tmp.name)
        (tmp / "bin").mkdir()
        gh = tmp / "bin" / "gh"
        gh.write_text(GH_STUB.format(python=sys.executable))
        gh.chmod(0o755)
        self.log = tmp / "gh.log"
        self.env = {"PATH": f"{tmp / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
                    "GH_STUB_LOG": str(self.log)}
        self.root = tmp / "repo"
        self.root.mkdir()
        records = [record([gate("PASS", "pytest (products/linux-desktop)", "linux")])]
        for target, value in (("records_for_here", mock.Mock(return_value=("thinkpad", records, True))),
                              ("git", mock.Mock(return_value=None))):
            patcher = mock.patch.object(vr, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def tearDown(self):
        self.tmp.cleanup()

    def pr(self, **flags):
        env = dict(self.env, **{k: "1" for k in flags})
        out = io.StringIO()
        with mock.patch.dict(os.environ, env), redirect_stdout(out):
            try:
                code = vr.main(["--root", str(self.root), "pr"])
            except SystemExit as exc:
                code = exc.code
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        return code, out.getvalue(), calls

    def test_pr_edit_success_needs_no_fallback(self):
        code, out, calls = self.pr()
        self.assertEqual(code, 0)
        self.assertEqual([c[:2] for c in calls], [["pr", "view"], ["pr", "edit"]])
        self.assertIn("https://github.com/octo/repo/pull/7", out)

    def test_pr_edit_failure_falls_back_to_rest_patch(self):
        code, out, calls = self.pr(GH_STUB_EDIT_FAILS=True)
        self.assertEqual(code, 0)
        self.assertEqual(calls[-1][:5], ["api", "--silent", "-X", "PATCH", "repos/octo/repo/pulls/7"])
        self.assertIn("projectCards", out)  # gh's reason is shown, not swallowed
        body = Path(f"{self.log}.body").read_text()
        self.assertTrue(body.startswith("Plain description"))
        self.assertIn(vr.BEGIN, body)

    def test_both_failing_exits_with_both_reasons(self):
        code, _, calls = self.pr(GH_STUB_EDIT_FAILS=True, GH_STUB_API_FAILS=True)
        self.assertIsInstance(code, str)  # sys.exit(message): exit status 1
        self.assertIn("projectCards", code)
        self.assertIn("HTTP 403", code)
        self.assertEqual(calls[-1][0], "api")


class EndToEndTest(unittest.TestCase):
    """write → render → commit → check: the tested tree equals the commit's tree."""

    def git(self, *args):
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.com", *args],
                       cwd=self.root, check=True, capture_output=True)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.git("init", "-q")
        (self.root / "products/linux-desktop").mkdir(parents=True)
        (self.root / "products/linux-desktop/a.py").write_text("x = 1\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.root, capture_output=True,
                                   text=True, check=True).stdout.strip()
        (self.root / "products/linux-desktop/a.py").write_text("x = 2\n")   # uncommitted change
        (self.root / "products/linux-desktop/new.py").write_text("y = 1\n")  # untracked file
        self.results = self.root.parent / f"{self.root.name}-results"
        self.results.write_text("PASS|pytest (products/linux-desktop)|linux.yml:test|linux.yml:test|\n")

    def tearDown(self):
        self.results.unlink()
        self.tmp.cleanup()

    def main(self, *argv):
        out = io.StringIO()
        with redirect_stdout(out):
            code = vr.main(["--root", str(self.root), *argv])
        return code, out.getvalue()

    def test_record_matches_the_commit_made_afterwards(self):
        env = {k: v for k, v in os.environ.items() if k not in ("GITHUB_ACTIONS", "GITHUB_STEP_SUMMARY")}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertEqual(self.main("write", "--results", str(self.results), "--args=--linux")[0], 0)
            status = subprocess.run(["git", "status", "--porcelain"], cwd=self.root, capture_output=True, text=True)
            self.assertIn("?? products/linux-desktop/new.py", status.stdout)  # real index untouched
            code, block = self.main("render")
            self.assertEqual(code, 0)
            self.assertIn("uncommitted changes", block)
            self.git("add", "-A")
            self.git("commit", "-q", "-m", "change")
            body_file = self.root.parent / f"{self.root.name}-body.md"
            body_file.write_text(vr.upsert_block("", "x", block.split("\n", 1)[1].rsplit("\n", 1)[0]))
            try:
                code, out = self.main("check", "--body-file", str(body_file), "--base", self.base)
            finally:
                body_file.unlink()
        self.assertEqual(code, 0)
        self.assertNotIn("Stale record", out)
        self.assertNotIn("not verified", out)


class AndroidDevicesTest(unittest.TestCase):
    LISTING = "List of devices attached\nTESTDEV\tdevice\nPERSONAL\tdevice\nGONE\toffline\n"

    def fake_run(self, cmd, timeout=None):
        if cmd == ["adb", "devices"]:
            return self.LISTING
        return {"ro.product.model": f"model-{cmd[2]}", "ro.build.version.release": "17",
                "ro.build.version.sdk": "37"}.get(cmd[-1])

    def devices(self, env):
        with mock.patch.object(vr, "run", self.fake_run), \
                mock.patch.object(vr.shutil, "which", return_value="/usr/bin/adb"), \
                mock.patch.dict(os.environ, {}, clear=False):
            # run_tests.sh exports both while it runs this suite; keep the real ones out.
            for key in ("ANDROID_SERIAL", "PST_TESTED_SERIALS"):
                os.environ.pop(key, None)
            os.environ.update(env)
            return vr.android_devices()

    def test_only_the_device_under_test_is_reported(self):
        self.assertEqual([d["model"] for d in self.devices({"ANDROID_SERIAL": "TESTDEV"})], ["model-TESTDEV"])

    def test_every_device_the_suite_ran_on_is_reported(self):
        env = {"ANDROID_SERIAL": "TESTDEV", "PST_TESTED_SERIALS": "TESTDEV,PERSONAL"}
        self.assertEqual([d["model"] for d in self.devices(env)], ["model-TESTDEV", "model-PERSONAL"])

    def test_every_attached_device_without_android_serial(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("ANDROID_SERIAL", None)
            os.environ.pop("PST_TESTED_SERIALS", None)
            models = [d["model"] for d in self.devices({})]
        self.assertEqual(models, ["model-TESTDEV", "model-PERSONAL"])


if __name__ == "__main__":
    unittest.main()
