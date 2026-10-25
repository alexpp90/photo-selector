"""AI Provider adapter framework, review packet export/ingest, and graceful fallback.

Supports Claude CLI (`claude -p`), Antigravity (`gemini -p` or in-session callable),
offline packet bundles (`packet-*/`), and null passthrough execution.
"""

from __future__ import annotations

import abc
import datetime as dt
import json
import logging
import os
import re
import shutil
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import yaml
from PIL import Image

from ..ingest import extract_findings_from_raw
from ..model import Finding, Tree
from ..paths import lab_tmp_root, repo_root
from ..review_bundle import compute_image_change_fraction, downscale_image
from .actions import Action, ScreenState

logger = logging.getLogger(__name__)


def _action_summary(action: Any) -> str:
    if hasattr(action, "summary"):
        return action.summary()
    tgt = f" on {getattr(action, 'target_id', None)}" if getattr(action, "target_id", None) else ""
    return f"[{getattr(action, 'action_type', 'action')}]{tgt} {getattr(action, 'label', '')}".strip()


# =====================================================================
# Base AI Provider Interface
# =====================================================================
class BaseAIProvider(abc.ABC):
    """Abstract base class for exploratory AI adapters (Feature 13)."""

    name: str = "base"
    timeout_s: float = 300.0

    @abc.abstractmethod
    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        """Generate open-ended expectation prompt before an action runs."""
        raise NotImplementedError

    @abc.abstractmethod
    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        """Judge observed screen state post-action and report visual findings."""
        raise NotImplementedError

    def judge_step(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        """Convenience alias to judge_outcome."""
        return self.judge_outcome(before_state, action, expectation, after_state)

    @abc.abstractmethod
    def is_available(self) -> bool:
        """Check whether provider runtime prerequisites are met."""
        raise NotImplementedError


# Public alias conforming to PROJECT.md
AIProvider = BaseAIProvider


# =====================================================================
# None AI Provider (Null / Passthrough)
# =====================================================================
class NoneAIProvider(BaseAIProvider):
    """Null AI provider for pure deterministic testing without AI overhead."""

    name: str = "none"

    def __init__(self, **kwargs: Any) -> None:
        self.timeout_s = 0.0

    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        return ""

    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        return []

    def is_available(self) -> bool:
        return True


# Alias conforming to test suite
NoneProvider = NoneAIProvider


# =====================================================================
# Offline Review Packet Export Adapter
# =====================================================================
class PacketExportAdapter(BaseAIProvider):
    """Exports self-contained review bundles for offline AI or human evaluation (Feature 16)."""

    name: str = "packet"

    def __init__(
        self,
        output_dir: Optional[Path] = None,
        max_long_edge: int = 1600,
        timeout_s: float = 300.0,
        **kwargs: Any,
    ) -> None:
        self.output_dir = output_dir
        self.max_long_edge = max_long_edge
        self.timeout_s = timeout_s
        self.last_packet_dir: Optional[Path] = None

    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        return f"Expectation for action '{_action_summary(action)}': screen settles cleanly without visual glitches."

    def export_packet(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
        step_index: int = 1,
    ) -> Path:
        """Create and populate a self-contained packet-*/ bundle directory."""
        if self.output_dir:
            base_dir = self.output_dir
        else:
            base_dir = lab_tmp_root() / "packets"

        bundle_name = f"packet-{dt.datetime.now().strftime('%Y%m%d_%H%M%S')}-step{step_index:02d}"
        packet_dir = base_dir / bundle_name
        images_dir = packet_dir / "images"
        images_dir.mkdir(parents=True, exist_ok=True)

        raw_before_path = packet_dir / "raw_before.png"
        raw_after_path = packet_dir / "raw_after.png"
        raw_before_path.write_bytes(before_state.screenshot_bytes or b"")
        raw_after_path.write_bytes(after_state.screenshot_bytes or b"")

        # Downscale images if needed
        dest_before_path = images_dir / "01_before.png"
        dest_after_path = images_dir / "01_after.png"

        orig_w1, orig_h1 = (1080, 1920)
        orig_w2, orig_h2 = (1080, 1920)
        bundle_w1, bundle_h1 = (1080, 1920)
        bundle_w2, bundle_h2 = (1080, 1920)

        if raw_before_path.stat().st_size > 0:
            try:
                orig_w1, orig_h1 = downscale_image(raw_before_path, dest_before_path, max_long_edge=self.max_long_edge)
                with Image.open(dest_before_path) as im:
                    bundle_w1, bundle_h1 = im.size
            except Exception as e:
                logger.debug("Failed to downscale before image: %s", e)
                dest_before_path.write_bytes(before_state.screenshot_bytes)
        else:
            dest_before_path.write_bytes(b"")

        if raw_after_path.stat().st_size > 0:
            try:
                orig_w2, orig_h2 = downscale_image(raw_after_path, dest_after_path, max_long_edge=self.max_long_edge)
                with Image.open(dest_after_path) as im:
                    bundle_w2, bundle_h2 = im.size
            except Exception as e:
                logger.debug("Failed to downscale after image: %s", e)
                dest_after_path.write_bytes(after_state.screenshot_bytes)
        else:
            dest_after_path.write_bytes(b"")

        # Clean up temporary raw screenshots
        if raw_before_path.is_file():
            raw_before_path.unlink()
        if raw_after_path.is_file():
            raw_after_path.unlink()

        # Compute image delta fraction
        change_fraction = 0.0
        if dest_before_path.is_file() and dest_after_path.is_file() and dest_before_path.stat().st_size > 0 and dest_after_path.stat().st_size > 0:
            try:
                change_fraction = compute_image_change_fraction(dest_before_path, dest_after_path)
            except Exception as e:
                logger.debug("Failed to compute image change fraction: %s", e)

        # Write metadata & context files
        instructions_text = (
            "# UI Review Instructions\n"
            "Compare 01_before.png and 01_after.png against the recorded expectation.\n"
            "Emit a JSON array of findings adhering to schema.json.\n"
        )
        (packet_dir / "instructions.md").write_text(instructions_text, encoding="utf-8")

        exp_desc = getattr(expectation, "description", str(expectation))
        context_text = (
            f"# Step Context\n"
            f"Step: {step_index}\n"
            f"Action: {_action_summary(action)}\n"
            f"Expectation: {exp_desc}\n"
            f"Image change ratio: {change_fraction:.4f}\n"
        )
        (packet_dir / "context.md").write_text(context_text, encoding="utf-8")

        # Serialized tree representations
        before_tree_dict = before_state.tree.to_dict() if hasattr(before_state.tree, "to_dict") else {}
        after_tree_dict = after_state.tree.to_dict() if hasattr(after_state.tree, "to_dict") else {}
        (packet_dir / "before.tree.json").write_text(json.dumps(before_tree_dict, indent=2), encoding="utf-8")
        (packet_dir / "after.tree.json").write_text(json.dumps(after_tree_dict, indent=2), encoding="utf-8")

        # Manifest
        manifest_data = {
            "step_index": step_index,
            "action": action.to_dict() if hasattr(action, "to_dict") else {"action_type": action.action_type},
            "expectation": expectation.to_dict() if hasattr(expectation, "to_dict") else {"description": exp_desc},
            "image_change_fraction": change_fraction,
            "images": [
                {
                    "role": "before",
                    "path": "images/01_before.png",
                    "orig_size": [orig_w1, orig_h1],
                    "bundle_size": [bundle_w1, bundle_h1],
                },
                {
                    "role": "after",
                    "path": "images/01_after.png",
                    "orig_size": [orig_w2, orig_h2],
                    "bundle_size": [bundle_w2, bundle_h2],
                },
            ],
        }
        (packet_dir / "manifest.json").write_text(json.dumps(manifest_data, indent=2), encoding="utf-8")

        # Copy schema if available
        try:
            schema_src = repo_root() / "scripts" / "lab" / "schemas" / "finding.schema.json"
            if schema_src.is_file():
                shutil.copy2(schema_src, packet_dir / "schema.json")
            else:
                (packet_dir / "schema.json").write_text("{}", encoding="utf-8")
        except Exception:
            (packet_dir / "schema.json").write_text("{}", encoding="utf-8")

        self.last_packet_dir = packet_dir
        return packet_dir

    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        """Export review packet and defer findings to offline review."""
        self.export_packet(before_state, action, expectation, after_state)
        return []

    def is_available(self) -> bool:
        return True


# =====================================================================
# Claude CLI Live Adapter
# =====================================================================
class ClaudeCLIAdapter(BaseAIProvider):
    """Live AI judging via Claude CLI with prompt-first argument ordering (Feature 14)."""

    name: str = "claude"

    def __init__(
        self,
        model: str = "claude-3-7-sonnet-20250219",
        allowed_tools: Optional[List[str]] = None,
        timeout_s: float = 300.0,
        sandbox_root: Optional[Path] = None,
        fallback_adapter: Optional[BaseAIProvider] = None,
        **kwargs: Any,
    ) -> None:
        self.model = model
        self.allowed_tools = list(allowed_tools) if allowed_tools is not None else ["Read"]
        self.timeout_s = max(30.0, timeout_s)
        self.sandbox_root = sandbox_root
        self.fallback_adapter = fallback_adapter or PacketExportAdapter(timeout_s=self.timeout_s)
        self.last_packet_dir: Optional[Path] = None

    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        return (
            f"Verify that after executing action '{_action_summary(action)}', the screen renders smoothly, "
            f"the active photo view is valid, no UI elements overlap or clip, and no error dialogs appear."
        )

    def build_command(self, prompt: str) -> List[str]:
        """Format CLI invocation ensuring -p strictly precedes --allowedTools."""
        cmd = ["claude", "-p", prompt, "--model", self.model]
        if self.allowed_tools:
            cmd.extend(["--allowedTools", *self.allowed_tools])
        return cmd

    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        """Execute sandboxed live Claude CLI inspection with graceful fallback."""
        if not self.is_available():
            logger.warning("[WARNING] Claude CLI binary not found in PATH; degrading to packet export")
            exported = self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
            self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
            return exported

        # Create sandboxed workspace strictly under /tmp/pstlab/
        base_sandbox = self.sandbox_root or (lab_tmp_root() / f"ai_explore_claude_{uuid.uuid4().hex[:8]}")
        base_sandbox.mkdir(parents=True, exist_ok=True)

        try:
            # Prepare review files
            before_png = base_sandbox / "before.png"
            after_png = base_sandbox / "after.png"
            before_png.write_bytes(before_state.screenshot_bytes or b"")
            after_png.write_bytes(after_state.screenshot_bytes or b"")

            if before_png.stat().st_size > 0:
                downscale_image(before_png, before_png, max_long_edge=1600)
            if after_png.stat().st_size > 0:
                downscale_image(after_png, after_png, max_long_edge=1600)

            (base_sandbox / "before.tree.json").write_text(
                json.dumps(before_state.tree.to_dict() if hasattr(before_state.tree, "to_dict") else {}, indent=2),
                encoding="utf-8",
            )
            (base_sandbox / "after.tree.json").write_text(
                json.dumps(after_state.tree.to_dict() if hasattr(after_state.tree, "to_dict") else {}, indent=2),
                encoding="utf-8",
            )

            exp_desc = getattr(expectation, "description", str(expectation))
            (base_sandbox / "instructions.md").write_text(
                "You are an expert mobile/desktop UI QA judge. Inspect before.png and after.png against context.md. "
                "Emit ONLY a JSON array of finding objects conforming to schema.json.\n",
                encoding="utf-8",
            )
            (base_sandbox / "context.md").write_text(
                f"Action: {_action_summary(action)}\nExpectation: {exp_desc}\n", encoding="utf-8"
            )

            prompt = (
                f"Inspect before.png and after.png for action '{_action_summary(action)}'. "
                f"Expectation: {exp_desc}. Emit JSON array of visual findings."
            )
            cmd = self.build_command(prompt)

            proc = subprocess.run(
                cmd,
                cwd=str(base_sandbox),
                capture_output=True,
                text=True,
                timeout=self.timeout_s,
            )

            if proc.returncode != 0:
                logger.warning(
                    "[WARNING] Claude CLI exited with code %d; falling back to packet export", proc.returncode
                )
                self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
                self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
                return []

            raw_file = base_sandbox / "findings.raw.txt"
            raw_file.write_text(proc.stdout, encoding="utf-8")
            out_file = base_sandbox / "findings.json"
            parsed_items = extract_findings_from_raw(raw_file, out_file)

            findings: List[Finding] = []
            for item in parsed_items:
                f = Finding.from_dict(item) if hasattr(Finding, "from_dict") else Finding(**item)
                findings.append(f)
            return findings

        except (subprocess.TimeoutExpired, FileNotFoundError, Exception) as exc:
            logger.warning(
                "[WARNING] Claude CLI failed or timed out after %.1fs: %s; exporting review packet",
                self.timeout_s,
                exc,
            )
            self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
            self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
            return []

    def is_available(self) -> bool:
        return shutil.which("claude") is not None


# =====================================================================
# Antigravity Live Adapter
# =====================================================================
class AntigravityAdapter(BaseAIProvider):
    """Live AI judging via Gemini CLI or in-session agent callable (Feature 15)."""

    name: str = "antigravity"

    def __init__(
        self,
        gemini_binary: str = "gemini",
        agent_callable: Optional[Callable[[str], Any]] = None,
        timeout_s: float = 300.0,
        sandbox_root: Optional[Path] = None,
        fallback_adapter: Optional[BaseAIProvider] = None,
        **kwargs: Any,
    ) -> None:
        self.gemini_binary = gemini_binary
        self.agent_callable = agent_callable
        self.timeout_s = max(30.0, timeout_s)
        self.sandbox_root = sandbox_root
        self.fallback_adapter = fallback_adapter or PacketExportAdapter(timeout_s=self.timeout_s)
        self.last_packet_dir: Optional[Path] = None

    def generate_expectation(self, before_state: ScreenState, action: Action) -> str:
        return (
            f"Verify that after executing action '{_action_summary(action)}', the screen responds correctly, "
            f"elements stay within bounds, and no unexpected layout shifts occur."
        )

    def build_command(self, prompt: str) -> List[str]:
        """Format gemini CLI command with file references."""
        prompt_content = f"Inputs: @instructions.md @context.md @schema.json @before.png @after.png\n{prompt}"
        return [self.gemini_binary, "-p", prompt_content]

    def judge_outcome(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
    ) -> List[Finding]:
        """Execute Antigravity review via in-session agent or gemini CLI."""
        exp_desc = getattr(expectation, "description", str(expectation))
        prompt_text = f"Judge screen transition for action '{_action_summary(action)}'. Expectation: {exp_desc}."

        # In-session agent callable path
        if self.agent_callable is not None:
            try:
                res = self.agent_callable(prompt_text)
                findings: List[Finding] = []
                if isinstance(res, list):
                    for item in res:
                        if isinstance(item, Finding):
                            findings.append(item)
                        elif isinstance(item, dict):
                            item.setdefault("source", "ai-explorer")
                            item.setdefault("product", "phototok")
                            item.setdefault("config", "default")
                            item.setdefault("journey", "explore")
                            item.setdefault("checkpoint", "step_01")
                            item.setdefault("rule", "ai-visual")
                            item.setdefault("severity", "major")
                            item.setdefault("confidence", "high")
                            item.setdefault("region", None)
                            item.setdefault("message", "Visual defect detected by in-session agent")
                            item.setdefault("evidence", [])
                            item.setdefault("new_since_last_run", True)
                            if "id" not in item:
                                item["id"] = Finding.make_id(
                                    item["product"], item["journey"], item["checkpoint"], item["rule"]
                                )
                            findings.append(Finding(**item))
                return findings
            except Exception as exc:
                logger.warning("[WARNING] In-session Antigravity callable failed: %s; falling back to packet", exc)
                self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
                self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
                return []

        # Subprocess CLI path
        if not self.is_available():
            logger.warning("[WARNING] Gemini CLI not found; degrading to packet export")
            self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
            self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
            return []

        base_sandbox = self.sandbox_root or (lab_tmp_root() / f"ai_explore_gemini_{uuid.uuid4().hex[:8]}")
        base_sandbox.mkdir(parents=True, exist_ok=True)

        try:
            (base_sandbox / "instructions.md").write_text("Review UI transition\n", encoding="utf-8")
            (base_sandbox / "context.md").write_text(f"Action: {_action_summary(action)}\n", encoding="utf-8")
            (base_sandbox / "schema.json").write_text("{}", encoding="utf-8")
            (base_sandbox / "before.png").write_bytes(before_state.screenshot_bytes or b"")
            (base_sandbox / "after.png").write_bytes(after_state.screenshot_bytes or b"")

            cmd = self.build_command(prompt_text)
            proc = subprocess.run(
                cmd,
                cwd=str(base_sandbox),
                capture_output=True,
                text=True,
                timeout=self.timeout_s,
            )

            if proc.returncode != 0:
                logger.warning("[WARNING] Gemini CLI exited with code %d; falling back to packet", proc.returncode)
                self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
                self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
                return []

            raw_file = base_sandbox / "findings.raw.txt"
            raw_file.write_text(proc.stdout, encoding="utf-8")
            out_file = base_sandbox / "findings.json"
            parsed_items = extract_findings_from_raw(raw_file, out_file)

            findings = []
            for item in parsed_items:
                f = Finding.from_dict(item) if hasattr(Finding, "from_dict") else Finding(**item)
                findings.append(f)
            return findings

        except Exception as exc:
            logger.warning("[WARNING] Gemini CLI execution failed: %s; falling back to packet export", exc)
            self.fallback_adapter.judge_outcome(before_state, action, expectation, after_state)
            self.last_packet_dir = getattr(self.fallback_adapter, "last_packet_dir", None)
            return []

    def is_available(self) -> bool:
        return self.agent_callable is not None or shutil.which(self.gemini_binary) is not None


# =====================================================================
# Offline Review Packet Ingest
# =====================================================================
class OfflineReviewIngest:
    """Ingests findings from offline review packets, rescales coordinates, and filters known findings (Feature 17)."""

    def __init__(
        self,
        known_findings_path: Optional[Path] = None,
        today: Optional[dt.date] = None,
    ) -> None:
        self.known_findings_path = known_findings_path
        self.today = today or dt.date.today()

    @staticmethod
    def rescale_coordinates(
        region: Optional[List[float]],
        orig_size: Tuple[int, int] | List[int],
        bundle_size: Tuple[int, int] | List[int],
    ) -> Optional[List[float]]:
        """Rescale bounding box coordinates from review bundle size to native window coordinates."""
        if not region or len(region) < 4:
            return None
        orig_w, orig_h = orig_size
        bundle_w, bundle_h = bundle_size
        scale_x = orig_w / max(1, bundle_w)
        scale_y = orig_h / max(1, bundle_h)
        return [
            region[0] * scale_x,
            region[1] * scale_y,
            region[2] * scale_x,
            region[3] * scale_y,
        ]

    def load_known_findings(self) -> List[Dict[str, Any]]:
        """Load active known findings from YAML file."""
        if not self.known_findings_path or not self.known_findings_path.is_file():
            return []
        try:
            raw = yaml.safe_load(self.known_findings_path.read_text(encoding="utf-8"))
            if not isinstance(raw, list):
                return []
            active: List[Dict[str, Any]] = []
            for entry in raw:
                if not isinstance(entry, dict):
                    continue
                until_val = entry.get("until")
                if until_val:
                    try:
                        until_date = (
                            until_val
                            if isinstance(until_val, dt.date)
                            else dt.date.fromisoformat(str(until_val))
                        )
                        if until_date < self.today:
                            continue
                    except ValueError:
                        pass
                active.append(entry)
            return active
        except Exception:
            return []

    def is_known_finding(self, finding: Dict[str, Any], known_list: List[Dict[str, Any]]) -> bool:
        """Check if finding matches an active known/triaged entry."""
        for k in known_list:
            if "id" in k and k.get("id") and k.get("id") == finding.get("id"):
                return True
            k_prod = k.get("product")
            f_prod = finding.get("product")
            k_rule = k.get("rule")
            f_rule = finding.get("rule")
            if k_prod and f_prod and k_rule and f_rule:
                if k_prod == f_prod and k_rule == f_rule:
                    if not k.get("screen") or k.get("screen") == finding.get("screen"):
                        return True
        return False

    def ingest_packet(
        self,
        packet_dir: Path,
        run_dir: Optional[Path] = None,
        out_path: Optional[Path] = None,
    ) -> List[Finding]:
        """Ingest findings from a packet directory and serialize to findings.ai.json."""
        manifest_path = packet_dir / "manifest.json"
        orig_size = (1080, 1920)
        bundle_size = (900, 1600)

        if manifest_path.is_file():
            try:
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                images = manifest.get("images", [])
                for img in images:
                    if img.get("role") == "after" or not img.get("role"):
                        orig_size = tuple(img.get("orig_size", orig_size))
                        bundle_size = tuple(img.get("bundle_size", bundle_size))
                        break
            except Exception:
                pass

        raw_file = packet_dir / "findings.raw.txt"
        json_file = packet_dir / "findings.json"

        parsed_items: List[Dict[str, Any]] = []
        if raw_file.is_file():
            temp_json = packet_dir / "temp_findings.json"
            parsed_items = extract_findings_from_raw(raw_file, temp_json)
        elif json_file.is_file():
            try:
                raw_json = json.loads(json_file.read_text(encoding="utf-8"))
                if isinstance(raw_json, list):
                    parsed_items = raw_json
            except Exception:
                parsed_items = []

        known_list = self.load_known_findings()
        findings: List[Finding] = []

        for item in parsed_items:
            if not isinstance(item, dict):
                continue

            region = item.get("region")
            rescaled_region = self.rescale_coordinates(region, orig_size, bundle_size)

            item["region"] = rescaled_region
            item.setdefault("source", "ai-review")
            item.setdefault("product", "phototok")
            item.setdefault("config", "default")
            item.setdefault("journey", "explore")
            item.setdefault("checkpoint", "step_01")
            item.setdefault("rule", "ai-visual")
            item.setdefault("severity", "minor")
            item.setdefault("confidence", "high")
            item.setdefault("message", "AI finding")
            item.setdefault("evidence", ["before.png", "after.png"])
            item.setdefault("new_since_last_run", True)

            if "id" not in item or not re.match(r"^[0-9a-f]{12}$", str(item.get("id", ""))):
                item["id"] = Finding.make_id(
                    item["product"],
                    item["journey"],
                    item["checkpoint"],
                    item["rule"],
                    item["region"],
                )

            if self.is_known_finding(item, known_list):
                continue

            f = Finding.from_dict(item) if hasattr(Finding, "from_dict") else Finding(**item)
            findings.append(f)

        dest_file = out_path
        if dest_file is None and run_dir is not None:
            dest_file = run_dir / "findings.ai.json"

        if dest_file is not None:
            dest_file.parent.mkdir(parents=True, exist_ok=True)
            serialized = [f.__dict__ for f in findings]
            dest_file.write_text(json.dumps(serialized, indent=2) + "\n", encoding="utf-8")

        return findings


# =====================================================================
# Factory and Auto-Detection
# =====================================================================
def create_ai_adapter(provider_name: str, **kwargs: Any) -> BaseAIProvider:
    """Factory creating configured AIProvider instance or raising ValueError."""
    name = (provider_name or "").lower().strip()
    if name == "claude":
        return ClaudeCLIAdapter(**kwargs)
    elif name == "antigravity":
        return AntigravityAdapter(**kwargs)
    elif name == "packet":
        return PacketExportAdapter(**kwargs)
    elif name in ("none", "null", ""):
        return NoneAIProvider(**kwargs)
    else:
        raise ValueError(f"Unsupported AI provider: {provider_name}")


def auto_detect_ai_provider() -> str:
    """Detect available system AI tools; degrades safely to packet mode."""
    if shutil.which("claude"):
        return "claude"
    elif shutil.which("gemini"):
        return "antigravity"
    return "packet"
