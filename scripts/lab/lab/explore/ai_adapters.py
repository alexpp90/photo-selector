"""AI judging of one exploratory step: review packets and the Claude / Gemini CLIs.

A provider looks at the before/after screenshots of a step against the recorded expectation and
answers with findings. It never decides a step passed: a failed judge raises `AIJudgeError`,
which the verifier turns into an `ai-judge-error` finding and an `error` step, so a step the AI
did not look at is never counted as checked.

- `none`: no AI; only the deterministic oracles run.
- `packet`: writes a review packet per step (images, trees, expectation, schema) for a human or
  an agent to review later; no findings come back, and nothing claims the step was judged.
- `claude`, `antigravity`: the packet is judged live by `claude -p` / `gemini -p`, started the
  way `lab/usability/explore.py` starts them (a model alias, no retired model ids, fail loud),
  in a sandbox under the lab tmp root outside the checkout.
"""

from __future__ import annotations

import abc
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from PIL import Image

from ..model import Finding
from ..paths import ensure_under, lab_tmp_root, repo_root
from ..review_bundle import compute_image_change_fraction, downscale_image
from ..schema import validate
from .actions import Action, ScreenState

MAX_LONG_EDGE = 1600
CLAUDE_MODEL = "sonnet"  # an alias, as in usability/explore.py: it follows the current model


class AIJudgeError(RuntimeError):
    """The AI judge could not give a usable answer for a step."""


@dataclass(frozen=True)
class JudgeContext:
    """Where a step happened and where its packet goes."""

    product: str  # schema product (`phototok`, `android-desktop`, `macos`, `linux`)
    config: str
    checkpoint: str  # `step_NN`
    step_index: int
    run_dir: Path  # every write stays under it
    packet_dir: Path
    timeout_s: float


@dataclass
class Judgement:
    findings: List[Finding]
    packet_dir: Optional[Path] = None


def _action_summary(action: Action) -> str:
    return action.summary()


def _tree_json(state: ScreenState) -> str:
    return json.dumps(state.tree.to_dict(), indent=2)


