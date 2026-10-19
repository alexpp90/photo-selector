"""Build the persona panel's packets from a captured walkthrough and read their answers back.

Each persona gets the screenshots, the task and what the user did between screens — nothing
else. No code, no requirements, no extracted text, and not the action the design expects:
the persona says what they would do, and the moderator compares that with `expected`. That
separation is what makes the review a user's view instead of another code review.

A walk can be captured several times (`usability-capture --locale de-DE --font-scale 2.0`). Each
persona reviews the variant its file names (`<!-- setup: de-DE@2.0 -->`, the phone that persona
would have), so English and German, normal and large text are all seen by someone. Only the
moderator gets the binding requirements (`[USER …]`, from
`private/scripts/check_requirement_enforcement.py`) as a checklist, together with the measured
text load per screen and per control (`textload.py`) of every variant.
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


_SETUP = re.compile(r"<!--\s*setup:\s*([\w-]+@\d+(?:\.\d+)?)\s*-->")


def persona_setup(markdown: str) -> str | None:
    """The variant a persona uses (`de-DE@2.0`), or None for the primary capture."""
    m = _SETUP.search(markdown)
    return m.group(1) if m else None


def variant_of(walkthrough: dict[str, Any]) -> str:
    if walkthrough.get("variant"):
        return str(walkthrough["variant"])
    dev = walkthrough.get("device", {})
    try:
        scale = float(dev.get("font_scale") or 1.0)
    except ValueError:
        scale = 1.0
    return f"{dev.get('app_locale') or 'en'}@{scale:.1f}"


def load_variants(run_dir: Path, others: list[Path] | None = None) -> dict[str, tuple[Path, dict[str, Any]]]:
    """`{variant: (run_dir, walkthrough)}`, the primary run first."""
    out: dict[str, tuple[Path, dict[str, Any]]] = {}
    for d in [run_dir, *(others or [])]:
        d = d.resolve()
        w = json.loads((d / "walkthrough.json").read_text(encoding="utf-8"))
        out.setdefault(variant_of(w), (d, w))
    return out


def binding_requirements(product: str, root: Path | None = None) -> list[dict[str, Any]]:
    """The product's `[USER …]` requirements from the private overlay, or [] in a public clone."""
    scripts = (root or repo_root()) / "private" / "scripts"
    if not (scripts / "check_requirement_enforcement.py").is_file():
        return []
    import sys

    sys.path.insert(0, str(scripts))
    try:
        import check_requirement_enforcement as enf  # type: ignore[import-not-found]

        return [
            {"id": i.id, "title": i.title, "stated": i.user_dates, "enforced_by": i.enforced_by,
             "text": re.sub(r"\s+", " ", i.text)}
            for i in enf.collect(product) if i.is_user
        ]
    finally:
        sys.path.remove(str(scripts))


def text_load_rows(variants: dict[str, tuple[Path, dict[str, Any]]]) -> list[dict[str, Any]]:
    """One row per screen of the primary walk, with each variant's text load beside it."""
    first = next(iter(variants.values()))[1]
    index = {
        label: {screen_key(sc["scenario"], s["name"]): s for sc, s in _screens(w)}
        for label, (_, w) in variants.items()
    }
    rows = []
    for sc, s in _screens(first):
        key = screen_key(sc["scenario"], s["name"])
        cells = {}
        for label in variants:
            v = index[label].get(key)
            if v is None:
                cells[label] = None
                continue
            tl = v["metrics"].get("text_load") or {}
            cells[label] = {
                "words": v["metrics"]["words"],
                "control_words": tl.get("control_words", 0),
                "max_control_words": tl.get("max_control_words", 0),
                "labelled_icons": tl.get("labelled_icons", 0),
                "wordy_controls": tl.get("wordy_controls", 0),
                "flagged": tl.get("flagged", []),
                "verdict": tl.get("verdict", "ok"),
            }
        rows.append({"screen": key, "system_screen": bool(s.get("system_screen")), "cells": cells})
    return rows


def text_load_table(variants: dict[str, tuple[Path, dict[str, Any]]]) -> tuple[str, bool]:
    """Markdown table of the text-load criterion; True when an app screen is over budget."""
    rows = text_load_rows(variants)
    labels = list(variants)
    out = [
        "Text load (docs/shared/UI_TEXT.md): words on screen · labelled icons · wordy controls",
        "",
        "| screen | " + " | ".join(labels) + " |",
        "|---|" + "---|" * len(labels),
    ]
    over = False
    worst: dict[str, set[str]] = {}
    for r in rows:
        cells = []
        for label in labels:
            c = r["cells"].get(label)
            if c is None:
                cells.append("—")
                continue
            mark = " ✘" if c["verdict"] == "over" and not r["system_screen"] else ""
            cells.append(f"{c['words']} · {c['labelled_icons']} · {c['wordy_controls']}{mark}")
            if c["verdict"] == "over" and not r["system_screen"]:
                over = True
                for f in c["flagged"]:
                    worst.setdefault(label, set()).add(f"{' / '.join(f['texts'])[:60]} [{', '.join(f['flags'])}]")
        name = r["screen"] + (" (system)" if r["system_screen"] else "")
        out.append(f"| {name} | " + " | ".join(cells) + " |")
    for label, items in worst.items():
        out += ["", f"Flagged controls, {label}:"] + [f"- {i}" for i in sorted(items)]
    return "\n".join(out) + "\n", over


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


