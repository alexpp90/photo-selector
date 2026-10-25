"""Verification engine, finding generation, and root signature deduplication.

Verifies post-interaction state against structured expectations and deterministic
oracles (crash/ANR logs, forbidden UI nodes, required UI nodes, filesystem oracle,
quantitative state deltas, layout linting R1-R9, and AI judging).
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..fs_oracle import evaluate_fs_oracle
from ..layout_lint import lint, load_rules
from ..model import Finding, Node, Tree
from .actions import Action, ScreenState
from .ai_adapters import AIProvider, PacketExportAdapter
from .expectations import Expectation

logger = logging.getLogger(__name__)

# Severity levels conforming to finding.schema.json
SEVERITY_BLOCKER = "blocker"
SEVERITY_MAJOR = "major"
SEVERITY_MINOR = "minor"
SEVERITY_POLISH = "polish"
SEVERITY_INFO = "polish"  # Map info to polish for schema compliance


@dataclass
class StepResult:
    """Outcome of verifying a single exploratory interaction step.

    Conforms to PROJECT.md § Interface Contracts (M2 <-> M3).
    """

    step_index: int
    action: Action
    expectation: Expectation
    passed: bool
    findings: List[Finding]
    before_state: ScreenState
    after_state: ScreenState
    packet_dir: Optional[Path] = None

    def to_dict(self) -> Dict[str, Any]:
        """Serialize StepResult to dictionary."""
        return {
            "step_index": self.step_index,
            "action": self.action.to_dict() if hasattr(self.action, "to_dict") else dict(getattr(self.action, "__dict__", {})),
            "expectation": self.expectation.to_dict() if hasattr(self.expectation, "to_dict") else dict(getattr(self.expectation, "__dict__", {})),
            "passed": self.passed,
            "findings": [f.__dict__ if hasattr(f, "__dict__") else dict(f) for f in self.findings],
            "packet_dir": str(self.packet_dir) if self.packet_dir else None,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> StepResult:
        """Deserialize StepResult from dictionary."""
        act_data = data.get("action", {})
        act = Action.from_dict(act_data) if isinstance(act_data, dict) and "action_type" in act_data else Action("tap")

        exp_data = data.get("expectation", {})
        exp = Expectation.from_dict(exp_data) if isinstance(exp_data, dict) and "action" in exp_data else Expectation(action=act, description=str(exp_data))

        findings: List[Finding] = []
        for f in data.get("findings", []):
            if isinstance(f, dict):
                findings.append(Finding.from_dict(f) if hasattr(Finding, "from_dict") else Finding(**f))
            elif isinstance(f, Finding):
                findings.append(f)

        packet_path = Path(data["packet_dir"]) if data.get("packet_dir") else None
        empty_tree = Tree("android", {"w": 1080, "h": 1920})
        dummy_state = ScreenState(empty_tree, b"", "", {})

        return cls(
            step_index=int(data.get("step_index", 0)),
            action=act,
            expectation=exp,
            passed=bool(data.get("passed", True)),
            findings=findings,
            before_state=dummy_state,
            after_state=dummy_state,
            packet_dir=packet_path,
        )


from .reporter import compute_root_signature


def normalize_product_for_verifier(product: str) -> str:
    """Normalize product name to canonical schema-compliant identifier.

    Maps:
      - 'jackpix', 'phototok' -> 'phototok'
      - 'frameduel', 'android-desktop' -> 'android-desktop'
      - 'lensjet', 'macos-desktop', 'macos' -> 'macos'
      - 'blitzlens', 'linux-desktop', 'linux' -> 'linux'
    Explicitly rejects legacy desktop.
    """
    p = (product or "").lower().strip()
    if p in ("desktop", "legacy-desktop", "legacy", "python-desktop"):
        raise ValueError(f"Exploratory testing excludes legacy desktop: {product}")
    if p in ("jackpix", "phototok"):
        return "phototok"
    if p in ("frameduel", "android-desktop"):
        return "android-desktop"
    if p in ("lensjet", "macos-desktop", "macos"):
        return "macos"
    if p in ("blitzlens", "linux-desktop", "linux"):
        return "linux"
    return p


class InteractionVerifier:
    """Verifies live device state after an action against pre-set expectations and oracles."""

    CRASH_LOG_PATTERNS = [
        "FATAL EXCEPTION",
        "AndroidRuntime: FATAL",
        "ANR in ",
        "ActivityManager: ANR",
        "NullPointerException",
        "SIGSEGV",
        "SIGABRT",
        "Traceback (most recent call last):",
        "Segmentation fault",
    ]

    def __init__(
        self,
        product: str,
        config: str = "default",
        rules: Optional[Dict[str, Any]] = None,
        ai_provider: Optional[AIProvider] = None,
        evidence_dir: Optional[Path] = None,
    ) -> None:
        self.product = normalize_product_for_verifier(product)
        self.config = config
        self.ai_provider = ai_provider
        self.evidence_dir = evidence_dir

        if rules is not None:
            self.rules = rules
        else:
            try:
                rule_product = "android-desktop" if self.product == "android-desktop" else ("phototok" if self.product == "phototok" else self.product)
                self.rules = load_rules(rule_product)
            except Exception as e:
                logger.debug("Failed to load layout rules for %s: %s", self.product, e)
                self.rules = {}

    def verify(
        self,
        step_index: int,
        before_state: ScreenState,
        action: Action,
        expectation: Expectation,
        after_state: ScreenState,
        packet_dir: Optional[Path] = None,
    ) -> StepResult:
        """Execute full verification pipeline for one interaction step."""
        checkpoint = f"step_{step_index:02d}"
        screen_sig = before_state.screen_mode or "viewer"
        action_sig = getattr(action, "signature", None) or f"{action.action_type}:{action.target_id or ''}"

        findings: List[Finding] = []
        evidence_files = self._save_step_evidence(step_index, before_state, after_state)

        # 1. Crash & ANR Log Scanning (highest severity)
        crash_findings = self.verify_logs(after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(crash_findings)

        # 2. Forbidden UI Nodes (crash sheets, error dialogs)
        forbidden_findings = self.verify_forbidden_nodes(expectation, after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(forbidden_findings)

        # 3. Required UI Nodes (elements that must remain on screen)
        required_findings = self.verify_required_nodes(expectation, after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(required_findings)

        # 4. Filesystem Oracle
        fs_findings = self.verify_filesystem(expectation, before_state, after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(fs_findings)

        # 5. Quantitative State Deltas (index and feed count)
        delta_findings = self.verify_deltas(expectation, before_state, after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(delta_findings)

        # 6. Layout Linting (R1-R9)
        layout_findings = self.verify_layout(after_state, screen_sig, action_sig, checkpoint, evidence_files)
        findings.extend(layout_findings)

        # 7. AI Provider Judgment & Fallback
        ai_findings, exported_packet = self.verify_ai(before_state, action, expectation, after_state, packet_dir)
        findings.extend(ai_findings)

        # Step pass/fail condition: Zero findings with severity blocker or major
        blocking_findings = [f for f in findings if f.severity in (SEVERITY_BLOCKER, SEVERITY_MAJOR)]
        passed = len(blocking_findings) == 0

        effective_packet_dir = exported_packet or packet_dir

        return StepResult(
            step_index=step_index,
            action=action,
            expectation=expectation,
            passed=passed,
            findings=findings,
            before_state=before_state,
            after_state=after_state,
            packet_dir=effective_packet_dir,
        )

    def verify_logs(
        self,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Scan logs for fatal crashes or ANR events."""
        findings: List[Finding] = []
        for line in after_state.logs:
            if any(pat in line for pat in self.CRASH_LOG_PATTERNS):
                rule = "anr" if "ANR" in line else "crash"
                sig = compute_root_signature(self.product, screen_sig, action_sig, rule, "")
                findings.append(
                    Finding(
                        id=sig,
                        source="log",
                        product=self.product,
                        config=self.config,
                        journey="explore",
                        checkpoint=checkpoint,
                        rule=rule,
                        severity=SEVERITY_BLOCKER,
                        confidence="high",
                        region=None,
                        message=f"Fatal error detected in logs: {line.strip()}",
                        evidence=evidence,
                        new_since_last_run=True,
                    )
                )
                break
        return findings

    def verify_forbidden_nodes(
        self,
        expectation: Expectation,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Verify that forbidden UI nodes (crash dialogs, error banners) did not appear."""
        findings: List[Finding] = []
        forbidden_ids = set(getattr(expectation, "forbidden_ui_nodes", []) or [])
        if not forbidden_ids or not hasattr(after_state.tree, "nodes"):
            return findings

        for node in after_state.tree.nodes:
            if node.id in forbidden_ids:
                node_text = (node.name or "") + " " + (node.id or "")
                is_crash = any(w in node_text.lower() for w in ("crash", "anr", "fatal", "died", "exception"))
                severity = SEVERITY_BLOCKER if is_crash else SEVERITY_MAJOR
                rule = "crash" if is_crash else "forbidden-ui-node"
                sig = compute_root_signature(self.product, screen_sig, action_sig, rule, node.id)
                findings.append(
                    Finding(
                        id=sig,
                        source="expectation",
                        product=self.product,
                        config=self.config,
                        journey="explore",
                        checkpoint=checkpoint,
                        rule=rule,
                        severity=severity,
                        confidence="high",
                        region=node.bounds if hasattr(node, "bounds") else None,
                        message=f"Forbidden UI node '{node.id}' appeared on screen",
                        evidence=evidence,
                        new_since_last_run=True,
                    )
                )
        return findings

    def verify_required_nodes(
        self,
        expectation: Expectation,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Verify that expected required UI nodes remain present and visible."""
        findings: List[Finding] = []
        required_ids = getattr(expectation, "required_ui_nodes", []) or []
        if not required_ids or not hasattr(after_state.tree, "nodes"):
            return findings

        present_nodes = {
            n.id: n
            for n in after_state.tree.nodes
            if getattr(n, "visible", True)
            and getattr(n, "w", 1) >= 1
            and getattr(n, "h", 1) >= 1
        }

        for req_id in required_ids:
            if req_id not in present_nodes:
                sig = compute_root_signature(self.product, screen_sig, action_sig, "missing-required-node", req_id)
                findings.append(
                    Finding(
                        id=sig,
                        source="expectation",
                        product=self.product,
                        config=self.config,
                        journey="explore",
                        checkpoint=checkpoint,
                        rule="missing-required-node",
                        severity=SEVERITY_MAJOR,
                        confidence="high",
                        region=None,
                        message=f"Required UI node '{req_id}' is missing or zero-sized",
                        evidence=evidence,
                        new_since_last_run=True,
                    )
                )
        return findings

    def verify_filesystem(
        self,
        expectation: Expectation,
        before_state: ScreenState,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Evaluate filesystem oracle rules."""
        fs_expect = getattr(expectation, "fs_expect", None)
        if not fs_expect:
            return []

        raw_findings = evaluate_fs_oracle(
            fs_data=after_state.fs_snapshot,
            expect=fs_expect,
            initial_fs=before_state.fs_snapshot,
            product=self.product,
            config=self.config,
            journey="explore",
            checkpoint=checkpoint,
            evidence=evidence,
        )

        findings: List[Finding] = []
        for rf in raw_findings:
            subj = rf.message.split(":")[-1].strip() if ":" in rf.message else ""
            sig = compute_root_signature(self.product, screen_sig, action_sig, rf.rule, subj)
            rf.id = sig
            rf.evidence = evidence
            findings.append(rf)
        return findings

    def verify_deltas(
        self,
        expectation: Expectation,
        before_state: ScreenState,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Verify quantitative state deltas (index delta and feed count delta)."""
        findings: List[Finding] = []

        # Expected index delta check
        exp_idx_delta = getattr(expectation, "expected_index_delta", None)
        if exp_idx_delta is not None:
            before_idx = self._extract_photo_index(before_state)
            after_idx = self._extract_photo_index(after_state)
            if before_idx is not None and after_idx is not None:
                actual_delta = after_idx - before_idx
                if actual_delta != exp_idx_delta:
                    sig = compute_root_signature(self.product, screen_sig, action_sig, "unexpected-index-delta", "")
                    findings.append(
                        Finding(
                            id=sig,
                            source="expectation",
                            product=self.product,
                            config=self.config,
                            journey="explore",
                            checkpoint=checkpoint,
                            rule="unexpected-index-delta",
                            severity=SEVERITY_MAJOR,
                            confidence="high",
                            region=None,
                            message=f"Photo index changed by {actual_delta}, expected {exp_idx_delta}",
                            evidence=evidence,
                            new_since_last_run=True,
                        )
                    )

        # Expected feed count delta check
        exp_feed_delta = getattr(expectation, "expected_feed_count_delta", None)
        if exp_feed_delta is not None:
            before_feed = self._extract_feed_count(before_state)
            after_feed = self._extract_feed_count(after_state)
            if before_feed is not None and after_feed is not None:
                actual_delta = after_feed - before_feed
                if actual_delta != exp_feed_delta:
                    sig = compute_root_signature(self.product, screen_sig, action_sig, "unexpected-feed-count-delta", "")
                    findings.append(
                        Finding(
                            id=sig,
                            source="expectation",
                            product=self.product,
                            config=self.config,
                            journey="explore",
                            checkpoint=checkpoint,
                            rule="unexpected-feed-count-delta",
                            severity=SEVERITY_MAJOR,
                            confidence="high",
                            region=None,
                            message=f"Feed photo count changed by {actual_delta}, expected {exp_feed_delta}",
                            evidence=evidence,
                            new_since_last_run=True,
                        )
                    )

        return findings

    def verify_layout(
        self,
        after_state: ScreenState,
        screen_sig: str,
        action_sig: str,
        checkpoint: str,
        evidence: List[str],
    ) -> List[Finding]:
        """Run layout linting rules R1-R9 on after_state tree."""
        if not self.rules or not hasattr(after_state.tree, "nodes"):
            return []

        try:
            raw_findings = lint(
                tree=after_state.tree,
                rules=self.rules,
                product=self.product,
                config=self.config,
                journey="explore",
                checkpoint=checkpoint,
                evidence=evidence,
            )
        except Exception as e:
            logger.debug("Layout lint error: %s", e)
            return []

        findings: List[Finding] = []
        for rf in raw_findings:
            subj = rf.message.split()[0] if rf.message else ""
            sig = compute_root_signature(self.product, screen_sig, action_sig, rf.rule, subj)
            rf.id = sig
            rf.evidence = evidence
            findings.append(rf)
        return findings

    def verify_ai(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Expectation,
        after_state: ScreenState,
        packet_dir: Optional[Path] = None,
    ) -> Tuple[List[Finding], Optional[Path]]:
        """Run AI provider evaluation or degrade gracefully to packet export."""
        if self.ai_provider is None:
            return [], packet_dir

        try:
            ai_findings = self.ai_provider.judge_outcome(before_state, action, expectation, after_state)
            exported = getattr(self.ai_provider, "last_packet_dir", None)
            return ai_findings, exported or packet_dir
        except Exception as exc:
            logger.warning("[WARNING] AI provider evaluation failed: %s; degrading to packet export", exc)
            fallback = PacketExportAdapter()
            fallback.export_packet(before_state, action, expectation, after_state)
            return [], fallback.last_packet_dir or packet_dir

    def _save_step_evidence(
        self,
        step_index: int,
        before_state: ScreenState,
        after_state: ScreenState,
    ) -> List[str]:
        """Serialize before/after screenshots and trees into evidence directory."""
        before_png = f"step_{step_index:02d}_before.png"
        after_png = f"step_{step_index:02d}_after.png"
        before_tree = f"step_{step_index:02d}_before_tree.json"
        after_tree = f"step_{step_index:02d}_after_tree.json"

        if not self.evidence_dir:
            return [before_png, after_png]

        try:
            self.evidence_dir.mkdir(parents=True, exist_ok=True)
            if before_state.screenshot_bytes:
                (self.evidence_dir / before_png).write_bytes(before_state.screenshot_bytes)
            if after_state.screenshot_bytes:
                (self.evidence_dir / after_png).write_bytes(after_state.screenshot_bytes)

            b_dict = before_state.tree.to_dict() if hasattr(before_state.tree, "to_dict") else {}
            a_dict = after_state.tree.to_dict() if hasattr(after_state.tree, "to_dict") else {}
            (self.evidence_dir / before_tree).write_text(json.dumps(b_dict, indent=2), encoding="utf-8")
            (self.evidence_dir / after_tree).write_text(json.dumps(a_dict, indent=2), encoding="utf-8")
            return [before_png, after_png, before_tree, after_tree]
        except Exception as e:
            logger.debug("Failed saving evidence files: %s", e)
            return [before_png, after_png]

    def _extract_photo_index(self, state: ScreenState) -> Optional[int]:
        """Extract photo index (0-based) from HUD text like '1 / 24'."""
        if not hasattr(state.tree, "nodes"):
            return None
        for node in state.tree.nodes:
            text = (getattr(node, "text", "") or "") + " " + (getattr(node, "name", "") or "")
            m = re.search(r"(\d+)\s*/\s*(\d+)", text)
            if m:
                return int(m.group(1)) - 1
        return None

    def _extract_feed_count(self, state: ScreenState) -> Optional[int]:
        """Extract feed photo count from HUD text like '1 / 24' or fs_snapshot."""
        if hasattr(state.tree, "nodes"):
            for node in state.tree.nodes:
                text = (getattr(node, "text", "") or "") + " " + (getattr(node, "name", "") or "")
                m = re.search(r"(\d+)\s*/\s*(\d+)", text)
                if m:
                    return int(m.group(2))
        if "files" in state.fs_snapshot:
            files = state.fs_snapshot.get("files", [])
            return len([f for f in files if not str(f.get("path", "")).startswith("Selection/")])
        return None


def verify_step(
    step_index: int,
    action: Action,
    expectation: Expectation,
    before_state: ScreenState,
    after_state: ScreenState,
    product: str = "phototok",
    config: str = "default",
    rules: Optional[Dict[str, Any]] = None,
    ai_provider: Optional[AIProvider] = None,
    evidence_dir: Optional[Path] = None,
    packet_dir: Optional[Path] = None,
) -> StepResult:
    """Convenience helper to verify a single exploratory step."""
    verifier = InteractionVerifier(
        product=product,
        config=config,
        rules=rules,
        ai_provider=ai_provider,
        evidence_dir=evidence_dir,
    )
    return verifier.verify(
        step_index=step_index,
        before_state=before_state,
        action=action,
        expectation=expectation,
        after_state=after_state,
        packet_dir=packet_dir,
    )
