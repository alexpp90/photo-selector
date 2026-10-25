"""Root signatures, deduplication and the reports; nothing is written outside the run directory."""

from __future__ import annotations

import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _explore_fakes import default_state, png_bytes  # noqa: E402

from lab.explore import reporter  # noqa: E402
from lab.explore.actions import Action  # noqa: E402
from lab.explore.expectations import Expectation  # noqa: E402
from lab.explore.verifier import StepResult  # noqa: E402
from lab.model import Finding  # noqa: E402

sig = reporter.compute_root_signature


def finding(rule="overlap-interactive", severity="major", config="phone@pixel", checkpoint="step_03", fid=None, evidence=None, message="x overlaps y", region=None):
    return Finding(fid or sig("phototok", "viewer", "tap:btn", rule, "x,y"), "layout-lint", "phototok", config, "explore",
                   checkpoint, rule, severity, "high", region, message, list(evidence or []), True)


def step(index, config="phone@pixel", status="pass", findings=()):
    action = Action("tap", target_id=f"btn_{index}", label=f"Tap {index}")
    return StepResult(index, action, Expectation(action, f"exp {index}"), status, list(findings), default_state(), default_state(), config_label=config)


def make_summary(run_dir: Path, findings=(), steps=(), workers=(), skipped=(), status="completed"):
    return SimpleNamespace(
        run_id=run_dir.name, run_dir=run_dir, start_time=0.0, end_time=12.0, product="phototok", seed=42,
        status=status, findings=list(findings), step_results=list(steps), worker_results=list(workers),
        skipped_devices=list(skipped), total_steps=len(steps), passed_steps=sum(s.passed for s in steps),
        failed_steps=sum(not s.passed for s in steps),
    )


class SignatureTest(unittest.TestCase):
    def test_twelve_hex_and_deterministic(self) -> None:
        a = sig("phototok", "viewer", "tap:btn", "crash", "x")
        self.assertRegex(a, r"^[0-9a-f]{12}$")
        self.assertEqual(a, sig("phototok", "viewer", "tap:btn", "crash", "x"))

    def test_each_part_matters_and_case_does_not(self) -> None:
        base = sig("phototok", "viewer", "tap:btn", "crash", "x")
        self.assertNotEqual(base, sig("linux", "viewer", "tap:btn", "crash", "x"))
        self.assertNotEqual(base, sig("phototok", "landing", "tap:btn", "crash", "x"))
        self.assertNotEqual(base, sig("phototok", "viewer", "tap:other", "crash", "x"))
        self.assertNotEqual(base, sig("phototok", "viewer", "tap:btn", "anr", "x"))
        self.assertNotEqual(base, sig("phototok", "viewer", "tap:btn", "crash", "y"))
        self.assertEqual(base, sig("PhotoTok", "VIEWER", "TAP:BTN", "Crash", "X"))

    def test_product_aliases(self) -> None:
        self.assertEqual(sig("jackpix", "s", "a", "r"), sig("phototok", "s", "a", "r"))
        self.assertEqual(sig("lensjet", "s", "a", "r"), sig("macos-desktop", "s", "a", "r"))
        self.assertEqual(sig("blitzlens", "s", "a", "r"), sig("linux", "s", "a", "r"))

    def test_subject_order_and_action_objects(self) -> None:
        self.assertEqual(sig("p", "s", "a", "r", ["b", "a"]), sig("p", "s", "a", "r", ["a", "b"]))
        self.assertEqual(sig("p", "s", Action("tap", target_id="BTN"), "r"), sig("p", "s", "tap:btn", "r"))


class DedupTest(unittest.TestCase):
    def test_one_signature_across_steps_and_devices_is_one_finding(self) -> None:
        fid = sig("phototok", "viewer", "tap:btn", "crash", "")
        findings = [
            finding("crash", "blocker", "phone@pixel", "step_03", fid),
            finding("crash", "major", "phone@moto", "step_11", fid),
            finding("crash", "blocker", "phone@pixel", "step_20", fid),
        ]
        steps = [step(3, "phone@pixel"), step(11, "phone@moto"), step(20, "phone@pixel")]
        (agg,) = reporter.deduplicate_findings(findings, steps)
        self.assertEqual(agg.occurrences, 3)
        self.assertEqual(agg.devices, ["pixel", "moto"])
        self.assertEqual(agg.configs, ["phone"])
        self.assertEqual(agg.step_indices, [3, 11, 20])
        self.assertEqual((agg.first_step, agg.last_step), (3, 20))
        self.assertEqual(agg.severity, "blocker")

    def test_step_numbers_repeat_per_device_and_traces_stay_apart(self) -> None:
        fid = "a" * 12
        findings = [finding(fid=fid, config="phone@pixel", checkpoint="step_02"), finding(fid=fid, config="phone@moto", checkpoint="step_02")]
        steps = [step(2, "phone@pixel"), step(2, "phone@moto")]
        steps[0].action = Action("tap", target_id="on_pixel")
        steps[1].action = Action("tap", target_id="on_moto")
        (agg,) = reporter.deduplicate_findings(findings, steps)
        self.assertIn("on_pixel", agg.first_trace["action"])
        self.assertEqual(agg.first_trace["config"], "phone@pixel")

    def test_sorting_puts_blockers_first_then_frequent(self) -> None:
        out = reporter.deduplicate_findings([
            finding("a", "minor", fid="1" * 12), finding("b", "blocker", fid="2" * 12),
            finding("c", "major", fid="3" * 12), finding("c", "major", fid="3" * 12), finding("d", "major", fid="4" * 12),
        ])
        self.assertEqual([f.rule for f in out], ["b", "c", "d", "a"])

    def test_findings_without_a_step_still_aggregate(self) -> None:
        (agg,) = reporter.deduplicate_findings([finding(checkpoint="step_04")], [])
        self.assertEqual(agg.step_indices, [4])
        self.assertEqual(agg.first_trace["action"], "")


class ReportTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.run_dir = self.root / "run"

    def worker(self, label="phone@pixel", status="completed", error=None, steps=0):
        target = SimpleNamespace(label=f"phototok_{label}")
        return SimpleNamespace(target=target, status=status, error=error, total_steps=steps)

    def test_only_the_run_directory_is_written(self) -> None:
        before = {p for p in self.root.rglob("*")}
        art = reporter.generate_explore_report(make_summary(self.run_dir, [finding()], [step(3)]))
        written = {p for p in self.root.rglob("*")} - before
        self.assertTrue(all(self.run_dir in p.parents or p == self.run_dir for p in written))
        self.assertEqual({p.name for p in self.run_dir.iterdir()}, {"findings.json", "report.md", "report.html"})
        self.assertEqual(set(art), {"run_dir", "findings_json", "report_md", "report_html"})

    def test_nothing_is_filed_in_a_backlog_or_staged_anywhere(self) -> None:
        for gone in ("log_findings_to_canonical_backlog", "resolve_canonical_backlog_path", "format_backlog_entry"):
            self.assertFalse(hasattr(reporter, gone), gone)
        self.assertNotIn("date_stage", reporter.generate_explore_report.__code__.co_varnames)

    def test_known_findings_are_listed_but_not_open(self) -> None:
        known, new = finding(fid="a" * 12), finding("crash", "blocker", fid="b" * 12)
        reporter.generate_explore_report(make_summary(self.run_dir, [known, new]), known_ids={"a" * 12})
        data = {f["id"]: f for f in json.loads((self.run_dir / "findings.json").read_text(encoding="utf-8"))}
        self.assertTrue(data["a" * 12]["known"])
        self.assertFalse(data["b" * 12]["known"])
        md = (self.run_dir / "report.md").read_text(encoding="utf-8")
        self.assertIn("1 open, 1 known", md)
        self.assertIn("## Known (triaged)", md)

    def test_markdown_shows_workers_errors_and_escapes_pipes(self) -> None:
        workers = [self.worker(status="error", error="cannot capture | at step 1"), self.worker("tab@moto", steps=4)]
        md_path = reporter.generate_explore_report(make_summary(self.run_dir, [], [step(1)], workers, ["pixel-9: not attached"]))["report_md"]
        md = md_path.read_text(encoding="utf-8")
        self.assertIn("| phototok_phone@pixel | error | 0 | cannot capture / at step 1 |", md)
        self.assertIn("pixel-9: not attached", md)
        self.assertIn("triages them", md)

    def test_html_links_and_thumbnails_resolve_from_the_run_directory(self) -> None:
        ev = "phototok/pixel/phone/evidence/step_03_after.png"
        (self.run_dir / ev).parent.mkdir(parents=True)
        (self.run_dir / ev).write_bytes(png_bytes((10, 200, 10), (120, 200)))
        reporter.generate_explore_report(make_summary(self.run_dir, [finding(evidence=[ev, "phototok/pixel/phone/evidence/step_03_after_tree.json"], region=[10.0, 10.0, 50.0, 50.0])], [step(3)]))
        html_text = (self.run_dir / "report.html").read_text(encoding="utf-8")
        self.assertIn(f'href="{ev}"', html_text)
        self.assertIn("data:image/png;base64,", html_text)
        self.assertIn('href="phototok/pixel/phone/evidence/step_03_after_tree.json"', html_text)

    def test_html_escapes_everything_dynamic(self) -> None:
        bad = finding(severity="major", message="<script>alert(1)</script>", rule="r<b>")
        bad.severity = 'major" onload="x'
        reporter.generate_explore_report(make_summary(self.run_dir, [bad]))
        html_text = (self.run_dir / "report.html").read_text(encoding="utf-8")
        self.assertNotIn("<script>alert", html_text)
        self.assertNotIn('onload="x', html_text)
        self.assertIn("&lt;script&gt;", html_text)

    def test_zero_findings_report(self) -> None:
        reporter.generate_explore_report(make_summary(self.run_dir))
        self.assertIn("_None._", (self.run_dir / "report.md").read_text(encoding="utf-8"))
        self.assertIn("<em>None.</em>", (self.run_dir / "report.html").read_text(encoding="utf-8"))

    def test_step_table_shows_status_and_findings(self) -> None:
        f = finding(fid="c" * 12)
        reporter.generate_explore_report(make_summary(self.run_dir, [f], [step(1), step(2, status="fail", findings=[f]), step(3, status="error")]))
        md = (self.run_dir / "report.md").read_text(encoding="utf-8")
        self.assertIn(f"| 2 | phone@pixel | {step(2).action.summary()} | exp 2 | FAIL | " + "c" * 12 + " |", md)
        self.assertIn("ERROR", md)


if __name__ == "__main__":
    unittest.main()
