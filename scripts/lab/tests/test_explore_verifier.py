"""The verifier: oracles, statuses, stable ids, evidence paths."""

from __future__ import annotations

import dataclasses

import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _explore_fakes import default_state, make_state, node  # noqa: E402

from lab.explore.actions import Action  # noqa: E402
from lab.explore.ai_adapters import AIJudgeError, AIProvider, Judgement  # noqa: E402
from lab.explore.discovery import SYSTEM_PICKER_MODE  # noqa: E402
from lab.explore.expectations import Expectation  # noqa: E402
from lab.explore.verifier import verify_step  # noqa: E402
from lab.model import Finding  # noqa: E402

TAP = Action("tap", target_id="btn_ok", label="OK")


def expectation(**kw) -> Expectation:
    return Expectation(TAP, "OK does nothing", **kw)


class FakeProvider(AIProvider):
    def __init__(self, findings=None, error=None) -> None:
        self.name = "fake"
        self.findings, self.error, self.contexts = findings or [], error, []

    def judge(self, before_state, action, expectation, after_state, ctx):
        self.contexts.append(ctx)
        if self.error:
            raise self.error
        return Judgement(list(self.findings), ctx.packet_dir)

    def is_available(self) -> bool:
        return True


class VerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.run_dir = Path(tmp.name)

    def verify(self, step=1, before=None, after=None, exp=None, app_log=(), action=TAP, **kw):
        return verify_step(
            step_index=step, action=action, expectation=exp or expectation(),
            before_state=before or default_state(), after_state=after or default_state(),
            app_log=list(app_log) if app_log is not None else None,
            product="phototok", config="default@pixel", run_dir=self.run_dir,
            evidence_dir=self.run_dir / "evidence", packet_dir=self.run_dir / "packets" / f"step_{step:02d}", **kw,
        )

    def test_a_clean_step_passes_and_keeps_no_files(self) -> None:
        res = self.verify()
        self.assertEqual((res.status, res.findings), ("pass", []))
        self.assertTrue(res.passed)
        self.assertEqual(list(self.run_dir.rglob("*.png")), [])

    def test_crash_in_the_app_log_fails_with_a_step_independent_id(self) -> None:
        lines = ["I ok", "E AndroidRuntime: FATAL EXCEPTION: main"]
        first, later = self.verify(step=1, app_log=lines), self.verify(step=9, app_log=lines)
        self.assertEqual(first.status, "fail")
        (crash,) = first.findings
        self.assertEqual((crash.rule, crash.severity, crash.source, crash.config), ("crash", "blocker", "log", "default@pixel"))
        self.assertEqual(crash.id, later.findings[0].id)
        self.assertEqual(crash.checkpoint, "step_01")
        self.assertEqual(later.findings[0].checkpoint, "step_09")

    def test_evidence_paths_resolve_from_the_run_directory(self) -> None:
        res = self.verify(app_log=["FATAL EXCEPTION"])
        evidence = res.findings[0].evidence
        self.assertIn("evidence/step_01_after.png", evidence)
        for rel in evidence:
            self.assertTrue((self.run_dir / rel).is_file(), rel)

    def test_a_caught_npe_in_the_log_is_not_a_crash(self) -> None:
        self.assertEqual(self.verify(app_log=["W Foo: java.lang.NullPointerException handled"]).status, "pass")

    def test_the_screen_state_logs_are_not_an_oracle(self) -> None:
        # Only `driver.read_app_log()` (the app's own process) feeds the log oracle.
        after = make_state(logs=["FATAL EXCEPTION in some other app"])
        self.assertEqual(self.verify(after=after).status, "pass")

    def test_an_unreadable_app_log_is_an_error_not_a_pass(self) -> None:
        res = self.verify(app_log=None)
        self.assertEqual(res.status, "error")
        self.assertEqual([f.rule for f in res.findings], ["log-unavailable"])

    def test_crash_dialog_node_is_a_blocker(self) -> None:
        after = make_state([node("crash_dialog", [0, 0, 600, 600], name="App crashed", parent=None)])
        res = self.verify(after=after)
        self.assertEqual(res.status, "fail")
        self.assertIn(("crash", "blocker"), [(f.rule, f.severity) for f in res.findings])

    def test_filesystem_oracle_findings_have_stable_ids(self) -> None:
        exp = expectation(fs_expect={"absent": ["a.jpg"]})
        first, later = self.verify(step=2, exp=exp), self.verify(step=8, exp=exp)
        self.assertEqual(first.status, "fail")
        fs = [f for f in first.findings if f.source == "fs-oracle"]
        self.assertEqual(len(fs), 1)
        self.assertEqual(fs[0].id, [f for f in later.findings if f.source == "fs-oracle"][0].id)
        self.assertEqual(fs[0].checkpoint, "step_02")

    def test_a_missing_snapshot_is_an_error_when_files_are_expected(self) -> None:
        before = default_state()
        before.fs_snapshot = {}
        res = self.verify(before=before, exp=expectation(fs_expect={"unchanged": True}))
        self.assertEqual(res.status, "error")
        self.assertEqual([f.rule for f in res.findings], ["fs-unavailable"])

    def test_index_delta_from_the_counter(self) -> None:
        def at(text):
            return make_state([node("position_counter", [0, 0, 100, 60], text=text, interactive=False)])

        wrong = self.verify(before=at("3 / 9"), after=at("3 / 9"), exp=expectation(expected_index_delta=1))
        self.assertEqual([f.rule for f in wrong.findings], ["unexpected-index-delta"])
        right = self.verify(before=at("3 / 9"), after=at("4 / 9"), exp=expectation(expected_index_delta=1))
        self.assertEqual(right.status, "pass")

    def test_a_claim_without_a_position_after_is_not_checked_never_passed_silently(self) -> None:
        # 2026-10-26 mentor review: the before side alone was guarded; a counter gone after the
        # action (a tap hid the overlays) still let the step read as a checked pass.
        def at(text=None):
            nodes = [node("position_counter", [0, 0, 100, 60], text=text, interactive=False)] if text else [node("x", [0, 0, 10, 10], interactive=False)]
            return make_state(nodes)

        open_claim = self.verify(before=at("3 / 9"), after=at(), exp=expectation(expected_index_delta=1))
        self.assertEqual(open_claim.status, "pass")
        self.assertEqual(open_claim.unchecked, ["index"])
        self.assertEqual(open_claim.to_dict()["unchecked"], ["index"])
        checked = self.verify(before=at("3 / 9"), after=at("4 / 9"), exp=expectation(expected_index_delta=1))
        self.assertEqual(checked.unchecked, [])
        no_claim = self.verify(before=at(), after=at())
        self.assertEqual(no_claim.unchecked, [])

    def test_index_delta_from_the_apps_own_position(self) -> None:
        # Blitzlens: no counter node in the tree, the driver's position from state.json instead.
        def at(index: int, total: int):
            return dataclasses.replace(make_state([node("slot_current", [0, 0, 100, 60], interactive=False)]), position=(index, total))

        stuck = self.verify(before=at(2, 9), after=at(2, 9), exp=expectation(expected_index_delta=1))
        self.assertEqual([f.rule for f in stuck.findings], ["unexpected-index-delta"])
        self.assertEqual(self.verify(before=at(2, 9), after=at(3, 9), exp=expectation(expected_index_delta=1)).status, "pass")

    def test_layout_findings_name_the_nodes_involved(self) -> None:
        def screen(*pairs):
            nodes = [node("root", [0, 0, 1080, 1920], interactive=False)]
            for nid, bounds in pairs:
                nodes.append(node(nid, bounds, parent="root", text=nid))
            return make_state(nodes)

        a = screen(("btn_a", [100, 800, 300, 300]), ("btn_b", [150, 850, 300, 300]))
        b = screen(("btn_c", [100, 800, 300, 300]), ("btn_d", [150, 850, 300, 300]))
        found_a = [f for f in self.verify(after=a).findings if f.rule == "overlap-interactive"]
        found_b = [f for f in self.verify(after=b).findings if f.rule == "overlap-interactive"]
        self.assertEqual((len(found_a), len(found_b)), (1, 1))
        self.assertNotEqual(found_a[0].id, found_b[0].id)
        # The screen has the defect whichever action led to it.
        other = self.verify(after=a, action=Action("key", params={"key": "Right"}))
        self.assertEqual([f.id for f in other.findings if f.rule == "overlap-interactive"], [found_a[0].id])

    def test_ai_findings_get_root_signatures_and_decide_the_status(self) -> None:
        ai = Finding("zzzzzzzzzzzz", "ai-explorer", "phototok", "default@pixel", "explore", "step_01", "ai-visual", "major", "medium", [10.0, 10.0, 20.0, 20.0], "clipped title")
        provider = FakeProvider([ai])
        res = self.verify(ai_provider=provider, remaining_s=500, ai_timeout_s=60)
        self.assertEqual(res.status, "fail")
        self.assertNotEqual(res.findings[0].id, "zzzzzzzzzzzz")
        self.assertEqual(res.findings[0].id, self.verify(step=5, ai_provider=provider).findings[0].id)

    def test_ai_timeout_never_exceeds_the_remaining_time(self) -> None:
        provider = FakeProvider()
        self.verify(ai_provider=provider, remaining_s=7.5, ai_timeout_s=120)
        self.verify(ai_provider=provider, remaining_s=900, ai_timeout_s=120)
        self.assertEqual([c.timeout_s for c in provider.contexts], [7.5, 120])

    def test_a_failed_ai_judge_is_an_error_with_a_finding_never_a_pass(self) -> None:
        res = self.verify(ai_provider=FakeProvider(error=AIJudgeError("claude exited with 1")))
        self.assertEqual(res.status, "error")
        (err,) = res.findings
        self.assertEqual((err.rule, err.severity, err.source), ("ai-judge-error", "minor", "ai-explorer"))
        self.assertIn("claude exited with 1", err.message)
        self.assertFalse(res.passed)

    def test_a_blocking_finding_outranks_an_unchecked_oracle(self) -> None:
        res = self.verify(app_log=None, after=make_state([node("crash_dialog", [0, 0, 600, 600])]))
        self.assertEqual(res.status, "fail")

    def test_step_result_serializes_without_screen_data(self) -> None:
        data = self.verify(app_log=["SIGSEGV"]).to_dict()
        self.assertEqual((data["step_index"], data["status"], data["config"]), (1, "fail", "default@pixel"))
        self.assertNotIn("screenshot", str(data))


