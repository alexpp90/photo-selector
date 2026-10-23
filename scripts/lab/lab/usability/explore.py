"""Live AI Persona Exploration Loop with bounded step budget, cycle detection, and finding synthesis."""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..paths import repo_root
from .driver import BaseDriver, MockDriver, ScenarioDriver, StepFailed, describe_action, parse_nodes
from .metrics import screen_metrics

PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01"
    b"\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
)


@dataclass
class ExplorationCharter:
    """Exploration mission charter defining goal, boundaries and constraints."""

    id: str
    title: str
    goal: str
    product: str = "phototok"
    app_id: str = "com.phototok.lab"
    device: str = "phone"
    folder: str = ""
    step_budget: int = 25
    never: list[str] = field(default_factory=list)
    use_case: str = ""
    target_screen: str = ""
    raw_text: str = ""

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ExplorationCharter:
        budget = int(data.get("step_budget") or data.get("budget") or data.get("time_box") or 25)
        return cls(
            id=str(data.get("id") or "charter"),
            title=str(data.get("title") or data.get("id") or "Exploration Charter"),
            goal=str(data.get("goal") or ""),
            product=str(data.get("product") or "phototok"),
            app_id=str(data.get("app_id") or "com.phototok.lab"),
            device=str(data.get("device") or "phone"),
            folder=str(data.get("folder") or ""),
            step_budget=budget,
            never=list(data.get("never") or []),
            use_case=str(data.get("use_case") or ""),
            target_screen=str(data.get("target_screen") or ""),
            raw_text=str(data.get("raw_text") or ""),
        )

    @classmethod
    def from_markdown(cls, path_or_text: Path | str, charter_id: str | None = None) -> ExplorationCharter:
        if isinstance(path_or_text, Path) or (
            isinstance(path_or_text, str) and "\n" not in path_or_text and Path(path_or_text).is_file()
        ):
            p = Path(path_or_text)
            text = p.read_text(encoding="utf-8")
            cid = charter_id or p.stem
        else:
            text = str(path_or_text)
            cid = charter_id or "charter"

        title, goal, device, app_id, folder, step_budget, never, use_case, target_screen = (
            "",
            "",
            "phone",
            "",
            "",
            25,
            [],
            "",
            "",
        )
        for line in text.splitlines():
            line_str = line.strip()
            if line_str.startswith("# Charter:"):
                title = line_str.replace("# Charter:", "").strip()
                m_uc = re.search(r"\((UC-[^)]+)\)", title)
                if m_uc:
                    use_case = m_uc.group(1)
            elif line_str.startswith("Device:"):
                device = line_str.replace("Device:", "").strip()
                m_app = re.search(r"app [`']([^`']+)['`]", line_str)
                if m_app:
                    app_id = m_app.group(1)
                m_fld = re.search(r"folder [`']([^`']+)['`]", line_str)
                if m_fld:
                    folder = m_fld.group(1)
            elif line_str.startswith("Goal:"):
                goal = line_str.replace("Goal:", "").strip()
            elif line_str.startswith("Time box:"):
                m_box = re.search(r"(\d+)\s+actions", line_str)
                if m_box:
                    step_budget = int(m_box.group(1))
            elif line_str.startswith("Never:"):
                raw_never = line_str.replace("Never:", "").strip()
                never = []
                for item in raw_never.split(","):
                    targets = re.findall(r"`([^`]+)`", item)
                    cleaned = re.sub(r"\(.*?\)", "", item).strip()
                    cleaned = re.sub(r"^(?:or|and)\s+", "", cleaned, flags=re.IGNORECASE)
                    cleaned = re.sub(r"[`'\"]", "", cleaned).strip().rstrip(".")
                    if cleaned and cleaned not in never:
                        never.append(cleaned)
                    m_act = re.match(r"^(?:tap|click|press|select)\s+(.+)$", cleaned, flags=re.IGNORECASE)
                    if m_act:
                        act_tgt = m_act.group(1).strip()
                        if act_tgt and act_tgt not in never:
                            never.append(act_tgt)
                    is_boundary = bool(re.match(r"^(?:leave|stay\s+in|remain\s+in)\b", cleaned, flags=re.IGNORECASE))
                    if not is_boundary:
                        for t in targets:
                            t_clean = re.sub(r"[`'\"]", "", t).strip().rstrip(".")
                            if t_clean and t_clean not in never:
                                never.append(t_clean)
            elif line_str.startswith("Target:"):
                target_screen = line_str.replace("Target:", "").strip()

        product = "phototok"
        if "desktop" in app_id or "tablet" in device or "android-desktop" in title.lower():
            product = "android-desktop"
        elif "macos" in device or "Lensjet" in title:
            product = "macos"
        elif "linux" in device or "Blitzlens" in title:
            product = "linux"

        return cls(
            id=cid,
            title=title or cid,
            goal=goal,
            product=product,
            app_id=app_id or "com.phototok.lab",
            device=device,
            folder=folder,
            step_budget=step_budget,
            never=never,
            use_case=use_case,
            target_screen=target_screen,
            raw_text=text,
        )