def build_panel(run_dir: Path, *, priv: Path | None = None, variants: list[Path] | None = None) -> Path:
    """Write `<run_dir>/panel/` with images, walkthroughs, persona files, prompts and schema."""
    run_dir = run_dir.resolve()
    all_variants = load_variants(run_dir, variants)
    walkthrough = all_variants[next(iter(all_variants))][1]
    priv_dir = priv or panel_dir_private()
    personas = load_personas(walkthrough["product"], priv_dir)

    out = ensure_under(run_dir / "panel", run_dir)
    img_out = out / "images"
    img_out.mkdir(parents=True, exist_ok=True)
    primary = next(iter(all_variants))
    for label, (vdir, w) in all_variants.items():
        sub = img_out if label == primary else img_out / label
        sub.mkdir(parents=True, exist_ok=True)
        for scenario, s in _screens(w):
            src = vdir / scenario["scenario"] / s["image"]
            shutil.copy2(src, ensure_under(sub / f"{scenario['scenario']}_{s['image']}", out))
        vdir_out = out / "variants"
        vdir_out.mkdir(exist_ok=True)
        (vdir_out / f"{label}.json").write_text(json.dumps(w, indent=2), encoding="utf-8")

    (out / "walkthrough.md").write_text(render_walkthrough_md(walkthrough), encoding="utf-8")
    pdir = out / "personas"
    pdir.mkdir(exist_ok=True)
    assignments: dict[str, str] = {}
    for pid, text in personas.items():
        (pdir / f"{pid}.md").write_text(text, encoding="utf-8")
        setup = persona_setup(text)
        label = setup if setup in all_variants else primary
        assignments[pid] = label
        w = all_variants[label][1]
        image_dir = "images" if label == primary else f"images/{label}"
        (out / f"walkthrough-{pid}.md").write_text(render_walkthrough_md(w, image_dir), encoding="utf-8")
    (out / "assignments.json").write_text(json.dumps(assignments, indent=2), encoding="utf-8")
    for name in ("panel.md", "moderator.md"):
        src = priv_dir / name
        if src.is_file():
            shutil.copy2(src, out / name)
    from .report import MODERATOR_SCHEMA

    (out / "persona.schema.json").write_text(json.dumps(PERSONA_SCHEMA, indent=2), encoding="utf-8")
    (out / "moderator.schema.json").write_text(json.dumps(MODERATOR_SCHEMA, indent=2), encoding="utf-8")
    (out / "answers").mkdir(exist_ok=True)
    (out / "requirements.json").write_text(
        json.dumps(binding_requirements(walkthrough["product"]), indent=2, ensure_ascii=False), encoding="utf-8")
    (out / "moderator-input.json").write_text(
        json.dumps(moderator_input(walkthrough, all_variants), indent=2, ensure_ascii=False), encoding="utf-8")
    table, _ = text_load_table(all_variants)
    (out / "text-load.md").write_text(table, encoding="utf-8")
    return out


def moderator_input(
    walkthrough: dict[str, Any], variants: dict[str, tuple[Path, dict[str, Any]]] | None = None
) -> list[dict[str, Any]]:
    """What only the moderator sees: the expected action and the measured text load per screen."""
    load = {r["screen"]: r["cells"] for r in text_load_rows(variants)} if variants else {}
    rows = []
    for scenario, s in _screens(walkthrough):
        m = s["metrics"]
        key = screen_key(scenario["scenario"], s["name"])
        tl = m.get("text_load") or {}
        rows.append(
            {
                "screen": key,
                "intent": s["intent"],
                "expected": s["expected"],
                "system_screen": s.get("system_screen", False),
                "words": m["words"],
                "reading_seconds": m["reading_seconds"],
                "longest_block_words": m["longest_block_words"],
                "small_targets": m["small_targets"],
                "texts": m["texts"],
                "labelled_icons": tl.get("labelled_icons", 0),
                "wordy_controls": tl.get("wordy_controls", 0),
                "flagged_controls": tl.get("flagged", []),
                "text_load_by_variant": load.get(key, {}),
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