class FeedCountSourceTest(VerifierTest):
    def test_a_counter_hidden_on_one_side_is_not_compared_with_files(self) -> None:
        before = make_state([node("root", [0, 0, 1080, 1920], interactive=False),
                             node("position_counter", [0, 0, 200, 60], text="1 / 12", interactive=False, parent="root")],
                            files=["a.jpg", "b.jpg"])
        after = make_state(default_state().tree.nodes, files=["a.jpg", "b.jpg"])  # a dialog hides the counter
        res = self.verify(before=before, after=after, exp=expectation(expected_feed_count_delta=0))
        self.assertNotIn("unexpected-feed-count-delta", [f.rule for f in res.findings])

class LintConfigTest(VerifierTest):
    def test_the_lint_gets_the_config_without_the_device(self) -> None:
        with patch("lab.explore.verifier.lint", return_value=[]) as lint:
            verify_step(
                step_index=1, action=TAP, expectation=expectation(), before_state=default_state(), after_state=default_state(),
                app_log=[], product="android-desktop", config="tab-landscape@pixel-tablet", run_dir=self.run_dir,
                evidence_dir=self.run_dir / "evidence", packet_dir=None,
            )
        self.assertEqual(lint.call_args.kwargs["config"], "tab-landscape")

class PickerLintTest(VerifierTest):
    def test_the_system_picker_is_not_linted(self) -> None:
        overlapping = [node("root", [0, 0, 1080, 1920], interactive=False), node("a", [0, 0, 400, 400]), node("b", [100, 100, 400, 400])]
        app = make_state(overlapping)
        self.assertTrue(self.verify(after=app).findings)
        picker = make_state(overlapping, mode=SYSTEM_PICKER_MODE)
        self.assertEqual(self.verify(after=picker).findings, [])

