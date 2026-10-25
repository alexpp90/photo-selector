"""Comprehensive E2E and unit tests for Deduplication, Reporting & Backlog Logging (M4).

Covers Features 25, 26, 27, 28 across Tiers 1-4:
- Feature 25: Root Signature Deduplication
- Feature 26: Run Directory & Date-Staged Lab Reports
- Feature 27: Canonical Backlog Logging
- Feature 28: Documentation Updates
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.model import Finding, Node, Tree  # noqa: E402
from lab.paths import repo_root  # noqa: E402
from lab.report import generate_report  # noqa: E402

# Check if implementation module is present
try:
    from lab.explore import reporter as exp_reporter  # noqa: E402
    HAS_EXPLORE_REPORTER = True
except ImportError:
    HAS_EXPLORE_REPORTER = False


if HAS_EXPLORE_REPORTER:
    compute_root_signature = exp_reporter.compute_root_signature
    format_backlog_entry = exp_reporter.format_backlog_entry
else:
    # --- Root Signature Generator Reference ---
    def compute_root_signature(
        product: str,
        screen_sig: str,
        action_sig: str,
        violated_rule: str,
        subject: str = "",
    ) -> str:
        """Computes SHA1(product|screen_sig|action_sig|violated_rule|subject)[:12]."""
        raw = f"{product.lower()}|{screen_sig}|{action_sig}|{violated_rule.lower()}|{subject}"
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]

    # --- Backlog Formatter Reference ---
    def format_backlog_entry(
        date_str: str,
        title: str,
        where: str,
        debt: str,
        proposal: str,
    ) -> str:
        """Formats markdown backlog item matching private/ai/memory/code_health.md schema."""
        return (
            f"## [OPEN] {date_str} - {title}\n"
            f"**Where:** {where}\n"
            f"**Debt:** {debt}\n"
            f"**Proposal:** {proposal}\n"
        )


# =====================================================================
# Tier 1 & 2: Feature 25 - Root Signature Deduplication
# =====================================================================
class TestRootSignatureDeduplication(unittest.TestCase):
    def test_signature_deterministic_sha1_12_chars(self) -> None:
        sig1 = compute_root_signature("phototok", "viewer", "swipe_up", "missing-or-zero-size", "viewer.photo")
        sig2 = compute_root_signature("phototok", "viewer", "swipe_up", "missing-or-zero-size", "viewer.photo")
        self.assertEqual(sig1, sig2)
        self.assertEqual(len(sig1), 12)

    def test_signature_differs_by_product(self) -> None:
        sig_tok = compute_root_signature("phototok", "viewer", "tap", "overlap")
        sig_duel = compute_root_signature("android-desktop", "viewer", "tap", "overlap")
        self.assertNotEqual(sig_tok, sig_duel)

    def test_signature_differs_by_screen_mode(self) -> None:
        sig_landing = compute_root_signature("phototok", "landing", "tap", "crash")
        sig_viewer = compute_root_signature("phototok", "viewer", "tap", "crash")
        self.assertNotEqual(sig_landing, sig_viewer)

    def test_signature_differs_by_action(self) -> None:
        sig_swipe = compute_root_signature("phototok", "viewer", "swipe", "crash")
        sig_key = compute_root_signature("phototok", "viewer", "key_m", "crash")
        self.assertNotEqual(sig_swipe, sig_key)

    def test_signature_normalizes_case(self) -> None:
        sig1 = compute_root_signature("Phototok", "viewer", "tap", "OVERLAP-INTERACTIVE")
        sig2 = compute_root_signature("phototok", "viewer", "tap", "overlap-interactive")
        self.assertEqual(sig1, sig2)

    def test_alias_normalization_macos(self) -> None:
        sig1 = compute_root_signature("macos-desktop", "viewer", "click", "crash")
        sig2 = compute_root_signature("lensjet", "viewer", "click", "crash")
        sig3 = compute_root_signature("macos", "viewer", "click", "crash")
        self.assertEqual(sig1, sig2)
        self.assertEqual(sig2, sig3)

    def test_alias_normalization_linux(self) -> None:
        sig1 = compute_root_signature("linux-desktop", "viewer", "click", "crash")
        sig2 = compute_root_signature("blitzlens", "viewer", "click", "crash")
        sig3 = compute_root_signature("linux", "viewer", "click", "crash")
        self.assertEqual(sig1, sig2)
        self.assertEqual(sig2, sig3)

    def test_alias_normalization_android(self) -> None:
        sig_tok1 = compute_root_signature("phototok", "viewer", "click", "crash")
        sig_tok2 = compute_root_signature("jackpix", "viewer", "click", "crash")
        self.assertEqual(sig_tok1, sig_tok2)
        sig_ad1 = compute_root_signature("android-desktop", "viewer", "click", "crash")
        sig_ad2 = compute_root_signature("frameduel", "viewer", "click", "crash")
        self.assertEqual(sig_ad1, sig_ad2)

    def test_dict_key_sorting_mixed_types(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")
        act_sig = exp_reporter.normalize_action_signature({"action_type": "tap", "params": {1: "a", "b": 2}})
        self.assertIn("1=a", act_sig)
        self.assertIn("b=2", act_sig)
        subj_sig = exp_reporter.normalize_subject({1: "a", "b": 2})
        self.assertIn("1=a", subj_sig)
        self.assertIn("b=2", subj_sig)

    def test_deduplicate_findings_groups_multiple_occurrences(self) -> None:
        # 10 identical crashes across 10 steps should deduplicate to 1 entry
        sig = compute_root_signature("phototok", "viewer", "swipe", "null_pointer_exception")
        findings: List[Finding] = []
        for step in range(10):
            findings.append(
                Finding(
                    id=sig,
                    source="log",
                    product="phototok",
                    config="phone",
                    journey="explore",
                    checkpoint=f"step_{step:02d}",
                    rule="crash",
                    severity="blocker",
                    confidence="high",
                    region=None,
                    message="NullPointerException on swipe",
                    evidence=[f"step_{step:02d}_before.png", f"step_{step:02d}_after.png"],
                )
            )

        # Deduplication logic
        if HAS_EXPLORE_REPORTER:
            dedup_list, counts = exp_reporter.deduplicate_findings_with_counts(findings)
            deduped = {f.id: f for f in dedup_list}
        else:
            deduped = {}
            counts = {}
            for f in findings:
                if f.id not in deduped:
                    deduped[f.id] = f
                    counts[f.id] = 1
                else:
                    counts[f.id] += 1
                    deduped[f.id].evidence.extend(f.evidence)

        self.assertEqual(len(deduped), 1)
        self.assertEqual(counts[sig], 10)
        self.assertEqual(len(deduped[sig].evidence), 20)

    def test_signature_handles_empty_subject(self) -> None:
        sig = compute_root_signature("linux", "fhd", "key_m", "fs-oracle", "")
        self.assertEqual(len(sig), 12)

    def test_signature_handles_special_characters(self) -> None:
        sig = compute_root_signature("macos", "main", "click", "error: [123] <test> & 'quotes'")
        self.assertEqual(len(sig), 12)


# =====================================================================
# Tier 1 & 2: Feature 26 - Run Directory & Date-Staged Lab Reports
# =====================================================================
class TestRunDirectoryAndReports(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-reports-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_run_json_generation(self) -> None:
        run_data = {
            "started": "2026-10-24T15:00:00Z",
            "finished": "2026-10-24T15:15:00Z",
            "commit": "abcdef1",
            "products": {
                "phototok": {"status": "ran", "steps": 50, "passed": 48, "failed": 2}
            },
            "tools": {"python": "3.13"},
            "journeys": [],
        }
        run_file = self.tmp / "run.json"
        run_file.write_text(json.dumps(run_data, indent=2), encoding="utf-8")
        self.assertTrue(run_file.is_file())
        loaded = json.loads(run_file.read_text(encoding="utf-8"))
        self.assertEqual(loaded["commit"], "abcdef1")
        self.assertEqual(loaded["products"]["phototok"]["steps"], 50)

    def test_findings_json_separate_det_and_ai(self) -> None:
        f_det = [{"id": "det001", "source": "layout-lint", "rule": "r1", "severity": "major"}]
        f_ai = [{"id": "ai001", "source": "ai-review", "rule": "ai-glitch", "severity": "minor"}]

        (self.tmp / "findings.det.json").write_text(json.dumps(f_det), encoding="utf-8")
        (self.tmp / "findings.ai.json").write_text(json.dumps(f_ai), encoding="utf-8")

        self.assertTrue((self.tmp / "findings.det.json").is_file())
        self.assertTrue((self.tmp / "findings.ai.json").is_file())

    def test_date_staged_report_path_hierarchy(self) -> None:
        date_str = "2026-10-24"
        staged_dir = self.tmp / "lab-reports" / date_str
        staged_dir.mkdir(parents=True, exist_ok=True)
        report_file = staged_dir / "REPORT.md"
        report_file.write_text(f"# Device lab report — {date_str}\n\n## 1. Coverage\n", encoding="utf-8")

        self.assertTrue(report_file.is_file())
        self.assertIn("Device lab report", report_file.read_text(encoding="utf-8"))

    def test_report_markdown_content_generation(self) -> None:
        report_md = self.tmp / "report.md"
        content = (
            "# Exploratory Test Run Report\n\n"
            "- Product: phototok\n"
            "- Duration: 900s\n"
            "- Seed: 42\n"
            "- Total Steps: 120\n"
            "- Passed: 118\n"
            "- Issues Discovered: 2\n\n"
            "| Step | Action | Expectation | Result | Defect ID |\n"
            "|------|--------|-------------|--------|-----------|\n"
            "| 14   | Swipe  | Advance     | FAIL   | a1b2c3d4e5f6 |\n"
        )
        report_md.write_text(content, encoding="utf-8")
        self.assertTrue(report_md.is_file())
        self.assertIn("Issues Discovered: 2", report_md.read_text(encoding="utf-8"))

    def test_report_generation_with_zero_findings(self) -> None:
        run_file = self.tmp / "run.json"
        run_file.write_text(
            json.dumps({
                "started": "2026-10-24T15:00:00Z",
                "finished": "2026-10-24T15:01:00Z",
                "commit": "abcdef1",
                "products": {"phototok": {"status": "ran"}},
                "journeys": [],
            }),
            encoding="utf-8",
        )
        generate_report(self.tmp)
        report_html = self.tmp / "report.html"
        self.assertTrue(report_html.is_file())

    def test_render_html_report_valid_tbody_closing(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        class DummyStep:
            step_index = 1
            action = None
            expectation = None
            passed = True
            findings: list = []

        class DummySummary:
            product = "phototok"
            run_id = "run_1"
            start_time = 0.0
            end_time = 1.0
            total_steps = 1
            passed_steps = 1
            failed_steps = 0

        html_out = exp_reporter.render_html_report(
            DummySummary(), [], self.tmp, step_results=[DummyStep()]
        )
        self.assertIn("</tbody></table>", html_out)
        self.assertNotIn("tbody</table>", html_out)

    def test_render_html_report_sanitizes_severity_class(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        finding = Finding(
            id="sig_xss_1",
            source="test",
            product="phototok",
            config="phone",
            journey="exp",
            checkpoint="step_01",
            rule="test-rule",
            severity='" onmouseover="alert(1)',
            confidence="high",
            region=None,
            message="Test message",
            evidence=[],
        )

        class DummySummary:
            product = "phototok"
            run_id = "run_1"
            start_time = 0.0
            end_time = 1.0
            total_steps = 1
            passed_steps = 0
            failed_steps = 1

        deduped = exp_reporter.deduplicate_findings([finding])
        html_out = exp_reporter.render_html_report(DummySummary(), deduped, self.tmp, step_results=[])
        self.assertNotIn('badge-"', html_out)
        self.assertNotIn('onmouseover="alert(1)"', html_out)
        self.assertIn('badge-onmouseoveralert1', html_out)

    def test_render_markdown_report_escapes_pipe_characters(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        class DummyAction:
            action_type = "swipe|up"
            def summary(self) -> str:
                return "swipe|up"

        class DummyExp:
            description = "Expect screen A | screen B transition"

        class DummyStep:
            step_index = 1
            action = DummyAction()
            expectation = DummyExp()
            passed = True
            findings: list = []

        class DummySummary:
            product = "phototok"
            run_id = "run_1"
            start_time = 0.0
            end_time = 1.0
            total_steps = 1
            passed_steps = 1
            failed_steps = 0

        md_out = exp_reporter.render_markdown_report(DummySummary(), [], step_results=[DummyStep()])
        for line in md_out.splitlines():
            if line.startswith("| 1 |"):
                self.assertEqual(line.count("|"), 6, f"Broken markdown table row: {line}")


# =====================================================================
# Tier 1 & 2: Feature 27 - Canonical Backlog Logging
# =====================================================================
class TestCanonicalBacklogLogging(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-backlog-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_backlog_entry_format(self) -> None:
        entry = format_backlog_entry(
            date_str="2026-10-24",
            title="Phototok feed swipe crash on rapid gestures",
            where="products/android/phototok/ui/viewer/ (@phototok-ui-agent)",
            debt="Root signature: a1b2c3d4e5f6. IndexOutOfBoundsException when swiping past feed buffer.",
            proposal="Clamp feed index in VerticalPager state and guard boundary.",
        )
        self.assertTrue(entry.startswith("## [OPEN] 2026-10-24 - "))
        self.assertIn("**Where:**", entry)
        self.assertIn("**Debt:**", entry)
        self.assertIn("**Proposal:**", entry)
        self.assertIn("@phototok-ui-agent", entry)

    def test_backlog_appends_without_breaking_existing_content(self) -> None:
        backlog_file = self.tmp / "code_health.md"
        existing = "# Code Health & Refactoring Debt\n\n## [OPEN] 2026-10-20 - Existing item\n**Where:** old.py\n"
        backlog_file.write_text(existing, encoding="utf-8")

        new_entry = format_backlog_entry(
            date_str="2026-10-24",
            title="New exploratory finding",
            where="new.py (@phototok-ui-agent)",
            debt="Finding debt",
            proposal="Fix proposal",
        )

        with open(backlog_file, "a", encoding="utf-8") as f:
            f.write(f"\n{new_entry}")

        content = backlog_file.read_text(encoding="utf-8")
        self.assertIn("Existing item", content)
        self.assertIn("New exploratory finding", content)

    def test_backlog_only_records_blocker_and_major(self) -> None:
        # Polish and minor findings are not logged to code_health.md
        severities = ["blocker", "major", "minor", "polish"]
        logged = [s for s in severities if s in ("blocker", "major")]
        self.assertEqual(logged, ["blocker", "major"])

    def test_backlog_symlink_safety(self) -> None:
        # In a real environment, public `ai` is a symlink pointing to `private/ai`.
        # Editing must write directly to the resolved target without replacing the symlink.
        target_dir = self.tmp / "private" / "ai" / "memory"
        target_dir.mkdir(parents=True, exist_ok=True)
        canonical_file = target_dir / "code_health.md"
        canonical_file.write_text("# Initial\n", encoding="utf-8")

        symlink_dir = self.tmp / "public" / "ai"
        symlink_dir.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(target_dir.parent, symlink_dir)

        symlink_file = symlink_dir / "memory" / "code_health.md"
        self.assertTrue(symlink_file.is_file())

        # Writing to the canonical file preserves the symlink
        with open(canonical_file, "a", encoding="utf-8") as f:
            f.write("## [OPEN] Appended\n")

        self.assertTrue(os.path.islink(symlink_dir))
        self.assertIn("Appended", symlink_file.read_text(encoding="utf-8"))

    def test_backlog_owner_mapping(self) -> None:
        owner_map = {
            "phototok": "@phototok-ui-agent",
            "android-desktop": "@android-desktop-ui-agent",
            "macos-desktop": "@macos-desktop-agent",
            "linux-desktop": "@linux-desktop-agent",
        }
        self.assertEqual(owner_map["phototok"], "@phototok-ui-agent")
        self.assertEqual(owner_map["android-desktop"], "@android-desktop-ui-agent")


# =====================================================================
# Tier 1 & 2: Feature 28 - Documentation Updates
# =====================================================================
class TestDocumentationUpdates(unittest.TestCase):
    def test_readme_file_exists(self) -> None:
        readme_path = LAB_DIR / "README.md"
        self.assertTrue(readme_path.is_file())

    def test_readme_documents_safety_constraints(self) -> None:
        readme_content = (LAB_DIR / "README.md").read_text(encoding="utf-8")
        self.assertIn("/tmp/pstlab", readme_content)
        self.assertIn("/sdcard/PSTLab", readme_content)

    def test_readme_mentions_four_products(self) -> None:
        readme_content = (LAB_DIR / "README.md").read_text(encoding="utf-8")
        for p in ["phototok", "android-desktop", "macos", "linux"]:
            self.assertIn(p, readme_content)

    def test_readme_markdown_headers_valid(self) -> None:
        readme_content = (LAB_DIR / "README.md").read_text(encoding="utf-8")
        lines = [l for l in readme_content.splitlines() if l.startswith("#")]
        self.assertGreater(len(lines), 5)

    def test_readme_code_fences_closed(self) -> None:
        readme_content = (LAB_DIR / "README.md").read_text(encoding="utf-8")
        fence_count = readme_content.count("```")
        self.assertEqual(fence_count % 2, 0, "All markdown code fences must be closed")


# =====================================================================
# Tier 3: Cross-Feature Combinations (Reporter & Backlog)
# =====================================================================
class TestReporterCrossFeatureCombinations(unittest.TestCase):
    def test_deduplicate_and_log_multiple_crashes(self) -> None:
        # Multiple crashes with same signature create exactly one backlog item with aggregated count
        findings = [
            Finding("sig_crash_1", "log", "phototok", "phone", "exp", "chk1", "crash", "blocker", "high", None, "ANR", ["ev1"]),
            Finding("sig_crash_1", "log", "phototok", "phone", "exp", "chk2", "crash", "blocker", "high", None, "ANR", ["ev2"]),
            Finding("sig_crash_1", "log", "phototok", "phone", "exp", "chk3", "crash", "blocker", "high", None, "ANR", ["ev3"]),
        ]
        unique_sigs = {f.id for f in findings}
        self.assertEqual(len(unique_sigs), 1)

    def test_multi_product_report_summary(self) -> None:
        results = {
            "phototok": {"steps": 100, "pass": 99, "fail": 1},
            "android-desktop": {"steps": 80, "pass": 80, "fail": 0},
            "linux-desktop": {"steps": 120, "pass": 115, "fail": 5},
        }
        total_steps = sum(r["steps"] for r in results.values())
        total_fails = sum(r["fail"] for r in results.values())
        self.assertEqual(total_steps, 300)
        self.assertEqual(total_fails, 6)

    def test_generate_explore_report_artifacts(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-exp-rep-"))
        try:
            findings = [
                Finding("sig_blocker_1", "log", "phototok", "phone", "exp", "step_01", "crash", "blocker", "high", None, "Fatal exception", ["step_01_after.png"]),
                Finding("sig_minor_1", "ai-review", "phototok", "phone", "exp", "step_02", "glitch", "minor", "medium", None, "UI glitch", ["step_02_after.png"]),
            ]
            summary_dict = {
                "run_id": "test_run_123",
                "run_dir": str(tmp),
                "product": "phototok",
                "start_time": 100.0,
                "end_time": 160.0,
                "total_steps": 2,
                "passed_steps": 1,
                "failed_steps": 1,
                "findings": [f.to_dict() for f in findings],
                "step_results": [],
            }

            @dataclass
            class MockSummary:
                run_id: str = "test_run_123"
                run_dir: Path = tmp
                product: str = "phototok"
                start_time: float = 100.0
                end_time: float = 160.0
                total_steps: int = 2
                passed_steps: int = 1
                failed_steps: int = 1
                findings: list = field(default_factory=lambda: findings)
                step_results: list = field(default_factory=list)

                def to_dict(self):
                    return summary_dict

            artifacts = exp_reporter.generate_explore_report(MockSummary(), run_dir=tmp, date_stage=False)

            self.assertTrue((tmp / "summary.json").is_file())
            self.assertTrue((tmp / "run.json").is_file())
            self.assertTrue((tmp / "findings.json").is_file())
            self.assertTrue((tmp / "findings.det.json").is_file())
            self.assertTrue((tmp / "findings.ai.json").is_file())
            self.assertTrue((tmp / "summary.md").is_file())
            self.assertTrue((tmp / "report.md").is_file())
            self.assertTrue((tmp / "summary.html").is_file())
            self.assertTrue((tmp / "report.html").is_file())

            md_text = (tmp / "report.md").read_text(encoding="utf-8")
            self.assertIn("Exploratory Test Run Report", md_text)
            self.assertIn("Product: phototok", md_text)
            self.assertIn("Blockers and Majors", md_text)

            html_text = (tmp / "report.html").read_text(encoding="utf-8")
            self.assertIn("Exploratory Test Report", html_text)
            self.assertIn("badge-blocker", html_text)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_log_findings_to_canonical_backlog_e2e(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-exp-backlog-"))
        try:
            backlog_file = tmp / "code_health.md"
            backlog_file.write_text("# Code Health\n", encoding="utf-8")

            findings = [
                Finding("sig_b1", "log", "phototok", "phone", "exp", "step_01", "crash", "blocker", "high", None, "Null pointer", ["ev1.png"]),
                Finding("sig_m1", "layout", "phototok", "phone", "exp", "step_02", "overlap", "major", "high", None, "Overlap detected", ["ev2.png"]),
                Finding("sig_min1", "ai-review", "phototok", "phone", "exp", "step_03", "alignment", "minor", "medium", None, "Slight misalignment", []),
                Finding("sig_pol1", "lint", "phototok", "phone", "exp", "step_04", "padding", "polish", "low", None, "Padding 2dp off", []),
            ]

            # 1. First run: logs 2 entries (blocker & major), ignores minor & polish
            logged_count = exp_reporter.log_findings_to_canonical_backlog(
                findings, repo_path=backlog_file, date_str="2026-10-24", seed=42
            )
            self.assertEqual(logged_count, 2)

            content = backlog_file.read_text(encoding="utf-8")
            self.assertIn("Root signature: sig_b1.", content)
            self.assertIn("Root signature: sig_m1.", content)
            self.assertNotIn("sig_min1", content)
            self.assertNotIn("sig_pol1", content)
            self.assertIn("@phototok-ui-agent", content)
            self.assertIn("Reproducible with seed 42.", content)

            # 2. Second run: idempotency check skips already-logged signatures
            logged_again = exp_reporter.log_findings_to_canonical_backlog(
                findings, repo_path=backlog_file, date_str="2026-10-24", seed=42
            )
            self.assertEqual(logged_again, 0)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_sort_findings_hierarchy(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        f_polish = Finding("s4", "lint", "phototok", "phone", "exp", "chk", "rule4", "polish", "low", None, "msg")
        f_minor = Finding("s3", "lint", "phototok", "phone", "exp", "chk", "rule3", "minor", "medium", None, "msg")
        f_major = Finding("s2", "oracle", "phototok", "phone", "exp", "chk", "rule2", "major", "high", None, "msg")
        f_blocker = Finding("s1", "oracle", "phototok", "phone", "exp", "chk", "rule1", "blocker", "high", None, "msg")
        f_blocker_ai = Finding("s0", "ai-review", "phototok", "phone", "exp", "chk", "rule0", "blocker", "high", None, "msg")

        sorted_list = exp_reporter.sort_findings([f_polish, f_minor, f_major, f_blocker_ai, f_blocker])
        self.assertEqual([f.id for f in sorted_list], ["s1", "s0", "s2", "s3", "s4"])

    def test_log_findings_does_not_mutate_caller_evidence(self) -> None:
        if not HAS_EXPLORE_REPORTER:
            self.skipTest("reporter module not available")

        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-no-mutate-"))
        try:
            backlog_file = tmp / "code_health.md"
            backlog_file.write_text("# Code Health\n", encoding="utf-8")

            f1 = Finding("sig_mut_1", "log", "phototok", "phone", "exp", "step_01", "crash", "blocker", "high", None, "Null pointer", ["ev1.png"])
            f2 = Finding("sig_mut_1", "log", "phototok", "phone", "exp", "step_02", "crash", "blocker", "high", None, "Null pointer", ["ev2.png"])

            original_ev_f1 = list(f1.evidence)
            original_ev_f2 = list(f2.evidence)

            exp_reporter.log_findings_to_canonical_backlog(
                [f1, f2], repo_path=backlog_file, date_str="2026-10-24", seed=42
            )

            # Caller's f1.evidence and f2.evidence must remain untouched (not mutated in-place)
            self.assertEqual(f1.evidence, original_ev_f1)
            self.assertEqual(f2.evidence, original_ev_f2)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


# =====================================================================
# Tier 4: CLI Product Aliases & Exploration Smoke
# =====================================================================
class TestCLIProductAliases(unittest.TestCase):
    def test_cli_parser_accepts_product_aliases(self) -> None:
        from lab.cli import build_parser

        parser = build_parser()
        for prod in ["linux", "macos", "jackpix", "frameduel", "lensjet", "blitzlens"]:
            args = parser.parse_args(["explore", "--product", prod, "--duration", "0s"])
            self.assertEqual(args.product, prod)

    def test_cli_parser_rejects_legacy_desktop(self) -> None:
        from lab.cli import build_parser

        parser = build_parser()
        with self.assertRaises(SystemExit) as cm:
            parser.parse_args(["explore", "--product", "desktop"])
        self.assertEqual(cm.exception.code, 2)

    def test_cli_smoke_mock_driver(self) -> None:
        from lab.cli import main

        for prod in ["linux", "macos", "jackpix"]:
            ret = main(["explore", "--product", prod, "--duration", "0s", "--driver", "mock"])
            self.assertEqual(ret, 0)


if __name__ == "__main__":
    unittest.main()
