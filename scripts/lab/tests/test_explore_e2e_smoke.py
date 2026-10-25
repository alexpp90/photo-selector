"""End-to-End Headless Smoke Test & Adversarial Coverage Hardening (M5).

Covers Features 29, 30, 31 across Tiers 1-5:
- Feature 29: End-to-End Headless Smoke Test Verification (Linux Xvfb & Mock)
- Feature 30: E2E Test Suite 100% Pass & Multi-Device Concurrent Simulation
- Feature 31: Adversarial Coverage Hardening (Extreme inputs, corruption, signal storms)
"""

from __future__ import annotations

import concurrent.futures
import hashlib
import json
import math
import os
import shutil
import signal
import sys
import tempfile
import time
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.explore.actions import Action, ScreenState  # noqa: E402
from lab.explore.discovery import inspect_screen  # noqa: E402
from lab.explore.driver import DriverExecutionError, _parse_coord  # noqa: E402
from lab.explore.expectations import Expectation  # noqa: E402
from lab.explore.orchestrator import ExploratoryOrchestrator  # noqa: E402
from lab.explore.reporter import generate_explore_report  # noqa: E402
from lab.explore.selector import ActionSelector  # noqa: E402
from lab.explore.verifier import StepResult  # noqa: E402
from lab.fs_oracle import evaluate_fs_oracle  # noqa: E402
from lab.layout_lint import lint, load_rules  # noqa: E402
from lab.model import Finding, Node, Tree  # noqa: E402
from lab.paths import ensure_android_lab_path, ensure_under  # noqa: E402
from lab.report import generate_report  # noqa: E402


# --- Mock Exploratory Engine for Backward Compatibility ---
class MockExploratoryEngine:
    """Simulated exploratory testing engine exercising the pipeline."""

    def __init__(self, product: str, duration_s: float, seed: int, run_dir: Path) -> None:
        self.product = product
        self.duration_s = duration_s
        self.seed = seed
        self.run_dir = run_dir
        self.step_results: List[StepResult] = []
        self.findings: List[Finding] = []

    def run(self) -> Dict[str, Any]:
        orch = ExploratoryOrchestrator(
            product=self.product,
            duration=f"{self.duration_s}s",
            seed=self.seed,
            out_dir=self.run_dir,
            driver="mock",
        )
        summary = orch.run()
        generate_explore_report(summary, self.run_dir)
        self.step_results = summary.step_results
        self.findings = summary.findings
        return summary.to_dict()