def load_charter(
    path_or_id: str | Path | dict[str, Any] | ExplorationCharter, product: str = "phototok"
) -> ExplorationCharter:
    """Load an exploration charter from a charter object, dict, path, or charter ID."""
    if isinstance(path_or_id, ExplorationCharter):
        return path_or_id
    if isinstance(path_or_id, dict):
        return ExplorationCharter.from_dict(path_or_id)

    p = Path(path_or_id)
    if p.is_file():
        return ExplorationCharter.from_markdown(p)

    root = repo_root()
    for base in [
        root / "private" / "lab" / "explorer" / "charters",
        root / "scripts" / "lab" / "explorer" / "charters",
    ]:
        cand = base / f"{path_or_id}.md"
        if cand.is_file():
            return ExplorationCharter.from_markdown(cand)
        cand2 = base / str(path_or_id)
        if cand2.is_file():
            return ExplorationCharter.from_markdown(cand2)

    raw = str(path_or_id)
    if "\n" in raw or raw.startswith("#"):
        return ExplorationCharter.from_markdown(raw)

    return ExplorationCharter(
        id=str(path_or_id),
        title=str(path_or_id),
        goal=f"Explore {product} autonomously",
        product=product,
    )


@dataclass
class InteractiveElement:
    id: str
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    center: tuple[int, int]
    clickable: bool
    role: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "text": self.text,
            "desc": self.desc,
            "bounds": list(self.bounds),
            "center": list(self.center),
            "clickable": self.clickable,
            "role": self.role,
        }


@dataclass
class ScreenObservation:
    step: int
    screen_name: str
    image_path: Path
    tree_dump: Path
    elements: list[InteractiveElement]
    metrics: dict[str, Any]
    signature: str


def compute_screen_signature(elements: list[InteractiveElement], screen_name: str = "") -> str:
    """Compute structural hash of interactive UI elements ignoring system bars and transient timing."""
    canonical_items: list[tuple[str, str, str, bool]] = []
    for el in elements:
        ign_id = el.id.lower()
        ign_desc = el.desc.lower()
        ign_text = el.text.lower()
        if any(ign in ign_id or ign in ign_desc or ign in ign_text for ign in ("battery", "clock", "status_bar", "navigation_bar")):
            continue
        canonical_items.append((
            el.id,
            el.text.strip()[:40],
            el.desc.strip()[:40],
            el.clickable,
        ))
    canonical_items.sort(key=lambda x: (x[0], x[1], x[2]))
    payload = json.dumps({"screen": screen_name, "items": canonical_items}, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:12]


@dataclass
class CycleEvent:
    cycle_type: str  # "stagnation", "oscillation", "loop"
    period: int  # 1, 2, 3
    screens: list[str]  # Screen names / signatures
    steps: list[int]  # Step indices
    message: str


class CycleDetector:
    """Detects UI stagnation, oscillation and cyclic dead ends during exploration."""

    def __init__(self) -> None:
        self.signatures: list[str] = []
        self.screen_names: list[str] = []
        self.step_indices: list[int] = []

    def record(self, step_idx: int, signature: str, screen_name: str) -> CycleEvent | None:
        self.signatures.append(signature)
        self.screen_names.append(screen_name)
        self.step_indices.append(step_idx)

        n = len(self.signatures)

        # 1. Stagnation (Period 1): 3 consecutive steps on identical signature
        if n >= 3 and self.signatures[-1] == self.signatures[-2] == self.signatures[-3]:
            return CycleEvent(
                cycle_type="stagnation",
                period=1,
                screens=[self.screen_names[-1]],
                steps=self.step_indices[-3:],
                message=f"UI stagnation: agent remained on '{self.screen_names[-1]}' for 3 consecutive steps without state change",
            )

        # 2. Oscillation (Period 2): A -> B -> A -> B
        if n >= 4:
            s1, s2, s3, s4 = self.signatures[-4], self.signatures[-3], self.signatures[-2], self.signatures[-1]
            if s1 == s3 and s2 == s4 and s1 != s2:
                name_a, name_b = self.screen_names[-4], self.screen_names[-3]
                return CycleEvent(
                    cycle_type="oscillation",
                    period=2,
                    screens=[name_a, name_b],
                    steps=self.step_indices[-4:],
                    message=f"UI oscillation: agent ping-ponged between '{name_a}' and '{name_b}' (2 full cycles)",
                )

        # 3. Cyclic Loop (Period 3): A -> B -> C -> A -> B -> C
        if n >= 6:
            block1 = self.signatures[-6:-3]
            block2 = self.signatures[-3:]
            if block1 == block2 and len(set(block1)) == 3:
                names = self.screen_names[-3:]
                return CycleEvent(
                    cycle_type="loop",
                    period=3,
                    screens=names,
                    steps=self.step_indices[-6:],
                    message=f"UI cyclic loop: agent repeated 3-step sequence {' -> '.join(names)}",
                )

        return None