def export_packet(
    before_state: ScreenState,
    action: Action,
    expectation: Any,
    after_state: ScreenState,
    ctx: JudgeContext,
) -> Tuple[Path, Dict[str, Any]]:
    """Write the review packet of one step under `ctx.packet_dir`; returns it and its manifest."""
    if not before_state.screenshot_bytes or not after_state.screenshot_bytes:
        raise AIJudgeError(f"{ctx.checkpoint}: the step has no screenshot to review")
    packet = ensure_under(ctx.packet_dir, ctx.run_dir)
    images = packet / "images"
    images.mkdir(parents=True, exist_ok=True)

    sizes: Dict[str, Dict[str, List[int]]] = {}
    for role, state in (("before", before_state), ("after", after_state)):
        raw = packet / f"raw_{role}.png"
        raw.write_bytes(state.screenshot_bytes)
        dest = images / f"01_{role}.png"
        try:
            orig_w, orig_h = downscale_image(raw, dest, max_long_edge=MAX_LONG_EDGE)
            with Image.open(dest) as im:
                bundle_w, bundle_h = im.size
        except Exception as exc:  # noqa: BLE001 - re-raised with the step named
            raise AIJudgeError(f"{ctx.checkpoint}: the {role} screenshot is not a readable image: {exc}") from exc
        finally:
            raw.unlink(missing_ok=True)
        sizes[role] = {"orig_size": [orig_w, orig_h], "bundle_size": [bundle_w, bundle_h]}

    change = compute_image_change_fraction(images / "01_after.png", images / "01_before.png")
    description = getattr(expectation, "description", str(expectation))
    (packet / "instructions.md").write_text(
        "# UI review\n"
        "Compare images/01_before.png and images/01_after.png against context.md.\n"
        "Answer with a JSON array of findings that follow schema.json (`[]` when there is nothing).\n"
        "A region is [x, y, w, h] in pixels of images/01_after.png, or null.\n",
        encoding="utf-8",
    )
    (packet / "context.md").write_text(
        f"# Step {ctx.step_index}\n"
        f"Product: {ctx.product}\nConfig: {ctx.config}\n"
        f"Action: {_action_summary(action)}\n"
        f"Expectation: {description}\n"
        f"Image change ratio: {change:.4f}\n",
        encoding="utf-8",
    )
    (packet / "before.tree.json").write_text(_tree_json(before_state), encoding="utf-8")
    (packet / "after.tree.json").write_text(_tree_json(after_state), encoding="utf-8")
    schema = repo_root() / "scripts" / "lab" / "schemas" / "finding.schema.json"
    shutil.copy2(schema, packet / "schema.json")
    manifest: Dict[str, Any] = {
        "step_index": ctx.step_index,
        "checkpoint": ctx.checkpoint,
        "product": ctx.product,
        "config": ctx.config,
        "action": action.to_dict(),
        "expectation": getattr(expectation, "to_dict", lambda: {"description": description})(),
        "image_change_fraction": change,
        "images": [
            {"role": role, "path": f"images/01_{role}.png", **sizes[role]} for role in ("before", "after")
        ],
    }
    (packet / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return packet, manifest


def rescale_region(
    region: Any, orig_size: List[int], bundle_size: List[int]
) -> Optional[List[float]]:
    """A region in review-image pixels as a region in screenshot pixels, or None."""
    if not isinstance(region, list) or len(region) != 4:
        return None
    sx = orig_size[0] / max(1, bundle_size[0])
    sy = orig_size[1] / max(1, bundle_size[1])
    x, y, w, h = (float(v) for v in region)
    return [x * sx, y * sy, w * sx, h * sy]


def parse_ai_findings(raw: str, ctx: JudgeContext, manifest: Dict[str, Any]) -> List[Finding]:
    """The findings in a judge's answer; raises `AIJudgeError` for an answer that is not usable."""
    start, end = raw.find("["), raw.rfind("]")
    if start == -1 or end < start:
        raise AIJudgeError(f"answered without a JSON array: {raw.strip()[:200]!r}")
    try:
        items = json.loads(raw[start : end + 1])
    except ValueError as exc:
        raise AIJudgeError(f"answer is not valid JSON ({exc}): {raw.strip()[:200]!r}") from exc
    if not isinstance(items, list):
        raise AIJudgeError("answer is not a JSON array")
    after = next(img for img in manifest["images"] if img["role"] == "after")
    findings: List[Finding] = []
    for idx, item in enumerate(items):
        if not isinstance(item, dict):
            raise AIJudgeError(f"finding #{idx} is not an object")
        data = dict(item)
        data.setdefault("rule", "ai-visual")
        data.setdefault("severity", "minor")
        data.setdefault("confidence", "medium")
        if "message" not in data:
            data["message"] = str(data.pop("summary", "AI review finding"))
        data["region"] = rescale_region(data.get("region"), after["orig_size"], after["bundle_size"])
        # What the run knows beats what the model guessed.
        data.update(
            source="ai-explorer",
            product=ctx.product,
            config=ctx.config,
            journey="explore",
            checkpoint=ctx.checkpoint,
            evidence=[],
            new_since_last_run=True,
        )
        data["id"] = Finding.make_id(ctx.product, "explore", ctx.checkpoint, str(data["rule"]), data["region"])
        errors = validate(data, "finding")
        if errors:
            raise AIJudgeError(f"finding #{idx} breaks the finding schema: {errors}")
        findings.append(Finding.from_dict(data))
    return findings


class AIProvider(abc.ABC):
    """Judges the outcome of one step."""

    name: str = "base"

    @abc.abstractmethod
    def judge(
        self,
        before_state: ScreenState,
        action: Action,
        expectation: Any,
        after_state: ScreenState,
        ctx: JudgeContext,
    ) -> Judgement:
        """Findings for the step; raises `AIJudgeError` when it could not be judged."""

    @abc.abstractmethod
    def is_available(self) -> bool:
        """Whether the provider can run on this machine."""


class NoneProvider(AIProvider):
    name = "none"

    def judge(self, before_state, action, expectation, after_state, ctx) -> Judgement:  # type: ignore[no-untyped-def]
        return Judgement([])

    def is_available(self) -> bool:
        return True


class PacketExportAdapter(AIProvider):
    """Writes the review packet and leaves the judging to a later reviewer."""

    name = "packet"

    def judge(self, before_state, action, expectation, after_state, ctx) -> Judgement:  # type: ignore[no-untyped-def]
        packet, _ = export_packet(before_state, action, expectation, after_state, ctx)
        return Judgement([], packet)

    def is_available(self) -> bool:
        return True


class CliJudge(AIProvider):
    """Judges a packet with `claude -p` or `gemini -p`, started like `CliExplorationAgent` does."""

    def __init__(self, cli: str, model: Optional[str] = None) -> None:
        if cli not in ("claude", "gemini"):
            raise ValueError(f"unsupported judge CLI {cli!r}")
        self.cli = cli
        self.name = "claude" if cli == "claude" else "antigravity"
        # Version-free: an alias for Claude, the CLI's own default for Gemini (no -m).
        self.model = model or ("sonnet" if cli == "claude" else None)

    def is_available(self) -> bool:
        return shutil.which(self.cli) is not None

    def build_command(self, prompt: str) -> List[str]:
        if self.cli == "claude":
            return ["claude", "-p", prompt, "--model", str(self.model), "--allowedTools", "Read"]
        cmd = [
            "gemini",
            "-p",
            "Inputs: @instructions.md @context.md @schema.json @before.png @after.png\n" + prompt,
        ]
        if self.model:
            cmd += ["-m", self.model]
        return cmd

    def judge(self, before_state, action, expectation, after_state, ctx) -> Judgement:  # type: ignore[no-untyped-def]
        packet, manifest = export_packet(before_state, action, expectation, after_state, ctx)
        description = getattr(expectation, "description", str(expectation))
        prompt = (
            f"You are a UI QA judge. Read before.png and after.png in this folder, plus context.md and "
            f"schema.json. The action was '{_action_summary(action)}'; the expectation: {description}. "
            "Answer ONLY with a JSON array of findings that follow schema.json (`[]` for none). "
            "A region is [x, y, w, h] in pixels of after.png, or null."
        )
        root = lab_tmp_root()
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=f"ai_explore_{self.cli}_", dir=root) as tmp:
            sandbox = Path(tmp)
            shutil.copy2(packet / "images" / "01_before.png", sandbox / "before.png")
            shutil.copy2(packet / "images" / "01_after.png", sandbox / "after.png")
            for name in ("instructions.md", "context.md", "schema.json", "before.tree.json", "after.tree.json"):
                shutil.copy2(packet / name, sandbox / name)
            try:
                res = subprocess.run(
                    self.build_command(prompt),
                    cwd=sandbox,
                    capture_output=True,
                    text=True,
                    check=True,
                    timeout=ctx.timeout_s,
                )
            except subprocess.TimeoutExpired as exc:
                raise AIJudgeError(f"{self.cli} timed out after {ctx.timeout_s:.0f}s") from exc
            except subprocess.CalledProcessError as exc:
                raise AIJudgeError(
                    f"{self.cli} exited with {exc.returncode}: {(exc.stderr or exc.stdout or '').strip()[:300]}"
                ) from exc
            except OSError as exc:
                raise AIJudgeError(f"{self.cli} could not be started: {exc}") from exc
        (packet / "findings.raw.txt").write_text(res.stdout, encoding="utf-8")
        findings = parse_ai_findings(res.stdout, ctx, manifest)
        (packet / "findings.json").write_text(json.dumps([f.to_dict() for f in findings], indent=2), encoding="utf-8")
        return Judgement(findings, packet)


def create_ai_adapter(provider_name: str) -> AIProvider:
    """The provider named on the command line; raises `ValueError` for an unknown one."""
    name = (provider_name or "").lower().strip()
    if name == "claude":
        return CliJudge("claude")
    if name == "antigravity":
        return CliJudge("gemini")
    if name == "packet":
        return PacketExportAdapter()
    if name in ("none", ""):
        return NoneProvider()
    raise ValueError(f"unsupported AI provider: {provider_name!r}")
