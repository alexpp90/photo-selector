"""Build the persona panel's packets from a captured walkthrough and read their answers back.

Each persona gets the screenshots, the task and what the user did between screens — nothing
else. No code, no requirements, no extracted text, and not the action the design expects:
the persona says what they would do, and the moderator compares that with `expected`. That
separation is what makes the review a user's view instead of another code review.
"""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from typing import Any

from ..paths import ensure_under, repo_root

VERDICTS = ("good", "ok", "bad")
SEVERITIES = ("blocker", "major", "minor")
ISSUE_KINDS = (
    "understanding",
    "discoverability",
    "too-much-text",
    "trust",
    "effort",
    "feedback",
    "look",
)


def panel_dir_private(root: Path | None = None) -> Path:
    return (root or repo_root()) / "private" / "lab" / "usability"


def load_personas(product: str, priv: Path | None = None) -> dict[str, str]:
    """`{persona_id: markdown}` from `private/lab/usability/personas/<product>/*.md`."""
    base = (priv or panel_dir_private()) / "personas" / product
    if not base.is_dir():
        raise FileNotFoundError(f"no personas for {product} in {base}")
    return {p.stem: p.read_text(encoding="utf-8") for p in sorted(base.glob("*.md"))}


def _screens(walkthrough: dict[str, Any]) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    return [(sc, s) for sc in walkthrough["scenarios"] for s in sc["screens"]]


def screen_key(scenario_id: str, screen_name: str) -> str:
    return f"{scenario_id}/{screen_name}"


def render_walkthrough_md(walkthrough: dict[str, Any], image_dir: str = "images") -> str:
    """The persona's view: goal, what you did, what you want now, and the screenshot."""
    lines = [
        "# Walkthrough",
        "",
        f"Device: {walkthrough['device'].get('model', '?')} phone, portrait.",
        "",
    ]
    for scenario in walkthrough["scenarios"]:
        lines += [f"## {scenario['scenario']} — {scenario['title']}", "", f"**Your task:** {scenario['goal']}", ""]
        for s in scenario["screens"]:
            key = screen_key(scenario["scenario"], s["name"])
            lines.append(f"### `{key}`")
            if s["actions_before"]:
                lines.append(f"- **You just:** {'; '.join(s['actions_before'])}")
            lines.append(f"- **Screenshot:** `{image_dir}/{scenario['scenario']}_{s['image']}`")
            if s.get("system_screen"):
                lines.append("- This is an Android system screen, not the app's own.")
            lines.append(f"- **You want to:** {s['intent']}")
            lines.append("")
        if scenario["status"] != "pass":
            lines += ["(The test stopped here — the walkthrough could not continue.)", ""]
    return "\n".join(lines)


def build_panel(run_dir: Path, *, priv: Path | None = None) -> Path:
    """Write `<run_dir>/panel/` with images, walkthrough, persona files, prompts and schema."""
    run_dir = run_dir.resolve()
    walkthrough = json.loads((run_dir / "walkthrough.json").read_text(encoding="utf-8"))
    priv_dir = priv or panel_dir_private()
    personas = load_personas(walkthrough["product"], priv_dir)

    out = ensure_under(run_dir / "panel", run_dir)
    img_out = out / "images"
    img_out.mkdir(parents=True, exist_ok=True)
    for scenario, s in _screens(walkthrough):
        src = run_dir / scenario["scenario"] / s["image"]
        shutil.copy2(src, ensure_under(img_out / f"{scenario['scenario']}_{s['image']}", out))

    (out / "walkthrough.md").write_text(render_walkthrough_md(walkthrough), encoding="utf-8")
    pdir = out / "personas"
    pdir.mkdir(exist_ok=True)
    for pid, text in personas.items():
        (pdir / f"{pid}.md").write_text(text, encoding="utf-8")
    for name in ("panel.md", "moderator.md"):
        src = priv_dir / name
        if src.is_file():
            shutil.copy2(src, out / name)
    from .report import MODERATOR_SCHEMA

    (out / "persona.schema.json").write_text(json.dumps(PERSONA_SCHEMA, indent=2), encoding="utf-8")
    (out / "moderator.schema.json").write_text(json.dumps(MODERATOR_SCHEMA, indent=2), encoding="utf-8")
    (out / "answers").mkdir(exist_ok=True)
    (out / "moderator-input.json").write_text(json.dumps(moderator_input(walkthrough), indent=2), encoding="utf-8")
    return out