@dataclass
class ExplorationDecision:
    action: dict[str, Any]
    thought: str = ""
    confidence: str = "high"
    hesitation: bool = False
    finding: dict[str, Any] | None = None


class ExplorationAgent:
    """Base interface for AI exploration personas."""

    def decide_action(
        self,
        observation: ScreenObservation,
        charter: ExplorationCharter,
        history: list[dict[str, Any]],
    ) -> ExplorationDecision:
        raise NotImplementedError


class MockExplorationAgent(ExplorationAgent):
    """Deterministic agent for offline unit testing without LLM dependencies."""

    def __init__(
        self,
        actions: list[Any] | None = None,
        rules: dict[str, Any] | None = None,
        hesitate_on_steps: list[int] | None = None,
        scripted_actions: list[Any] | None = None,
    ) -> None:
        raw_actions = scripted_actions if scripted_actions is not None else (actions or [])
        self.actions = list(raw_actions)
        self.rules = dict(rules or {})
        self.hesitate_on_steps = set(hesitate_on_steps or [])
        self.observations_received: list[ScreenObservation] = []
        self.call_count = 0

    def decide_action(
        self,
        observation: ScreenObservation,
        charter: ExplorationCharter,
        history: list[dict[str, Any]],
    ) -> ExplorationDecision:
        self.call_count += 1
        self.observations_received.append(observation)
        hesitation = observation.step in self.hesitate_on_steps

        # 1. Scripted action sequence
        if self.actions:
            item = self.actions.pop(0)
            if isinstance(item, ExplorationDecision):
                return item
            if isinstance(item, dict):
                act_copy = dict(item)
                # Check for explicit finish
                if act_copy.get("action") == "finish" or "finish" in act_copy:
                    fin_data = act_copy.get("finish") if isinstance(act_copy.get("finish"), dict) else {}
                    reason = act_copy.get("reason") or fin_data.get("reason", "goal reached")
                    return ExplorationDecision(
                        action={"finish": {"goal_achieved": True, "reason": reason}},
                        thought=f"Goal reached: {reason}",
                        confidence="high",
                        hesitation=hesitation,
                    )
                # Check for explicit stuck
                if act_copy.get("action") == "stuck":
                    reason = act_copy.get("reason", "Agent is stuck")
                    return ExplorationDecision(
                        action={"stuck": {"reason": reason}},
                        thought=f"Stuck: {reason}",
                        confidence="low",
                        hesitation=True,
                    )
                return ExplorationDecision(
                    action=act_copy,
                    thought=f"Executing scripted action {self.call_count}",
                    confidence="high",
                    hesitation=hesitation,
                )

        # 2. Rule-based lookup by screen name or element ID
        if observation.screen_name in self.rules:
            act = self.rules[observation.screen_name]
            return ExplorationDecision(
                action=act if isinstance(act, dict) else {"tap": act},
                thought=f"Matched screen rule for {observation.screen_name}",
                confidence="high",
                hesitation=hesitation,
            )

        for el in observation.elements:
            if el.id in self.rules:
                act = self.rules[el.id]
                return ExplorationDecision(
                    action=act if isinstance(act, dict) else {"tap": act},
                    thought=f"Matched element rule for {el.id}",
                    confidence="high",
                    hesitation=hesitation,
                )

        # 3. Target screen check
        if charter.target_screen and observation.screen_name == charter.target_screen:
            return ExplorationDecision(
                action={"finish": {"goal_achieved": True, "reason": f"Reached target screen {charter.target_screen}"}},
                thought=f"Reached target screen {charter.target_screen}",
                confidence="high",
                hesitation=hesitation,
            )

        # 4. Default fallback finish
        return ExplorationDecision(
            action={"finish": {"goal_achieved": False, "reason": "No rule or scripted action available"}},
            thought="Giving up, no action matched",
            confidence="low",
            hesitation=True,
        )