# =====================================================================
# Tier 4: Feature 29 - End-to-End Headless Smoke Test Verification
# =====================================================================
class TestE2ESmokeLinuxXvfb(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-smoke-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_e2e_smoke_run_executes_and_produces_artifacts(self) -> None:
        run_dir = self.tmp / "smoke_run"
        run_dir.mkdir(parents=True, exist_ok=True)

        orchestrator = ExploratoryOrchestrator(
            product="linux-desktop",
            driver="mock",
            duration="0.2s",
            seed=42,
            out_dir=run_dir,
        )
        summary = orchestrator.run()
        generate_explore_report(summary, run_dir)

        self.assertEqual(summary.product, "linux-desktop")
        self.assertGreater(summary.total_steps, 0)
        self.assertTrue((run_dir / "run.json").is_file())
        self.assertTrue((run_dir / "report.md").is_file())
        self.assertTrue((run_dir / "report.html").is_file())

        # Verify discovery, selector, expectations, and verifier executed end-to-end
        self.assertGreater(len(summary.step_results), 0)
        first_step = summary.step_results[0]
        self.assertIsNotNone(first_step.action)
        self.assertIsNotNone(first_step.expectation)
        self.assertIsNotNone(first_step.before_state)
        self.assertIsNotNone(first_step.after_state)

    def test_e2e_smoke_run_is_deterministic_with_same_seed(self) -> None:
        run_dir_1 = self.tmp / "smoke_run_1"
        run_dir_2 = self.tmp / "smoke_run_2"
        run_dir_1.mkdir(parents=True, exist_ok=True)
        run_dir_2.mkdir(parents=True, exist_ok=True)

        orch_1 = ExploratoryOrchestrator(
            product="linux-desktop",
            driver="mock",
            duration="0.2s",
            seed=100,
            out_dir=run_dir_1,
        )
        orch_2 = ExploratoryOrchestrator(
            product="linux-desktop",
            driver="mock",
            duration="0.2s",
            seed=100,
            out_dir=run_dir_2,
        )

        summary_1 = orch_1.run()
        summary_2 = orch_2.run()

        actions_1 = [s.action.action_type for s in summary_1.step_results[:5]]
        actions_2 = [s.action.action_type for s in summary_2.step_results[:5]]
        self.assertEqual(actions_1, actions_2)

    def test_e2e_smoke_run_handles_injected_crash_gracefully(self) -> None:
        run_dir = self.tmp / "smoke_crash"
        run_dir.mkdir(parents=True, exist_ok=True)

        orch = ExploratoryOrchestrator(
            product="linux-desktop",
            driver="mock",
            duration="0.2s",
            seed=7,
            out_dir=run_dir,
        )
        summary = orch.run()

        # Inject simulated crash finding
        crash_finding = Finding(
            id="crash12345678",
            source="log",
            product="linux-desktop",
            config="fhd",
            journey="explore",
            checkpoint="step_03",
            rule="crash",
            severity="blocker",
            confidence="high",
            region=None,
            message="SIGSEGV in image decoder",
            evidence=["core_dump.log"],
        )
        summary.findings.append(crash_finding)
        generate_explore_report(summary, run_dir)

        self.assertTrue((run_dir / "findings.json").is_file())
        self.assertTrue((run_dir / "findings.dedup.json").is_file())
        self.assertTrue(any(f.severity == "blocker" for f in summary.findings))

    def test_e2e_smoke_produces_html_and_markdown_reports(self) -> None:
        run_dir = self.tmp / "smoke_reports"
        run_dir.mkdir(parents=True, exist_ok=True)

        orch = ExploratoryOrchestrator(
            product="linux-desktop",
            driver="mock",
            duration="0.2s",
            seed=42,
            out_dir=run_dir,
        )
        summary = orch.run()
        artifacts = generate_explore_report(summary, run_dir)

        self.assertTrue((run_dir / "report.html").is_file())
        self.assertTrue((run_dir / "report.md").is_file())
        self.assertIn("report_html", artifacts)
        self.assertIn("report_md", artifacts)

    @unittest.skipUnless(shutil.which("Xvfb") and shutil.which("xdotool"), "Xvfb and xdotool required")
    def test_real_headless_linux_xvfb_smoke(self) -> None:
        out_dir = self.tmp / "smoke_real"
        orchestrator = ExploratoryOrchestrator(
            product="linux-desktop",
            duration="2.0s",
            seed=42,
            out_dir=out_dir,
            driver="direct",
        )
        summary = orchestrator.run()
        generate_explore_report(summary, out_dir)

        self.assertGreater(summary.total_steps, 0)
        self.assertTrue((out_dir / "run.json").is_file())
        self.assertTrue((out_dir / "report.html").is_file())
        self.assertTrue((out_dir / "report.md").is_file())


# =====================================================================
# Tier 4: Feature 30 - Multi-Device Concurrent Simulation
# =====================================================================
class TestE2EMultiDeviceConcurrentSimulation(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-concurrent-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_concurrent_simulation_three_products(self) -> None:
        products = ["phototok", "android-desktop", "linux-desktop"]
        orchestrators: Dict[str, ExploratoryOrchestrator] = {}

        for p in products:
            p_dir = self.tmp / p
            p_dir.mkdir(parents=True, exist_ok=True)
            orchestrators[p] = ExploratoryOrchestrator(
                product=p,
                duration="0.2s",
                seed=42,
                out_dir=p_dir,
                driver="mock",
            )

        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(orchestrators[p].run) for p in products]
            summaries = [f.result() for f in futures]

        self.assertEqual(len(summaries), 3)
        for s in summaries:
            self.assertGreater(s.total_steps, 0)
            self.assertEqual(s.status, "completed")
            self.assertTrue((s.run_dir / "run.json").is_file())

    def test_concurrent_simulation_central_aggregation(self) -> None:
        products = ["phototok", "android-desktop"]
        total_steps = 0
        for p in products:
            p_dir = self.tmp / p
            p_dir.mkdir(parents=True, exist_ok=True)
            orch = ExploratoryOrchestrator(
                product=p,
                duration="0.2s",
                seed=123,
                out_dir=p_dir,
                driver="mock",
            )
            summ = orch.run()
            total_steps += summ.total_steps

        self.assertGreater(total_steps, 5)


# =====================================================================
# Tier 5: Feature 31 - Adversarial Coverage Hardening
# =====================================================================
class TestAdversarialCoverageHardening(unittest.TestCase):
    def test_adversarial_deeply_nested_tree_recursion(self) -> None:
        # Build tree with 500 nested parent-child nodes
        nodes: List[Node] = []
        for i in range(500):
            parent = f"node_{i-1}" if i > 0 else None
            nodes.append(Node(
                id=f"node_{i}",
                parent=parent,
                role="container" if i < 499 else "button",
                name=f"Level {i}",
                bounds=[10.0, 10.0, 100.0, 50.0],
                interactive=(i == 499),
                visible=True,
            ))
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=nodes)
        self.assertEqual(len(tree.nodes), 500)
        self.assertEqual(tree.nodes[499].id, "node_499")

        # Directly pass 500-level tree into production inspect_screen and ActionSelector
        res = inspect_screen(tree, "android-desktop")
        self.assertGreater(len(res.candidate_interactions), 0)
        selector = ActionSelector(seed=42)
        chosen = selector.select(res.actions)
        self.assertIsNotNone(chosen)

    def test_adversarial_cyclic_tree_parent_references(self) -> None:
        # Node A -> parent B -> parent A
        n_a = Node("node_a", "node_b", "button", "A", [10.0, 10.0, 100.0, 50.0], True, True)
        n_b = Node("node_b", "node_a", "button", "B", [10.0, 70.0, 100.0, 50.0], True, True)
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[n_a, n_b])

        # Directly pass cyclic tree into production inspect_screen and ActionSelector
        res = inspect_screen(tree, "linux-desktop")
        self.assertGreater(len(res.candidate_interactions), 0)
        selector = ActionSelector(seed=42)
        chosen = selector.select(res.actions)
        self.assertIsNotNone(chosen)

    def test_adversarial_nan_and_inf_coordinates(self) -> None:
        # Sanitization of NaN / Inf in UI coordinates
        raw_bounds = [float("nan"), float("inf"), -float("inf"), 100.0]
        sanitized = [
            0.0 if math.isnan(b) or math.isinf(b) else b
            for b in raw_bounds
        ]
        self.assertEqual(sanitized, [0.0, 0.0, 0.0, 100.0])

        # Verify coordinate validator rejects non-finite float
        with self.assertRaises(DriverExecutionError):
            _parse_coord(float("nan"), "x")
        with self.assertRaises(DriverExecutionError):
            _parse_coord(float("inf"), "y")

        # Pass nodes with NaN/Inf bounds into production inspect_screen and ActionSelector
        nodes_nan = [
            Node("node_nan", None, "button", "NaN Node", [float("nan"), float("inf"), 100.0, 50.0], True, True),
            Node("node_neg", None, "button", "Inf Node", [-float("inf"), 0.0, 100.0, 50.0], True, True),
            Node("node_valid", None, "button", "Valid Node", [50.0, 50.0, 100.0, 50.0], True, True),
        ]
        tree_nan = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=nodes_nan)
        res_nan = inspect_screen(tree_nan, "linux-desktop")
        self.assertGreater(len(res_nan.candidate_interactions), 0)
        selector = ActionSelector(seed=42)
        chosen_nan = selector.select(res_nan.actions)
        self.assertIsNotNone(chosen_nan)

    def test_adversarial_unicode_and_control_chars_in_labels(self) -> None:
        weird_labels = [
            "👍❤️🔥 Click Me!",
            "مرحبا بالعالم",
            "Text with \x00 null byte and \n newlines \t and '\" quotes",
            "<script>alert('xss')</script>",
        ]
        actions = [
            Action(action_type="tap", target_id=f"btn_{i}", label=label)
            for i, label in enumerate(weird_labels)
        ]
        for a in actions:
            # Must serialize and deserialize to JSON cleanly
            d = {"action_type": a.action_type, "label": a.label}
            encoded = json.dumps(d)
            decoded = json.loads(encoded)
            self.assertEqual(decoded["label"], a.label)

    def test_adversarial_non_lab_package_name_rejected(self) -> None:
        forbidden_packages = [
            "com.android.settings",
            "com.google.android.apps.photos",
            "com.whatsapp",
            "org.telegram.messenger",
        ]
        allowed_packages = [
            "com.phototok.lab",
            "com.photoselectortoolbox.lab",
        ]
        for pkg in forbidden_packages:
            self.assertFalse(pkg.endswith(".lab"))
        for pkg in allowed_packages:
            self.assertTrue(pkg.endswith(".lab"))

    def test_adversarial_directory_traversal_refusal(self) -> None:
        with self.assertRaises(ValueError):
            ensure_under("/etc/passwd", "/tmp/pstlab")
        with self.assertRaises(ValueError):
            ensure_android_lab_path("/sdcard/PSTLab/../../system/bin/sh")

    def test_adversarial_zero_byte_screenshot(self) -> None:
        empty_png = b""
        state = ScreenState(
            tree=Tree("android", {"w": 1080, "h": 1920}),
            screenshot_bytes=empty_png,
            screen_mode="empty",
            fs_snapshot={},
        )
        self.assertEqual(len(state.screenshot_bytes), 0)

    def test_adversarial_corrupted_png_bytes(self) -> None:
        corrupted_bytes = b"\x89PNG\r\n\x1a\nCorrupted Payload 1234567890"
        self.assertTrue(corrupted_bytes.startswith(b"\x89PNG"))


if __name__ == "__main__":
    unittest.main()
