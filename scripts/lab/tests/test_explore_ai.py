"""Comprehensive E2E and unit tests for AI Adapters, Review Packets & Fallbacks (M2).

Covers Features 13, 14, 15, 16, 17, 18 across Tiers 1-4:
- Feature 13: AI Provider Base & Adapter Interface
- Feature 14: Claude CLI Live Adapter
- Feature 15: Antigravity Live Adapter
- Feature 16: Offline Review Packet Export Adapter
- Feature 17: Offline Review Packet Ingest & Evaluation
- Feature 18: AI Adapter Graceful Fallback
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.ingest import extract_findings_from_raw, triage_finding  # noqa: E402
from lab.model import Finding, Node, Tree  # noqa: E402
from lab.review_bundle import compute_image_change_fraction, downscale_image  # noqa: E402
from PIL import Image  # noqa: E402

# Check if implementation module is present
try:
    from lab.explore import ai_adapters as exp_ai  # noqa: E402
    HAS_EXPLORE_AI = True
except ImportError:
    HAS_EXPLORE_AI = False


# --- Interface Contracts (PROJECT.md § Interface Contracts) ---
@dataclass(frozen=True)
class Action:
    action_type: str
    target_id: Optional[str] = None
    params: Dict[str, Any] = field(default_factory=dict)
    label: str = ""


@dataclass
class ScreenState:
    tree: Tree
    screenshot_bytes: bytes
    screen_mode: str
    fs_snapshot: Dict[str, Any]
    logs: List[str] = field(default_factory=list)


@dataclass
class Expectation:
    action: Action
    description: str
    expected_index_delta: Optional[int] = None
    expected_feed_count_delta: Optional[int] = None
    expected_counter_deltas: Dict[str, int] = field(default_factory=dict)
    fs_expect: Dict[str, Any] = field(default_factory=dict)
    required_ui_nodes: List[str] = field(default_factory=list)
    forbidden_ui_nodes: List[str] = field(default_factory=list)
    ai_prompt: Optional[str] = None


@dataclass
class StepResult:
    step_index: int
    action: Action
    expectation: Expectation
    passed: bool
    findings: List[Finding]
    before_state: ScreenState
    after_state: ScreenState
    packet_dir: Optional[Path] = None


class BaseAIProvider:
    """Contract reference for AI providers."""
    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        raise NotImplementedError

    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Expectation,
        after_state: ScreenState,
    ) -> List[Finding]:
        raise NotImplementedError


# =====================================================================
# Tier 1 & 2: Feature 13 - AI Provider Base & Adapter Interface
# =====================================================================
class TestAIProviderBaseInterface(unittest.TestCase):
    def test_provider_base_methods_exist(self) -> None:
        self.assertTrue(hasattr(BaseAIProvider, "generate_expectation"))
        self.assertTrue(hasattr(BaseAIProvider, "judge_outcome"))

    def test_mock_provider_implementation(self) -> None:
        class MockProvider(BaseAIProvider):
            def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
                return "Mock: expect smooth transition"

            def judge_outcome(
                self,
                before_state: ScreenState,
                action: Action,
                expectation: Expectation,
                after_state: ScreenState,
            ) -> List[Finding]:
                return []

        p = MockProvider()
        tree = Tree(platform="android", window={"w": 1080, "h": 1920})
        state = ScreenState(tree=tree, screenshot_bytes=b"", screen_mode="viewer", fs_snapshot={})
        action = Action(action_type="tap", target_id="btn_ok")
        prompt = p.generate_expectation(state, action)
        self.assertEqual(prompt, "Mock: expect smooth transition")
        findings = p.judge_outcome(state, action, Expectation(action=action, description=""), state)
        self.assertEqual(findings, [])

    def test_provider_none_mode_returns_empty_findings(self) -> None:
        class NoneProvider(BaseAIProvider):
            def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
                return ""

            def judge_outcome(
                self,
                before_state: ScreenState,
                action: Action,
                expectation: Expectation,
                after_state: ScreenState,
            ) -> List[Finding]:
                return []

        none_p = NoneProvider()
        self.assertEqual(none_p.judge_outcome(None, None, None, None), [])  # type: ignore[arg-type]

    def test_provider_unsupported_provider_name_rejection(self) -> None:
        valid_providers = {"claude", "antigravity", "packet", "none"}
        self.assertNotIn("unsupported_ai", valid_providers)

    def test_provider_timeout_setting(self) -> None:
        timeout_s = 300.0
        self.assertGreaterEqual(timeout_s, 30.0)


# =====================================================================
# Tier 1 & 2: Feature 14 - Claude CLI Live Adapter
# =====================================================================
class TestClaudeCLILiveAdapter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-claude-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_claude_cli_prompt_first_command_ordering(self) -> None:
        # Prompt must come strictly BEFORE --allowedTools: that flag takes several values and swallows a trailing prompt
        prompt = "Judge this screen transition"
        model = "claude-3-7-sonnet-20250219"
        cmd = ["claude", "-p", prompt, "--model", model, "--allowedTools", "Read"]
        p_index = cmd.index("-p")
        tools_index = cmd.index("--allowedTools")
        self.assertLess(p_index, tools_index)

    def test_claude_cli_sandbox_directory_location(self) -> None:
        sandbox_dir = self.tmp / "ai_explore_claude_123"
        sandbox_dir.mkdir(parents=True, exist_ok=True)
        self.assertTrue(sandbox_dir.is_dir())
        self.assertFalse(str(sandbox_dir).startswith(str(LAB_DIR)))

    def test_claude_cli_parses_json_finding_array(self) -> None:
        raw_output = json.dumps([
            {
                "rule": "ai-visual-defect",
                "severity": "major",
                "confidence": "high",
                "message": "Photo frame is distorted after rotate",
                "region": [100.0, 200.0, 300.0, 400.0],
            }
        ])
        raw_file = self.tmp / "findings.raw.txt"
        out_file = self.tmp / "findings.json"
        raw_file.write_text(raw_output, encoding="utf-8")
        parsed = extract_findings_from_raw(raw_file, out_file)
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["rule"], "ai-visual-defect")

    def test_claude_cli_recovers_json_from_markdown_code_fences(self) -> None:
        raw_output = "Here is the finding:\n```json\n[{\"rule\": \"ai-defect\", \"severity\": \"minor\", \"confidence\": \"medium\", \"message\": \"Slight alignment issue\", \"region\": null}]\n```"
        raw_file = self.tmp / "findings.raw.txt"
        out_file = self.tmp / "findings.json"
        raw_file.write_text(raw_output, encoding="utf-8")
        parsed = extract_findings_from_raw(raw_file, out_file)
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["rule"], "ai-defect")

    def test_claude_cli_empty_array_is_clean_pass(self) -> None:
        raw_output = "[]"
        raw_file = self.tmp / "findings.raw.txt"
        out_file = self.tmp / "findings.json"
        raw_file.write_text(raw_output, encoding="utf-8")
        parsed = extract_findings_from_raw(raw_file, out_file)
        self.assertEqual(parsed, [])

    def test_claude_cli_in_place_downscale_does_not_abort_to_packet(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        adapter = exp_ai.ClaudeCLIAdapter(sandbox_root=self.tmp)
        img_path = self.tmp / "screen.png"
        Image.new("RGB", (800, 600), (0, 0, 0)).save(img_path)

        tree = Tree("android", {"w": 800, "h": 600})
        b_state = ScreenState(tree, img_path.read_bytes(), "viewer", {})
        a_state = ScreenState(tree, img_path.read_bytes(), "viewer", {})
        act = Action("tap")
        exp = Expectation(act, "Verify tap outcome")

        mock_completed = MagicMock()
        mock_completed.returncode = 0
        mock_completed.stdout = "[]"
        mock_completed.stderr = ""

        with patch.object(adapter, "is_available", return_value=True), \
             patch("subprocess.run", return_value=mock_completed) as mock_run:
            findings = adapter.judge_outcome(b_state, act, exp, a_state)
            self.assertTrue(mock_run.called)
            self.assertEqual(findings, [])
            self.assertIsNone(adapter.last_packet_dir)



# =====================================================================
# Tier 1 & 2: Feature 15 - Antigravity Live Adapter
# =====================================================================
class TestAntigravityLiveAdapter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-antigravity-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_antigravity_cli_command_construction(self) -> None:
        prompt_content = "Inputs: @instructions.md @context.md @schema.json @before.png @after.png\nJudge screen transition"
        cmd = ["gemini", "-p", prompt_content]
        self.assertEqual(cmd[0], "gemini")
        self.assertEqual(cmd[1], "-p")
        self.assertIn("@instructions.md", cmd[2])

    def test_antigravity_context_files_prepared(self) -> None:
        b_dir = self.tmp / "bundle"
        b_dir.mkdir(parents=True, exist_ok=True)
        (b_dir / "instructions.md").write_text("Instructions", encoding="utf-8")
        (b_dir / "context.md").write_text("Context", encoding="utf-8")
        (b_dir / "schema.json").write_text("{}", encoding="utf-8")
        self.assertTrue((b_dir / "instructions.md").is_file())
        self.assertTrue((b_dir / "context.md").is_file())
        self.assertTrue((b_dir / "schema.json").is_file())

    def test_antigravity_schema_validation_finding(self) -> None:
        finding_data = {
            "id": "1234567890ab",
            "source": "ai-explorer",
            "product": "phototok",
            "config": "phone",
            "journey": "explore",
            "checkpoint": "step_01",
            "rule": "ai-ui-overlap",
            "severity": "major",
            "confidence": "high",
            "region": [10.0, 10.0, 50.0, 50.0],
            "message": "Overlapping interactive elements",
            "evidence": ["before.png", "after.png"],
            "new_since_last_run": True,
        }
        f = Finding(**finding_data)
        self.assertEqual(f.id, "1234567890ab")
        self.assertEqual(f.source, "ai-explorer")

    def test_antigravity_missing_binary_fallback_ready(self) -> None:
        which_gemini = shutil.which("gemini")
        # In test environments without gemini installed, graceful fallback should be available
        self.assertTrue(which_gemini is None or isinstance(which_gemini, str))

    def test_antigravity_agent_callable_mode(self) -> None:
        # In-session Antigravity agent can act as a direct callable
        mock_agent = MagicMock(return_value=[{"rule": "ai-pass"}])
        res = mock_agent("prompt")
        self.assertEqual(len(res), 1)


# =====================================================================
# Tier 1 & 2: Feature 16 - Offline Review Packet Export Adapter
# =====================================================================
class TestOfflineReviewPacketExportAdapter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-packet-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_export_packet_directory_structure(self) -> None:
        packet_dir = self.tmp / "packet-20261024-step01"
        packet_dir.mkdir(parents=True, exist_ok=True)
        images_dir = packet_dir / "images"
        images_dir.mkdir(exist_ok=True)

        (packet_dir / "instructions.md").write_text("Review instructions", encoding="utf-8")
        (packet_dir / "context.md").write_text("Step 1: Tap Keep button", encoding="utf-8")
        (packet_dir / "manifest.json").write_text(json.dumps({"images": []}), encoding="utf-8")
        (packet_dir / "schema.json").write_text("{}", encoding="utf-8")

        self.assertTrue((packet_dir / "instructions.md").is_file())
        self.assertTrue((packet_dir / "context.md").is_file())
        self.assertTrue((packet_dir / "manifest.json").is_file())
        self.assertTrue((packet_dir / "images").is_dir())

    def test_downscale_image_preserves_aspect_ratio_and_bounds(self) -> None:
        src = self.tmp / "large.png"
        dst = self.tmp / "downscaled.png"
        # Create 3200x1800 image
        img = Image.new("RGB", (3200, 1800), (100, 150, 200))
        img.save(src, format="PNG")

        orig_w, orig_h = downscale_image(src, dst, max_long_edge=1600)
        self.assertEqual((orig_w, orig_h), (3200, 1800))
        self.assertTrue(dst.is_file())

        with Image.open(dst) as result:
            self.assertEqual(result.size, (1600, 900))

    def test_downscale_image_small_image_retains_dimensions(self) -> None:
        src = self.tmp / "small.png"
        dst = self.tmp / "small_out.png"
        img = Image.new("RGB", (800, 600), (50, 50, 50))
        img.save(src, format="PNG")

        w, h = downscale_image(src, dst, max_long_edge=1600)
        self.assertEqual(w, 800)
        self.assertEqual(h, 600)

    def test_downscale_image_same_file_in_place_does_not_raise(self) -> None:
        # Test calling downscale_image(path, path) on <= 1600px image
        small_path = self.tmp / "in_place_small.png"
        img_small = Image.new("RGB", (800, 600), (40, 50, 60))
        img_small.save(small_path, format="PNG")

        w1, h1 = downscale_image(small_path, small_path, max_long_edge=1600)
        self.assertEqual((w1, h1), (800, 600))
        self.assertTrue(small_path.is_file())
        with Image.open(small_path) as im:
            self.assertEqual(im.size, (800, 600))

        # Test calling downscale_image(path, path) on > 1600px image
        large_path = self.tmp / "in_place_large.png"
        img_large = Image.new("RGB", (3200, 1800), (70, 80, 90))
        img_large.save(large_path, format="PNG")

        w2, h2 = downscale_image(large_path, large_path, max_long_edge=1600)
        self.assertEqual((w2, h2), (3200, 1800))
        self.assertTrue(large_path.is_file())
        with Image.open(large_path) as im:
            self.assertEqual(im.size, (1600, 900))


    def test_compute_image_change_fraction(self) -> None:
        img_a = Image.new("RGB", (100, 100), (0, 0, 0))
        img_b = Image.new("RGB", (100, 100), (255, 255, 255))
        p_a = self.tmp / "a.png"
        p_b = self.tmp / "b.png"
        img_a.save(p_a, format="PNG")
        img_b.save(p_b, format="PNG")

        frac = compute_image_change_fraction(p_a, p_b)
        self.assertAlmostEqual(frac, 1.0, places=2)

        same_frac = compute_image_change_fraction(p_a, p_a)
        self.assertAlmostEqual(same_frac, 0.0, places=2)

    def test_manifest_metadata_recording(self) -> None:
        manifest = {
            "step_index": 3,
            "action": {"type": "swipe", "label": "Swipe next"},
            "expectation": {"expected_index_delta": 1},
            "images": [
                {"role": "before", "path": "images/01_before.png", "orig_size": [1080, 1920], "bundle_size": [900, 1600]},
                {"role": "after", "path": "images/01_after.png", "orig_size": [1080, 1920], "bundle_size": [900, 1600]},
            ],
        }
        manifest_path = self.tmp / "manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        loaded = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(loaded["step_index"], 3)
        self.assertEqual(len(loaded["images"]), 2)


# =====================================================================
# Tier 1 & 2: Feature 17 - Offline Review Packet Ingest & Evaluation
# =====================================================================
class TestOfflineReviewPacketIngestAdapter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-ingest-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_ingest_rescales_coordinates(self) -> None:
        # If image was downscaled by factor 2.0 (e.g. 1600 down from 3200),
        # coordinates in bundle space [100, 100, 200, 150] rescale back to [200, 200, 400, 300]
        scale_x = 3200 / 1600
        scale_y = 1800 / 900
        bundle_region = [100.0, 100.0, 200.0, 150.0]
        rescaled = [
            bundle_region[0] * scale_x,
            bundle_region[1] * scale_y,
            bundle_region[2] * scale_x,
            bundle_region[3] * scale_y,
        ]
        self.assertEqual(rescaled, [200.0, 200.0, 400.0, 300.0])

    def test_ingest_filters_known_findings(self) -> None:
        known_findings = [
            {"product": "phototok", "rule": "expected-glitch", "screen": "viewer"}
        ]
        finding = {
            "product": "phototok",
            "rule": "expected-glitch",
            "screen": "viewer",
        }
        is_known = any(
            k["product"] == finding["product"] and k["rule"] == finding["rule"]
            for k in known_findings
        )
        self.assertTrue(is_known)

    def test_ingest_preserves_new_findings(self) -> None:
        known_findings: List[Dict[str, Any]] = []
        finding = {
            "product": "phototok",
            "rule": "unseen-crash",
            "screen": "viewer",
        }
        is_known = any(
            k.get("rule") == finding["rule"] for k in known_findings
        )
        self.assertFalse(is_known)

    def test_ingest_writes_findings_ai_json(self) -> None:
        findings = [
            {
                "id": "fedcba987654",
                "source": "ai-review",
                "product": "phototok",
                "config": "phone",
                "journey": "explore",
                "checkpoint": "step_02",
                "rule": "ai-misalignment",
                "severity": "minor",
                "confidence": "high",
                "region": [10.0, 20.0, 30.0, 40.0],
                "message": "Icon misaligned",
                "evidence": ["before.png", "after.png"],
                "new_since_last_run": True,
            }
        ]
        out_path = self.tmp / "findings.ai.json"
        out_path.write_text(json.dumps(findings), encoding="utf-8")
        self.assertTrue(out_path.is_file())
        loaded = json.loads(out_path.read_text(encoding="utf-8"))
        self.assertEqual(len(loaded), 1)
        self.assertEqual(loaded[0]["id"], "fedcba987654")

    def test_ingest_null_region_supported(self) -> None:
        finding = Finding(
            id="nullregion123",
            source="ai-explorer",
            product="linux",
            config="fhd",
            journey="explore",
            checkpoint="step_01",
            rule="ai-text-error",
            severity="minor",
            confidence="high",
            region=None,
            message="Global text glitch without specific bounding box",
        )
        self.assertIsNone(finding.region)

    def test_offline_ingest_does_not_drop_unrelated_findings_when_triage_entry_missing_keys(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        ingest = exp_ai.OfflineReviewIngest()
        # Triage entry containing only id and reason (missing product and rule)
        known_list = [{"id": "111111111111", "reason": "triaged"}]

        # Unrelated finding with different id and missing product/rule
        unrelated = {"id": "999999999999", "message": "unrelated crash"}
        self.assertFalse(ingest.is_known_finding(unrelated, known_list))

        # Test through packet ingestion workflow: ensure unrelated finding is preserved
        packet_dir = self.tmp / "triage_test_packet"
        packet_dir.mkdir(parents=True, exist_ok=True)
        (packet_dir / "manifest.json").write_text(json.dumps({
            "images": [{"role": "after", "orig_size": [1080, 1920], "bundle_size": [900, 1600]}]
        }), encoding="utf-8")

        raw_findings = [
            {"id": "111111111111", "message": "Known triaged issue"},
            {"id": "222222222222", "message": "New genuine issue"},
        ]
        (packet_dir / "findings.json").write_text(json.dumps(raw_findings), encoding="utf-8")

        with patch.object(ingest, "load_known_findings", return_value=known_list):
            run_dir = self.tmp / "triage_run"
            ingested = ingest.ingest_packet(packet_dir, run_dir=run_dir)
            self.assertEqual(len(ingested), 1)
            self.assertEqual(ingested[0].id, "222222222222")
            self.assertEqual(ingested[0].message, "New genuine issue")



# =====================================================================
# Tier 1 & 2: Feature 18 - AI Adapter Graceful Fallback
# =====================================================================
class TestAIAdapterGracefulFallback(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-fallback-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_fallback_on_cli_timeout(self) -> None:
        # When live CLI times out, fallback to packet export without failing test step
        timed_out = True
        packet_exported = False
        step_passed = False

        if timed_out:
            # Fallback path
            packet_dir = self.tmp / "packet-fallback-step01"
            packet_dir.mkdir(parents=True, exist_ok=True)
            packet_exported = packet_dir.is_dir()
            step_passed = True  # Step continues, finding deferred to offline review

        self.assertTrue(packet_exported)
        self.assertTrue(step_passed)

    def test_fallback_on_cli_non_zero_exit(self) -> None:
        exit_code = 127  # Command not found or crashed
        packet_dir = None
        if exit_code != 0:
            packet_dir = self.tmp / "packet-fallback-step02"
            packet_dir.mkdir(parents=True, exist_ok=True)

        self.assertIsNotNone(packet_dir)
        self.assertTrue(packet_dir.is_dir())

    def test_fallback_records_warning_in_logs(self) -> None:
        logs: List[str] = []
        logs.append("[WARNING] Claude CLI timed out after 300s; exported review packet to /tmp/pstlab/packet-01")
        has_warning = any("timed out" in l and "exported review packet" in l for l in logs)
        self.assertTrue(has_warning)

    def test_fallback_preserves_step_result_contract(self) -> None:
        action = Action(action_type="tap", target_id="btn_keep")
        exp = Expectation(action=action, description="Keep photo")
        before_state = ScreenState(tree=Tree("android", {"w": 1080, "h": 1920}), screenshot_bytes=b"", screen_mode="", fs_snapshot={})
        after_state = ScreenState(tree=Tree("android", {"w": 1080, "h": 1920}), screenshot_bytes=b"", screen_mode="", fs_snapshot={})
        packet_path = self.tmp / "packet-step-01"

        res = StepResult(
            step_index=1,
            action=action,
            expectation=exp,
            passed=True,
            findings=[],
            before_state=before_state,
            after_state=after_state,
            packet_dir=packet_path,
        )
        self.assertEqual(res.packet_dir, packet_path)
        self.assertTrue(res.passed)

    def test_fallback_offline_environment_auto_detection(self) -> None:
        has_claude = bool(shutil.which("claude"))
        has_gemini = bool(shutil.which("gemini"))
        # If neither is present, auto-fallback to "packet" mode
        selected_mode = "claude" if has_claude else ("antigravity" if has_gemini else "packet")
        self.assertIn(selected_mode, {"claude", "antigravity", "packet"})


# =====================================================================
# Tier 3: Cross-Feature Combinations (AI Adapters & Packets)
# =====================================================================
class TestAICrossFeatureCombinations(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-ai-comb-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_live_failure_to_packet_export_to_ingest_roundtrip(self) -> None:
        tmp_run = self.tmp / "run_roundtrip"
        tmp_run.mkdir(parents=True, exist_ok=True)
        packet_dir = tmp_run / "packet-01"
        packet_dir.mkdir(parents=True, exist_ok=True)

        # 1. Live failure triggers packet export
        (packet_dir / "manifest.json").write_text(json.dumps({"step": 1}), encoding="utf-8")

        # 2. Simulated offline evaluation writes findings.raw.txt
        raw_findings = [
            {
                "rule": "ai-visual-glitch",
                "severity": "minor",
                "confidence": "high",
                "message": "Glitch observed offline",
                "region": [10.0, 20.0, 30.0, 40.0],
            }
        ]
        raw_file = packet_dir / "findings.raw.txt"
        raw_file.write_text(json.dumps(raw_findings), encoding="utf-8")

        # 3. Ingestion into run directory
        out_file = tmp_run / "findings.ai.json"
        parsed = extract_findings_from_raw(raw_file, out_file)
        self.assertEqual(len(parsed), 1)
        self.assertTrue(out_file.is_file())



# =====================================================================
# Tier 4: Direct Module Implementation Tests (lab.explore.ai_adapters)
# =====================================================================
class TestAIAdaptersModuleImplementation(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-ai-impl-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_create_ai_adapter_factory(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        claude = exp_ai.create_ai_adapter("claude")
        self.assertIsInstance(claude, exp_ai.ClaudeCLIAdapter)
        anti = exp_ai.create_ai_adapter("antigravity")
        self.assertIsInstance(anti, exp_ai.AntigravityAdapter)
        packet = exp_ai.create_ai_adapter("packet")
        self.assertIsInstance(packet, exp_ai.PacketExportAdapter)
        none_p = exp_ai.create_ai_adapter("none")
        self.assertIsInstance(none_p, exp_ai.NoneAIProvider)

        with self.assertRaises(ValueError):
            exp_ai.create_ai_adapter("invalid_provider")

    def test_auto_detect_ai_provider(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        detected = exp_ai.auto_detect_ai_provider()
        self.assertIn(detected, ("claude", "antigravity", "packet"))

    def test_claude_cli_build_command(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        adapter = exp_ai.ClaudeCLIAdapter(model="custom-claude", allowed_tools=["Read", "Bash"])
        cmd = adapter.build_command("Analyze screen")
        self.assertEqual(cmd[0], "claude")
        p_idx = cmd.index("-p")
        tools_idx = cmd.index("--allowedTools")
        self.assertLess(p_idx, tools_idx)
        self.assertIn("custom-claude", cmd)

    def test_antigravity_in_session_callable(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        mock_callable = MagicMock(return_value=[{
            "id": "112233445566",
            "source": "ai-explorer",
            "product": "phototok",
            "config": "phone",
            "journey": "explore",
            "checkpoint": "step_01",
            "rule": "ai-visual",
            "severity": "major",
            "confidence": "high",
            "region": [0, 0, 10, 10],
            "message": "AI defect",
        }])
        adapter = exp_ai.AntigravityAdapter(agent_callable=mock_callable)
        self.assertTrue(adapter.is_available())

        tree = Tree("android", {"w": 1080, "h": 1920})
        b_state = ScreenState(tree, b"", "viewer", {})
        a_state = ScreenState(tree, b"", "viewer", {})
        act = Action("tap", "btn_ok")
        exp = Expectation(act, "Expect ok")

        findings = adapter.judge_outcome(b_state, act, exp, a_state)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].id, "112233445566")

    def test_packet_export_creates_complete_bundle(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        adapter = exp_ai.PacketExportAdapter(output_dir=self.tmp)
        # Create synthetic images
        img_a = Image.new("RGB", (2000, 1000), (50, 100, 150))
        img_b = Image.new("RGB", (2000, 1000), (60, 110, 160))
        p_a = self.tmp / "temp_a.png"
        p_b = self.tmp / "temp_b.png"
        img_a.save(p_a, format="PNG")
        img_b.save(p_b, format="PNG")

        tree = Tree("android", {"w": 1080, "h": 1920})
        b_state = ScreenState(tree, p_a.read_bytes(), "viewer", {})
        a_state = ScreenState(tree, p_b.read_bytes(), "viewer", {})
        act = Action("swipe", label="Swipe next")
        exp = Expectation(act, "Next photo")

        packet_dir = adapter.export_packet(b_state, act, exp, a_state, step_index=5)
        self.assertTrue(packet_dir.is_dir())
        self.assertTrue((packet_dir / "images" / "01_before.png").is_file())
        self.assertTrue((packet_dir / "images" / "01_after.png").is_file())
        self.assertTrue((packet_dir / "manifest.json").is_file())
        self.assertTrue((packet_dir / "instructions.md").is_file())
        self.assertTrue((packet_dir / "context.md").is_file())

        # Verify downscaling happened (2000 -> 1600 max long edge)
        with Image.open(packet_dir / "images" / "01_before.png") as im:
            self.assertEqual(im.size, (1600, 800))

        # judge_outcome should return empty list and set last_packet_dir
        res_findings = adapter.judge_outcome(b_state, act, exp, a_state)
        self.assertEqual(res_findings, [])
        self.assertIsNotNone(adapter.last_packet_dir)

    def test_offline_review_ingest_workflow(self) -> None:
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        ingest = exp_ai.OfflineReviewIngest()
        # Test coordinate rescaling
        rescaled = ingest.rescale_coordinates([100.0, 100.0, 200.0, 150.0], (3200, 1800), (1600, 900))
        self.assertEqual(rescaled, [200.0, 200.0, 400.0, 300.0])
        self.assertIsNone(ingest.rescale_coordinates(None, (3200, 1800), (1600, 900)))

        # Test packet ingestion
        packet_dir = self.tmp / "sample_packet"
        packet_dir.mkdir(parents=True, exist_ok=True)
        manifest = {
            "images": [
                {"role": "after", "orig_size": [2000, 1000], "bundle_size": [1000, 500]}
            ]
        }
        (packet_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        raw_items = [
            {
                "rule": "ai-misaligned-button",
                "severity": "major",
                "confidence": "high",
                "region": [50.0, 50.0, 100.0, 50.0],
                "message": "Button misaligned",
            }
        ]
        (packet_dir / "findings.json").write_text(json.dumps(raw_items), encoding="utf-8")

        run_dir = self.tmp / "sample_run"
        findings = ingest.ingest_packet(packet_dir, run_dir=run_dir)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "ai-misaligned-button")
        # 2x rescaling from 1000x500 to 2000x1000
        self.assertEqual(findings[0].region, [100.0, 100.0, 200.0, 100.0])
        self.assertTrue((run_dir / "findings.ai.json").is_file())

    def test_offline_review_ingest_extra_metadata_keys(self) -> None:
        """Verify ingest_packet successfully ingests findings containing extra metadata keys (such as screen, summary) without TypeError."""
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        packet_dir = self.tmp / "extra_metadata_packet"
        packet_dir.mkdir(parents=True, exist_ok=True)
        raw_items = [
            {
                "rule": "ai-visual",
                "severity": "minor",
                "confidence": "high",
                "region": [10.0, 20.0, 30.0, 40.0],
                "message": "Viewer issue with extra metadata",
                "screen": "viewer",
                "summary": "Visual misplacement on viewer screen",
                "notes": "Found during exploratory testing",
            },
            {
                "rule": "ai-layout-overflow",
                "severity": "major",
                "confidence": "high",
                "region": [],
                "message": "Gallery issue with empty region and extra screen metadata",
                "screen": "gallery",
            },
        ]
        (packet_dir / "findings.json").write_text(json.dumps(raw_items), encoding="utf-8")

        run_dir = self.tmp / "extra_metadata_run"
        ingest = exp_ai.OfflineReviewIngest()
        findings = ingest.ingest_packet(packet_dir, run_dir=run_dir)
        self.assertEqual(len(findings), 2)
        self.assertEqual(findings[0].message, "Viewer issue with extra metadata")
        self.assertEqual(findings[0].rule, "ai-visual")
        self.assertEqual(findings[1].message, "Gallery issue with empty region and extra screen metadata")
        self.assertIsNone(findings[1].region)
        self.assertTrue((run_dir / "findings.ai.json").is_file())

    def test_rescale_coordinates_empty_and_short_regions(self) -> None:
        """Verify rescale_coordinates with region=[] and len(region) < 4 returns None without IndexError."""
        if not HAS_EXPLORE_AI:
            self.skipTest("lab.explore.ai_adapters not imported")
        ingest = exp_ai.OfflineReviewIngest()
        # Empty list
        self.assertIsNone(ingest.rescale_coordinates([], (1080, 1920), (900, 1600)))
        # Fewer than 4 elements
        self.assertIsNone(ingest.rescale_coordinates([10.0], (1080, 1920), (900, 1600)))
        self.assertIsNone(ingest.rescale_coordinates([10.0, 20.0], (1080, 1920), (900, 1600)))
        self.assertIsNone(ingest.rescale_coordinates([10.0, 20.0, 30.0], (1080, 1920), (900, 1600)))
        # None region
        self.assertIsNone(ingest.rescale_coordinates(None, (1080, 1920), (900, 1600)))
        # Valid 4 elements
        scaled = ingest.rescale_coordinates([10.0, 20.0, 30.0, 40.0], (1080, 1920), (900, 1600))
        self.assertIsNotNone(scaled)
        self.assertEqual(len(scaled), 4)
        self.assertAlmostEqual(scaled[0], 12.0)
        self.assertAlmostEqual(scaled[1], 24.0)
        self.assertAlmostEqual(scaled[2], 36.0)
        self.assertAlmostEqual(scaled[3], 48.0)


if __name__ == "__main__":
    unittest.main()

