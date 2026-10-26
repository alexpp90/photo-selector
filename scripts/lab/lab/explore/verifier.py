"""Verification of one exploratory step: oracles, findings and the step's status.

A step is `pass` only when it ran and every oracle that applies to it ran and found nothing
blocking. A step an oracle could not look at (no app log, no filesystem snapshot, an AI judge
that failed) is `error`, never `pass`; a step with a blocker or major finding is `fail`.

Finding ids are root signatures (`compute_root_signature`): the same defect on the same screen
reached by the same action has the same id on every step and device, so the report lists it once.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..fs_oracle import evaluate_fs_oracle
from ..layout_lint import lint, load_rules
from ..model import Finding
from ..paths import ensure_under
from .actions import Action, ScreenState
from .ai_adapters import AIJudgeError, AIProvider, JudgeContext
from .discovery import SYSTEM_PICKER_MODE
from .expectations import Expectation, feed_bundles, read_counter
from .reporter import compute_root_signature

logger = logging.getLogger(__name__)

# Severity levels conforming to finding.schema.json
SEVERITY_BLOCKER = "blocker"
SEVERITY_MAJOR = "major"
SEVERITY_MINOR = "minor"
SEVERITY_POLISH = "polish"

STATUS_PASS = "pass"
STATUS_FAIL = "fail"
STATUS_ERROR = "error"


@dataclass
class StepResult:
    """Outcome of one exploratory step."""

    step_index: int
    action: Action
    expectation: Expectation
    status: str  # pass | fail | error
    findings: List[Finding]
    before_state: ScreenState
    after_state: ScreenState
    packet_dir: Optional[Path] = None
    config_label: str = ""  # `<config>@<device>`, the `config` of this step's findings
    # Claims of the expectation no oracle could check (e.g. "index": no position on one side).
    # The step's status ignores them; the report shows them, so a pass is never read as checked.
    unchecked: List[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.status == STATUS_PASS

    def to_dict(self) -> Dict[str, Any]:
        return {
            "step_index": self.step_index,
            "config": self.config_label,
            "action": self.action.to_dict(),
            "expectation": self.expectation.to_dict(),
            "status": self.status,
            "findings": [f.to_dict() for f in self.findings],
            "packet_dir": str(self.packet_dir) if self.packet_dir else None,
            "unchecked": list(self.unchecked),
        }


def normalize_product_for_verifier(product: str) -> str:
    """The schema's product name: `phototok`, `android-desktop`, `macos` or `linux`."""
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
    """Checks the screen after an action against the expectation recorded before it."""

    CRASH_LOG_PATTERNS = [
        "FATAL EXCEPTION",
        "AndroidRuntime: FATAL",
        "ANR in ",
        "ActivityManager: ANR",
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
        run_dir: Optional[Path] = None,
        evidence_dir: Optional[Path] = None,
        ai_timeout_s: float = 120.0,
    ) -> None:
        self.product = normalize_product_for_verifier(product)
        self.config = config
        self.ai_provider = ai_provider
        self.run_dir = run_dir
        self.evidence_dir = evidence_dir
        self.ai_timeout_s = ai_timeout_s
        self.rules = rules if rules is not None else load_rules(self.product)

    # ------------------------------------------------------------------ helpers

    def _finding(
        self,
        *,
        source: str,
        rule: str,
        severity: str,
        message: str,
        screen_sig: str,
        action_sig: str,
        subject: Any,
        checkpoint: str,
        evidence: List[str],
        region: Optional[List[float]] = None,
        confidence: str = "high",
    ) -> Finding:
        return Finding(
            id=compute_root_signature(self.product, screen_sig, action_sig, rule, subject),
            source=source,
            product=self.product,
            config=self.config,
            journey="explore",
            checkpoint=checkpoint,
            rule=rule,
            severity=severity,
            confidence=confidence,
            region=region,
            message=message,
            evidence=list(evidence),
            new_since_last_run=True,
        )

    def _restamp(self, raw: List[Finding], screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """Give findings of a shared oracle this run's root signature and step.

        The oracle ran with a fixed checkpoint, so its own id depends only on rule and subject.
        """
        out: List[Finding] = []
        for f in raw:
            f.id = compute_root_signature(self.product, screen_sig, action_sig, f.rule, f.id)
            f.checkpoint = checkpoint
            f.evidence = list(evidence)
            out.append(f)
        return out

    # ------------------------------------------------------------------ pipeline

    def verify(
        self,
        step_index: int,
        before_state: ScreenState,
        action: Action,
        expectation: Expectation,
        after_state: ScreenState,
        app_log: Optional[List[str]],
        packet_dir: Optional[Path] = None,
        remaining_s: Optional[float] = None,
    ) -> StepResult:
        """Run every oracle for one step. `app_log` is None when the driver could not read it."""
        checkpoint = f"step_{step_index:02d}"
        screen_sig = before_state.screen_mode or "unknown"
        action_sig = action.signature
        evidence = self._evidence_files(step_index, before_state, after_state)
        unchecked: List[Finding] = []

        def unavailable(oracle: str, why: str) -> None:
            unchecked.append(
                self._finding(
                    source="flow", rule=f"{oracle}-unavailable", severity=SEVERITY_MINOR,
                    message=f"The {oracle} oracle could not run: {why}",
                    screen_sig=screen_sig, action_sig="", subject=why, checkpoint=checkpoint, evidence=evidence,
                )
            )

        findings: List[Finding] = []
        if app_log is None:
            unavailable("log", "the driver could not read the app's log")
        else:
            findings += self.verify_logs(app_log, screen_sig, action_sig, checkpoint, evidence)
        findings += self.verify_forbidden_nodes(expectation, after_state, screen_sig, action_sig, checkpoint, evidence)
        findings += self.verify_required_nodes(expectation, after_state, screen_sig, action_sig, checkpoint, evidence)
        if expectation.fs_expect and not (before_state.fs_snapshot and after_state.fs_snapshot):
            unavailable("fs", "the driver returned no filesystem snapshot")
        else:
            findings += self.verify_filesystem(expectation, before_state, after_state, screen_sig, action_sig, checkpoint, evidence)
        delta_findings, not_checked = self.verify_deltas(expectation, before_state, after_state, screen_sig, action_sig, checkpoint, evidence)
        findings += delta_findings
        findings += self.verify_layout(after_state, after_state.screen_mode or "unknown", checkpoint, evidence)

        ai_findings, ai_packet, ai_error = self.verify_ai(
            step_index, checkpoint, before_state, action, expectation, after_state, packet_dir, remaining_s,
            screen_sig, action_sig, evidence,
        )
        findings += ai_findings
        if ai_error is not None:
            unchecked.append(ai_error)
        findings += unchecked

        if findings:
            self._write_evidence(evidence, before_state, after_state)  # only steps with findings keep their files
        if any(f.severity in (SEVERITY_BLOCKER, SEVERITY_MAJOR) for f in findings):
            status = STATUS_FAIL
        elif unchecked:
            status = STATUS_ERROR
        else:
            status = STATUS_PASS
        return StepResult(
            step_index=step_index,
            action=action,
            expectation=expectation,
            status=status,
            findings=findings,
            before_state=before_state,
            after_state=after_state,
            packet_dir=ai_packet,
            config_label=self.config,
            unchecked=not_checked,
        )

    # ------------------------------------------------------------------ oracles

    def verify_logs(self, app_log: List[str], screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """A crash or ANR in the app's own log lines since the previous step."""
        for line in app_log:
            pattern = next((p for p in self.CRASH_LOG_PATTERNS if p in line), None)
            if pattern is None:
                continue
            rule = "anr" if "ANR" in pattern else "crash"
            return [
                self._finding(
                    source="log", rule=rule, severity=SEVERITY_BLOCKER,
                    message=f"Fatal error in the app's log: {line.strip()}",
                    screen_sig=screen_sig, action_sig=action_sig, subject=pattern, checkpoint=checkpoint, evidence=evidence,
                )
            ]
        return []

    def verify_forbidden_nodes(self, expectation: Expectation, after_state: ScreenState, screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """Crash dialogs and error banners must not appear."""
        forbidden = set(expectation.forbidden_ui_nodes)
        findings: List[Finding] = []
        for node in after_state.tree.nodes:
            if node.id not in forbidden:
                continue
            is_crash = any(w in f"{node.name or ''} {node.id}".lower() for w in ("crash", "anr", "fatal", "died", "exception"))
            findings.append(
                self._finding(
                    source="flow", rule="crash" if is_crash else "forbidden-ui-node",
                    severity=SEVERITY_BLOCKER if is_crash else SEVERITY_MAJOR,
                    message=f"Forbidden UI node '{node.id}' appeared on screen",
                    screen_sig=screen_sig, action_sig=action_sig, subject=node.id, checkpoint=checkpoint, evidence=evidence,
                    region=list(node.bounds),
                )
            )
        return findings

    def verify_required_nodes(self, expectation: Expectation, after_state: ScreenState, screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """Nodes the expectation requires must be visible and at least 1x1."""
        present = {n.id for n in after_state.tree.nodes if n.visible and n.w >= 1 and n.h >= 1}
        return [
            self._finding(
                source="flow", rule="missing-required-node", severity=SEVERITY_MAJOR,
                message=f"Required UI node '{req}' is missing or zero-sized",
                screen_sig=screen_sig, action_sig=action_sig, subject=req, checkpoint=checkpoint, evidence=evidence,
            )
            for req in expectation.required_ui_nodes
            if req not in present
        ]

    def verify_filesystem(self, expectation: Expectation, before_state: ScreenState, after_state: ScreenState, screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """The filesystem oracle of the lab, on the working folder's snapshots."""
        if not expectation.fs_expect:
            return []
        expect = dict(expectation.fs_expect)
        findings = self._verify_counts(expect.pop("counts", None), before_state, after_state, screen_sig, action_sig, checkpoint, evidence)
        if not expect:
            return findings
        raw = evaluate_fs_oracle(
            fs_data=after_state.fs_snapshot,
            expect=expect,
            initial_fs=before_state.fs_snapshot,
            product=self.product,
            config=self.config,
            journey="explore",
            checkpoint="explore",
        )
        return findings + self._restamp(raw, screen_sig, action_sig, checkpoint, evidence)

    def _verify_counts(self, counts: Optional[Dict[str, Any]], before_state: ScreenState, after_state: ScreenState, screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """A cull's effect on the folder by counts: feed photos exactly, Selection/ and Trash by growth."""
        if not counts:
            return []
        before_feed, after_feed = feed_bundles(before_state.fs_snapshot), feed_bundles(after_state.fs_snapshot)
        if before_feed is None or after_feed is None:
            return []

        def sizes(state: ScreenState) -> Dict[str, int]:
            fs = state.fs_snapshot or {}
            return {
                "selection": sum(1 for f in fs.get("files", []) if str(f.get("path", "")).startswith("Selection/")),
                "trash": len(fs.get("trash", []) or []),
            }

        b, a = sizes(before_state), sizes(after_state)
        deltas = {"feed": len(after_feed) - len(before_feed), "selection": a["selection"] - b["selection"], "trash": a["trash"] - b["trash"]}
        wrong = [
            f"{what} {'+' if got > 0 else ''}{got}, expected {'growth' if want == '+' else want}"
            for what, want in counts.items()
            for got in [deltas.get(what, 0)]
            if (want == "+" and got <= 0) or (want != "+" and got != want)
        ]
        if not wrong:
            return []
        return [
            self._finding(
                source="fs-oracle", rule="unexpected-folder-change", severity=SEVERITY_MAJOR,
                message="Folder changed unexpectedly: " + "; ".join(wrong),
                screen_sig=screen_sig, action_sig=action_sig, subject=sorted(counts), checkpoint=checkpoint, evidence=evidence,
            )
        ]

    def verify_deltas(
        self, expectation: Expectation, before_state: ScreenState, after_state: ScreenState, screen_sig: str, action_sig: str, checkpoint: str, evidence: List[str]
    ) -> Tuple[List[Finding], List[str]]:
        """Photo index (HUD counter) and feed size (counter, else the files) moved as expected.

        Returns the findings and the claims that could not be checked: a screen without the
        position or count on either side leaves the claim open, which is not a pass.
        """
        findings: List[Finding] = []
        unchecked: List[str] = []
        checks = (
            ("index", "unexpected-index-delta", "Photo index", expectation.expected_index_delta, _index(before_state), _index(after_state)),
            ("feed-count", "unexpected-feed-count-delta", "Feed photo count", expectation.expected_feed_count_delta, *_feed_counts(before_state, after_state)),
        )
        for claim, rule, what, expected, before, after in checks:
            if expected is None:
                continue
            if before is None or after is None:
                unchecked.append(claim)
                continue
            if after - before == expected:
                continue
            findings.append(
                self._finding(
                    source="flow", rule=rule, severity=SEVERITY_MAJOR,
                    message=f"{what} changed by {after - before}, expected {expected}",
                    screen_sig=screen_sig, action_sig=action_sig, subject="", checkpoint=checkpoint, evidence=evidence,
                )
            )
        return findings, unchecked

    def verify_layout(self, after_state: ScreenState, screen_sig: str, checkpoint: str, evidence: List[str]) -> List[Finding]:
        """Layout rules R1-R8 on the screen the action led to.

        A layout defect belongs to the screen, not to the action that reached it, so the action
        is not part of its signature; the lint's own subject (the nodes involved) is. The system
        folder picker is another app's window, so it is not linted.
        """
        if after_state.screen_mode == SYSTEM_PICKER_MODE:
            return []
        raw = lint(
            tree=after_state.tree,
            rules=self.rules,
            product=self.product,
            config=self.config.split("@", 1)[0],  # the rules' frame sizes are keyed by config, not config@device
            journey="explore",
            checkpoint="explore",
        )
        return self._restamp(raw, screen_sig, "", checkpoint, evidence)

    def verify_ai(
        self,
        step_index: int,
        checkpoint: str,
        before_state: ScreenState,
        action: Action,
        expectation: Expectation,
        after_state: ScreenState,
        packet_dir: Optional[Path],
        remaining_s: Optional[float],
        screen_sig: str,
        action_sig: str,
        evidence: List[str],
    ) -> "tuple[List[Finding], Optional[Path], Optional[Finding]]":
        """The AI judge's findings, its packet, and an `ai-judge-error` finding when it failed."""
        if self.ai_provider is None or self.ai_provider.name == "none":
            return [], None, None
        timeout = self.ai_timeout_s if remaining_s is None else min(self.ai_timeout_s, max(1.0, remaining_s))
        try:
            if self.run_dir is None or packet_dir is None:
                raise AIJudgeError("no run directory to write the review packet to")
            ctx = JudgeContext(
                product=self.product, config=self.config, checkpoint=checkpoint, step_index=step_index,
                run_dir=self.run_dir, packet_dir=packet_dir, timeout_s=timeout,
            )
            judgement = self.ai_provider.judge(before_state, action, expectation, after_state, ctx)
        except AIJudgeError as exc:
            logger.warning("AI judge failed at %s: %s", checkpoint, exc)
            error = self._finding(
                source="ai-explorer", rule="ai-judge-error", severity=SEVERITY_MINOR,
                message=f"The AI judge ({self.ai_provider.name}) failed, so this step was not judged: {exc}",
                screen_sig="", action_sig="", subject=self.ai_provider.name, checkpoint=checkpoint, evidence=evidence,
                confidence="high",
            )
            return [], None, error
        findings = self._restamp_ai(judgement.findings, screen_sig, action_sig, evidence)
        return findings, judgement.packet_dir, None

    def _restamp_ai(self, raw: List[Finding], screen_sig: str, action_sig: str, evidence: List[str]) -> List[Finding]:
        for f in raw:
            region = [round(v / 50.0) * 50 for v in f.region] if f.region else []
            f.id = compute_root_signature(self.product, screen_sig, action_sig, f.rule, region)
            f.evidence = list(evidence)
        return raw

    # ------------------------------------------------------------------ evidence

    def _evidence_files(self, step_index: int, before_state: ScreenState, after_state: ScreenState) -> List[str]:
        """Paths, relative to the run directory, of the files `_write_evidence` writes for this step."""
        if self.evidence_dir is None or self.run_dir is None:
            return []
        base = ensure_under(self.evidence_dir, self.run_dir).relative_to(self.run_dir.resolve())
        names: List[str] = []
        for role, state in (("before", before_state), ("after", after_state)):
            stem = f"step_{step_index:02d}_{role}"
            if state.screenshot_bytes:
                names.append(f"{stem}.png")
            names.append(f"{stem}_tree.json")
        return [str(base / n) for n in names]

    def _write_evidence(self, evidence: List[str], before_state: ScreenState, after_state: ScreenState) -> None:
        """Write the step's screenshots and trees (`evidence` lists them, run-relative)."""
        if not evidence or self.run_dir is None:
            return
        for rel in evidence:
            path = ensure_under(self.run_dir / rel, self.run_dir)
            path.parent.mkdir(parents=True, exist_ok=True)
            state = before_state if "_before" in path.name else after_state
            if path.suffix == ".png":
                path.write_bytes(state.screenshot_bytes)
            else:
                path.write_text(json.dumps(state.tree.to_dict(), indent=2), encoding="utf-8")


def _index(state: ScreenState) -> Optional[int]:
    counter = read_counter(state)
    return counter[0] if counter else None


def _counter_count(state: ScreenState) -> Optional[int]:
    counter = read_counter(state)
    return counter[1] if counter else None


def _file_count(state: ScreenState) -> Optional[int]:
    files = state.fs_snapshot.get("files") if state.fs_snapshot else None
    if files is None:
        return None
    return len([f for f in files if not str(f.get("path", "")).startswith("Selection/")])


def _feed_counts(before: ScreenState, after: ScreenState) -> Tuple[Optional[int], Optional[int]]:
    """Feed size before and after from one source: the counter when both screens show it, else the files.

    A counter hidden behind a dialog on one side must not be compared with a file count.
    """
    b, a = _counter_count(before), _counter_count(after)
    if b is not None and a is not None:
        return b, a
    return _file_count(before), _file_count(after)


def verify_step(
    step_index: int,
    action: Action,
    expectation: Expectation,
    before_state: ScreenState,
    after_state: ScreenState,
    app_log: Optional[List[str]],
    product: str = "phototok",
    config: str = "default",
    rules: Optional[Dict[str, Any]] = None,
    ai_provider: Optional[AIProvider] = None,
    run_dir: Optional[Path] = None,
    evidence_dir: Optional[Path] = None,
    packet_dir: Optional[Path] = None,
    remaining_s: Optional[float] = None,
    ai_timeout_s: float = 120.0,
) -> StepResult:
    """Verify a single exploratory step."""
    return InteractionVerifier(
        product=product,
        config=config,
        rules=rules,
        ai_provider=ai_provider,
        run_dir=run_dir,
        evidence_dir=evidence_dir,
        ai_timeout_s=ai_timeout_s,
    ).verify(
        step_index=step_index,
        before_state=before_state,
        action=action,
        expectation=expectation,
        after_state=after_state,
        app_log=app_log,
        packet_dir=packet_dir,
        remaining_s=remaining_s,
    )