class AgentError(RuntimeError):
    """The exploration agent produced no decision (CLI missing, timed out, no JSON)."""


def resolve_target(target: Any, elements: list[InteractiveElement]) -> dict[str, Any]:
    """The agent names a control by id or visible text; the driver needs a node selector."""
    if isinstance(target, dict):
        return target
    name = str(target).strip()
    for el in elements:
        if name and name == el.id:
            return {"id": name}
    for el in elements:
        if name and name == el.text:
            return {"text": name}
    for el in elements:
        if name and name == el.desc:
            return {"desc": name}
    return {"text": name, "contains": True}


class CliExplorationAgent(ExplorationAgent):
    """Sandboxed live CLI agent running Claude or Gemini CLI outside checkout."""

    def __init__(self, cli: str = "claude", model: str | None = None, sandbox_dir: Path | None = None,
                 timeout_s: float = 300.0) -> None:
        self.cli = cli
        self.timeout_s = timeout_s
        # Version-free: an alias for Claude, the CLI's own default for Gemini (no -m).
        self.model = model or ("sonnet" if cli == "claude" else None)
        base_tmp = Path(os.environ.get("PST_LAB_TMP", "/tmp/pstlab"))
        base_tmp.mkdir(parents=True, exist_ok=True)
        self.sandbox_dir = sandbox_dir or Path(tempfile.mkdtemp(prefix=f"ai_explore_{cli}_", dir=base_tmp))
        self.sandbox_dir.mkdir(parents=True, exist_ok=True)

    def decide_action(
        self,
        observation: ScreenObservation,
        charter: ExplorationCharter,
        history: list[dict[str, Any]],
    ) -> ExplorationDecision:
        step = observation.step
        prompt_file = self.sandbox_dir / f"step_{step:02d}_prompt.md"

        el_lines = [
            f"- [{idx}] `{el.id}`: text='{el.text}' desc='{el.desc}' clickable={el.clickable}"
            for idx, el in enumerate(observation.elements[:30])
        ]
        hist_lines = [
            f"- Step {h['step']}: {h.get('action_description', '?')} -> Screen {h.get('screen', '?')}"
            for h in history[-5:]
        ]

        never_str = ", ".join(charter.never) if charter.never else "None"
        prompt_content = f"""# Persona Charter: {charter.title}
**Goal:** {charter.goal}
**Prohibited (Never):** {never_str}

## Current Screen: {observation.screen_name} (Step {step}/{charter.step_budget})
Screenshot: `{observation.image_path.name}`
Screen Metrics: Words: {observation.metrics.get('words', 0)}, Small Targets: {observation.metrics.get('small_targets', 0)}, Unlabeled Controls: {observation.metrics.get('unlabeled_controls', 0)}

### Interactive Elements
{chr(10).join(el_lines) or '(No interactive elements found)'}

### Recent History
{chr(10).join(hist_lines) or '(Session just started)'}

## Instructions
Decide the single next action to take to make progress toward your goal.
Respond ONLY with a JSON object matching this schema:
```json
{{
  "thought": "Your reasoning in persona voice",
  "hesitation": false,
  "confidence": "high",
  "action": {{
    "type": "tap",
    "target": "element_id_or_text"
  }}
}}
```
The "action" is exactly one of:
- {{"type": "tap", "target": "<element id or its visible text>"}} (also "long_press")
- {{"type": "swipe", "direction": "up" | "down" | "left" | "right"}}
- {{"type": "key", "key": "Right" | "Left" | "Space" | "Delete" | "Enter" | "Escape"}}
- {{"type": "back"}}
- {{"type": "wait_ms", "ms": 500}}
- {{"type": "finish", "goal_achieved": true | false}}
"""
        prompt_file.write_text(prompt_content, encoding="utf-8")
        if observation.image_path.is_file():
            shutil.copy2(observation.image_path, self.sandbox_dir / observation.image_path.name)

        if self.cli == "claude":
            cmd = ["claude", "-p", prompt_content, "--model", self.model, "--allowedTools", "Read"]
        else:
            cmd = ["gemini", "-p", f"Inputs: @{prompt_file.name} @{observation.image_path.name}\n{prompt_content}"]
            if self.model:
                cmd += ["-m", self.model]

        try:
            res = subprocess.run(cmd, cwd=self.sandbox_dir, capture_output=True, text=True, check=True,
                                 timeout=self.timeout_s)
            raw = res.stdout
            (self.sandbox_dir / f"step_{step:02d}_reply.txt").write_text(raw, encoding="utf-8")
            s_idx = raw.find("{")
            e_idx = raw.rfind("}")
            if s_idx == -1 or e_idx == -1:
                raise AgentError(f"{self.cli} answered without a JSON object: {raw.strip()[:200]!r}")
            data = json.loads(raw[s_idx:e_idx + 1])
        except (OSError, subprocess.SubprocessError, ValueError) as exc:
            # The persona never decided anything: that is a broken run, not a persona who gave up.
            raise AgentError(f"{self.cli} exploration agent failed: {exc}") from exc

        act = data.get("action", {})
        norm_act: dict[str, Any] = {}
        act_type = act.get("type", "")
        if act_type in ("tap", "long_press"):
            norm_act[act_type] = resolve_target(act.get("target", ""), observation.elements)
        elif act_type == "double_tap":
            norm_act["double_tap"] = True
        elif act_type == "swipe":
            # Never a default direction: "swipe right to keep" became ten swipes up (2026-10-24).
            direction = str(act.get("direction") or act.get("target") or act.get("value") or "").lower()
            if direction not in ("up", "down", "left", "right"):
                raise AgentError(f"{self.cli} chose a swipe without a direction: {act!r}")
            norm_act["swipe"] = direction
        elif act_type == "key":
            key = act.get("key") or act.get("code") or act.get("target")
            if not key:
                raise AgentError(f"{self.cli} chose a key without naming it: {act!r}")
            norm_act["key"] = key
        elif act_type == "back":
            norm_act["back"] = True
        elif act_type == "wait_ms":
            norm_act["wait_ms"] = int(act.get("ms", 500))
        elif act_type == "finish" or "finish" in act:
            norm_act["finish"] = act.get("finish", act)
        else:
            norm_act = act

        return ExplorationDecision(
            action=norm_act,
            thought=data.get("thought", ""),
            confidence=data.get("confidence", "high"),
            hesitation=bool(data.get("hesitation")),
            finding=data.get("finding"),
        )