class CountsOracleTest(VerifierTest):
    """A cull that names no photo is checked by counts of feed photos, Selection/ and Trash."""

    MOVE = {"counts": {"feed": -1, "selection": "+", "trash": 0}}

    def state(self, files, trash=()):
        s = make_state(default_state().tree.nodes, files=list(files))
        s.fs_snapshot["trash"] = [{"path": t, "size": 10} for t in trash]
        return s

    def test_a_move_that_moved_one_bundle_passes(self) -> None:
        before = self.state(["a.jpg", "a.cr3", "b.jpg"])
        after = self.state(["b.jpg", "Selection/a.jpg", "Selection/a.cr3"])
        res = self.verify(before=before, after=after, exp=expectation(fs_expect=self.MOVE))
        self.assertEqual((res.status, res.findings), ("pass", []))

    def test_a_move_that_changed_nothing_is_a_finding(self) -> None:
        before = self.state(["a.jpg", "b.jpg"])
        res = self.verify(before=before, after=self.state(["a.jpg", "b.jpg"]), exp=expectation(fs_expect=self.MOVE))
        self.assertEqual(res.status, "fail")
        [f] = res.findings
        self.assertEqual((f.rule, f.source, f.severity), ("unexpected-folder-change", "fs-oracle", "major"))
        self.assertIn("feed 0, expected -1", f.message)
        self.assertIn("selection 0, expected growth", f.message)

    def test_a_delete_must_reach_the_trash(self) -> None:
        delete = expectation(fs_expect={"counts": {"feed": -1, "selection": 0, "trash": "+"}})
        before = self.state(["a.jpg", "b.jpg"])
        gone = self.verify(before=before, after=self.state(["b.jpg"]), exp=delete)
        self.assertIn("trash 0, expected growth", gone.findings[0].message)
        trashed = self.verify(before=before, after=self.state(["b.jpg"], trash=["a.jpg"]), exp=delete)
        self.assertEqual(trashed.findings, [])

if __name__ == "__main__":
    unittest.main()