def moderator_input(walkthrough: dict[str, Any]) -> list[dict[str, Any]]:
    """What only the moderator sees: the expected action and the measured text load per screen."""
    rows = []
    for scenario, s in _screens(walkthrough):
        m = s["metrics"]
        rows.append(
            {
                "screen": screen_key(scenario["scenario"], s["name"]),
                "intent": s["intent"],
                "expected": s["expected"],
                "system_screen": s.get("system_screen", False),
                "words": m["words"],
                "reading_seconds": m["reading_seconds"],
                "longest_block_words": m["longest_block_words"],
                "small_targets": m["small_targets"],
                "texts": m["texts"],
            }
        )
    return rows


PERSONA_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["persona", "screens", "overall"],
    "properties": {
        "persona": {"type": "string"},
        "screens": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["screen", "first_impression", "would_do", "read_text", "verdict"],
                "properties": {
                    "screen": {"type": "string", "description": "the `scenario/screen` key"},
                    "first_impression": {"type": "string", "description": "what you think this screen is, <= 20 words"},
                    "would_do": {"type": "string", "description": "what you would do now to reach 'You want to'"},
                    "read_text": {"enum": ["all", "some", "none"]},
                    "verdict": {"enum": list(VERDICTS)},
                    "issues": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "required": ["what", "severity", "kind"],
                            "properties": {
                                "what": {"type": "string"},
                                "severity": {"enum": list(SEVERITIES)},
                                "kind": {"enum": list(ISSUE_KINDS)},
                            },
                        },
                    },
                    "liked": {"type": "array", "items": {"type": "string"}},
                },
            },
        },
        "overall": {
            "type": "object",
            "required": ["would_keep_using", "why", "top_problems", "top_strengths", "one_change"],
            "properties": {
                "would_keep_using": {"enum": ["yes", "maybe", "no"]},
                "why": {"type": "string"},
                "top_problems": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
                "top_strengths": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
                "one_change": {"type": "string"},
            },
        },
    },
}


def extract_json(raw: str) -> Any:
    """The last JSON object or array in an LLM reply (fenced or bare)."""
    fenced = re.findall(r"```(?:json)?\s*(\{.*?\}|\[.*?\])\s*```", raw, flags=re.S)
    candidates = fenced or [raw[raw.find("{") : raw.rfind("}") + 1]]
    for cand in reversed(candidates):
        try:
            return json.loads(cand)
        except json.JSONDecodeError:
            continue
    raise ValueError("no JSON object found in reply")


def validate_persona(answer: dict[str, Any], expected_screens: list[str]) -> list[str]:
    """Problems with a persona answer; empty when it can be used."""
    problems: list[str] = []
    if not isinstance(answer, dict):
        return ["answer is not an object"]
    for key in PERSONA_SCHEMA["required"]:
        if key not in answer:
            problems.append(f"missing '{key}'")
    seen = set()
    for row in answer.get("screens", []):
        seen.add(row.get("screen"))
        if row.get("verdict") not in VERDICTS:
            problems.append(f"{row.get('screen')}: verdict {row.get('verdict')!r}")
        for issue in row.get("issues", []) or []:
            if issue.get("severity") not in SEVERITIES:
                problems.append(f"{row.get('screen')}: severity {issue.get('severity')!r}")
            if issue.get("kind") not in ISSUE_KINDS:
                problems.append(f"{row.get('screen')}: kind {issue.get('kind')!r}")
    missing = [s for s in expected_screens if s not in seen]
    if missing:
        problems.append(f"screens not reviewed: {', '.join(missing)}")
    return problems


def expected_screen_keys(walkthrough: dict[str, Any]) -> list[str]:
    return [screen_key(sc["scenario"], s["name"]) for sc, s in _screens(walkthrough)]