def ClaudeExplorationAgent(model: str | None = None) -> CliExplorationAgent:
    return CliExplorationAgent(cli="claude", model=model)


def GeminiExplorationAgent(model: str | None = None) -> CliExplorationAgent:
    return CliExplorationAgent(cli="gemini", model=model)


def _content_name(nodes: list[Any]) -> str:
    """A short stable name for an Android screen: the first resource ids on it."""
    ids = sorted({n.res_id for n in nodes if n.res_id})[:3]
    return "+".join(ids) or "screen"


def capture_current_observation(
    driver: BaseDriver,
    step_idx: int,
    out_dir: Path,
    current_screen: str | None = None,
) -> ScreenObservation:
    """Capture screenshot, tree hierarchy and metrics for the current screen."""
    stem = f"step_{step_idx:02d}"
    screens_dir = out_dir / "screens"
    screens_dir.mkdir(parents=True, exist_ok=True)

    img_path = screens_dir / f"{stem}.png"
    tree_path = screens_dir / f"{stem}.xml"

    elements: list[InteractiveElement] = []
    metrics: dict[str, Any] = {}  # nothing measured is nothing reported, never a made-up default
    screen_name = current_screen or "screen"

    if isinstance(driver, ScenarioDriver):
        xml_text = driver._dump()  # one dump: the elements, the saved tree and the metrics agree
        nodes = parse_nodes(xml_text)
        if nodes:
            driver.size = (nodes[0].bounds[2], nodes[0].bounds[3])
            # Named by content, not by step: the name is part of the signature the cycle detector
            # compares, and a per-step name hid ten swipes on the same last photo (2026-10-24).
            screen_name = current_screen or _content_name(nodes)
            elements = [
                InteractiveElement(
                    id=n.res_id or f"node_{idx}",
                    text=n.text or "",
                    desc=n.desc or "",
                    bounds=n.bounds,
                    center=n.center,
                    clickable=n.clickable,
                )
                for idx, n in enumerate(nodes)
                if n.clickable or n.text or n.desc
            ]
        driver.adb.screencap(img_path)
        tree_path.write_text(xml_text, encoding="utf-8")
        metrics = screen_metrics(xml_text, density_dpi=driver.adb.density())
    elif isinstance(driver, MockDriver):
        img_path.write_bytes(PNG_1PX)
        if current_screen:
            screen_name = current_screen
        elif driver.screens:
            screen_name = driver.screens[-1].get("name", f"mock_screen_{step_idx}")
        else:
            screen_name = f"mock_screen_{step_idx}"

        elements = [
            InteractiveElement(
                id=f"{screen_name}_el_{idx}",
                text=f"{screen_name} element {idx}",
                desc="",
                bounds=(10, 10 + idx * 50, 200, 50 + idx * 50),
                center=(105, 30 + idx * 50),
                clickable=True,
            )
            for idx in range(3)
        ]
        tree_path.write_text(f"<mock screen='{screen_name}'/>", encoding="utf-8")
    else:
        raise ValueError(
            f"exploration needs a driver that observes the screen between steps (Android or mock), not {type(driver).__name__}"
        )

    sig = compute_screen_signature(elements, screen_name=screen_name)
    return ScreenObservation(
        step=step_idx,
        screen_name=screen_name,
        image_path=img_path,
        tree_dump=tree_path,
        elements=elements,
        metrics=metrics,
        signature=sig,
    )


def run_exploration(
    driver: BaseDriver,
    charter: dict[str, Any] | Path | str | ExplorationCharter,
    step_budget: int = 25,
    agent: ExplorationAgent | None = None,
    out_dir: Path | None = None,
) -> dict[str, Any]:
    """Execute autonomous live persona exploration loop with bounded steps and cycle detection."""
    c_obj = load_charter(charter, product=getattr(driver, "product", "phototok"))
    budget = min(step_budget, c_obj.step_budget)
    effective_agent = agent or MockExplorationAgent()

    run_dir = out_dir or (driver.out_dir / "exploration" / c_obj.id)
    run_dir.mkdir(parents=True, exist_ok=True)

    cycle_detector = CycleDetector()
    trajectory: list[dict[str, Any]] = []
    findings: list[dict[str, Any]] = []
    screens_visited: set[str] = set()

    status = "goal_reached"
    error = None
    goal_achieved = False
    start_time = time.monotonic()
    current_screen: str | None = None
    dead_ends_count = 0

    for step_idx in range(1, budget + 1):
        # A. PERCEPTION
        obs = capture_current_observation(driver, step_idx, run_dir, current_screen=current_screen)
        screens_visited.add(obs.screen_name)

        # B. CYCLE DETECTION
        cycle_event = cycle_detector.record(step_idx, obs.signature, obs.screen_name)
        if cycle_event:
            status = "dead_end"
            dead_ends_count += 1
            findings.append({
                "id": hashlib.sha256(f"{c_obj.id}_cycle_{step_idx}".encode()).hexdigest()[:12],
                "source": "ai-explorer",
                "product": c_obj.product,
                "config": c_obj.device,
                "journey": c_obj.id,
                "checkpoint": obs.screen_name,
                "rule": "discoverability-dead-end",
                "severity": "major",
                "confidence": "high",
                "region": None,
                "message": cycle_event.message,
                "evidence": [f"Cycle of period {cycle_event.period} detected: {cycle_event.message}"],
                "new_since_last_run": True,
                "kind": "discoverability",
                "detail": cycle_event.message,
                "screen": obs.screen_name,
            })
            break

        # Check objective text load/target defects
        if obs.metrics.get("small_targets", 0) > 0:
            findings.append({
                "id": hashlib.sha256(f"{c_obj.id}_small_target_{step_idx}".encode()).hexdigest()[:12],
                "source": "ai-explorer",
                "product": c_obj.product,
                "config": c_obj.device,
                "journey": c_obj.id,
                "checkpoint": obs.screen_name,
                "rule": "touch-target-size",
                "severity": "minor",
                "confidence": "medium",
                "region": None,
                "message": f"Screen '{obs.screen_name}' contains {obs.metrics['small_targets']} undersized touch targets",
                "evidence": [str(obs.image_path.name)],
                "new_since_last_run": True,
                "kind": "touch-target-size",
                "detail": f"Screen '{obs.screen_name}' contains {obs.metrics['small_targets']} undersized touch targets",
                "screen": obs.screen_name,
            })

        # C. DECISION
        try:
            decision = effective_agent.decide_action(obs, c_obj, trajectory)
        except AgentError as exc:
            status, error = "error", str(exc)
            break

        # Check for hesitation
        if decision.hesitation:
            findings.append({
                "id": hashlib.sha256(f"{c_obj.id}_hesitation_{step_idx}".encode()).hexdigest()[:12],
                "source": "ai-explorer",
                "product": c_obj.product,
                "config": c_obj.device,
                "journey": c_obj.id,
                "checkpoint": obs.screen_name,
                "rule": "affordance-hesitation",
                "severity": "minor",
                "confidence": "high",
                "region": None,
                "message": f"Persona hesitated on screen '{obs.screen_name}': {decision.thought}",
                "evidence": [f"Decision thought: {decision.thought}"],
                "new_since_last_run": True,
                "kind": "hesitation",
                "detail": f"Persona hesitated on screen '{obs.screen_name}': {decision.thought}",
                "screen": obs.screen_name,
            })

        # Check for stuck
        if "stuck" in decision.action or decision.action.get("action") == "stuck":
            stuck_data = decision.action.get("stuck") if isinstance(decision.action.get("stuck"), dict) else {}
            reason = decision.action.get("reason") or stuck_data.get("reason", "Agent hit dead end")
            status = "dead_end"
            dead_ends_count += 1
            findings.append({
                "id": hashlib.sha256(f"{c_obj.id}_stuck_{step_idx}".encode()).hexdigest()[:12],
                "source": "ai-explorer",
                "product": c_obj.product,
                "config": c_obj.device,
                "journey": c_obj.id,
                "checkpoint": obs.screen_name,
                "rule": "discoverability-dead-end",
                "severity": "major",
                "confidence": "high",
                "region": None,
                "message": f"Agent stuck on screen '{obs.screen_name}': {reason}",
                "evidence": [f"Decision: {reason}"],
                "new_since_last_run": True,
                "kind": "discoverability",
                "detail": f"Agent stuck: {reason}",
                "screen": obs.screen_name,
            })
            trajectory.append({
                "step": step_idx,
                "screen": obs.screen_name,
                "screen_before": obs.screen_name,
                "signature": obs.signature,
                "thought": decision.thought,
                "action": decision.action,
                "action_description": "Agent declared dead end",
                "metrics": obs.metrics,
                "timestamp": time.time(),
                "status": "stuck",
            })
            break

        # Check for finish
        if "finish" in decision.action or decision.action.get("action") == "finish":
            fin = decision.action.get("finish") if isinstance(decision.action.get("finish"), dict) else decision.action
            goal_achieved = bool(fin.get("goal_achieved", True))
            status = "goal_reached" if goal_achieved else "gave_up"
            trajectory.append({
                "step": step_idx,
                "screen": obs.screen_name,
                "screen_before": obs.screen_name,
                "signature": obs.signature,
                "thought": decision.thought,
                "action": decision.action,
                "action_description": "Finished exploration",
                "metrics": obs.metrics,
                "timestamp": time.time(),
                "status": "ok",
            })
            break

        # D. CHARTER SAFETY CHECK
        act_str = str(decision.action)
        action_targets: list[str] = []
        for k in ("target", "tap", "long_press", "double_tap", "scroll_to", "id"):
            v = decision.action.get(k)
            if isinstance(v, str):
                action_targets.append(v.lower())
            elif isinstance(v, dict):
                for sub_k in ("id", "label", "text", "desc"):
                    sub_v = v.get(sub_k)
                    if isinstance(sub_v, str):
                        action_targets.append(sub_v.lower())

        prohibited_hit = False
        matched_rule = None
        act_lower = act_str.lower()
        for nev in c_obj.never:
            nev_clean = nev.strip().lower()
            if not nev_clean:
                continue
            # 1. Exact phrase match in full action string (e.g. {'action': 'open other apps'})
            if nev_clean in act_lower:
                prohibited_hit = True
                matched_rule = nev
                break
            # 2. Target matches prohibited control ID
            for tgt in action_targets:
                if tgt == nev_clean or (len(nev_clean) > 3 and nev_clean in tgt):
                    prohibited_hit = True
                    matched_rule = nev
                    break
                nev_norm = re.sub(r"[\s_-]+", "", nev_clean)
                tgt_norm = re.sub(r"[\s_-]+", "", tgt)
                if len(nev_norm) > 5 and nev_norm in tgt_norm:
                    prohibited_hit = True
                    matched_rule = nev
                    break
            if prohibited_hit:
                break

        if prohibited_hit:
            try:
                act_desc = describe_action(decision.action) or str(decision.action)
            except Exception:
                act_desc = str(decision.action)

            findings.append({
                "id": hashlib.sha256(f"{c_obj.id}_prohibited_{step_idx}".encode()).hexdigest()[:12],
                "source": "ai-explorer",
                "product": c_obj.product,
                "config": c_obj.device,
                "journey": c_obj.id,
                "checkpoint": obs.screen_name,
                "rule": "charter-boundary-violation",
                "severity": "major",
                "confidence": "high",
                "region": None,
                "message": f"Agent attempted action violating charter constraint: {act_str}",
                "evidence": [f"Prohibited rule from charter: {matched_rule or c_obj.never}"],
                "new_since_last_run": True,
                "kind": "charter-boundary-violation",
                "detail": f"Agent attempted action violating charter constraint: {act_str}",
                "screen": obs.screen_name,
            })
            trajectory.append({
                "step": step_idx,
                "screen": obs.screen_name,
                "screen_before": obs.screen_name,
                "signature": obs.signature,
                "thought": decision.thought,
                "action": decision.action,
                "action_description": act_desc,
                "metrics": obs.metrics,
                "timestamp": time.time(),
                "status": "prohibited",
                "error": f"Charter prohibited action: {matched_rule}",
            })
            continue

        # E. EXECUTE ON DRIVER
        next_screen = (
            decision.action.get("expected_screen")
            or decision.action.get("screen_name")
            or decision.action.get("screen")
        )
        if next_screen:
            current_screen = str(next_screen)

        action_status = "ok"
        step_err = None
        # The label the agent expects next names the next observation; it is not part of the
        # action (a `screen` key makes a driver capture instead of acting).
        driver_step = {k: v for k, v in decision.action.items() if k not in ("expected_screen", "screen_name", "screen")}

        try:
            driver._step(driver_step)
        except StepFailed as exc:
            action_status = "failed"
            step_err = str(exc)
        except Exception as exc:
            action_status = "broken"
            step_err = str(exc)
            status = "broken"
            error = str(exc)

        try:
            action_desc = describe_action(decision.action) or str(decision.action)
        except Exception:
            action_desc = str(decision.action)

        trajectory.append({
            "step": step_idx,
            "screen": obs.screen_name,
            "screen_before": obs.screen_name,
            "signature": obs.signature,
            "thought": decision.thought,
            "action": decision.action,
            "action_description": action_desc,
            "metrics": obs.metrics,
            "timestamp": time.time(),
            "status": action_status,
            "error": step_err,
        })

        if status == "broken":
            break
    else:
        # Loop exhausted all steps
        if status not in ("dead_end", "broken"):
            status = "budget_exhausted"
            goal_achieved = False

    duration = time.monotonic() - start_time

    # F. EXPORT ARTIFACTS
    result = {
        "charter_id": c_obj.id,
        "charter": {
            "id": c_obj.id,
            "title": c_obj.title,
            "goal": c_obj.goal,
            "product": c_obj.product,
            "step_budget": budget,
            "never": c_obj.never,
        },
        "product": c_obj.product,
        "status": status,
        "error": error,
        "goal_achieved": goal_achieved,
        "budget": budget,
        "step_budget": budget,
        "steps_taken": len(trajectory),
        "duration_seconds": round(duration, 2),
        "unique_screens": sorted(screens_visited),
        "screens_visited_count": len(screens_visited),
        "findings_count": len(findings),
        "dead_ends": dead_ends_count,
        "trajectory": trajectory,
        "findings": findings,
    }

    (run_dir / "exploration.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    (run_dir / "findings.explorer.json").write_text(json.dumps(findings, indent=2), encoding="utf-8")

    # If goal achieved, generate draft journey
    if goal_achieved and trajectory:
        try:
            import yaml

            journeys_dir = run_dir / "proposed_journeys"
            journeys_dir.mkdir(parents=True, exist_ok=True)
            draft_steps = [t["action"] for t in trajectory if "finish" not in t.get("action", {}) and t.get("status") == "ok"]
            draft_scenario = {
                "id": f"EXPLORE-{c_obj.id.upper()}",
                "title": f"Discovered flow: {c_obj.title}",
                "goal": c_obj.goal,
                "steps": draft_steps,
            }
            (journeys_dir / f"{c_obj.id}.yaml").write_text(yaml.dump(draft_scenario, sort_keys=False), encoding="utf-8")
        except Exception:
            pass

    return result
