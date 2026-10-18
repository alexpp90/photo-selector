"""Turn persona answers and the moderator's brief into the maintainer's decision report."""

from __future__ import annotations

import base64
import html
import io
import json
from pathlib import Path
from typing import Any

from ..paths import ensure_under
from .panel import expected_screen_keys, moderator_input

MODERATOR_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["summary", "consensus", "discoverability", "tradeoffs", "keep", "persona_fit"],
    "properties": {
        "summary": {"type": "string"},
        "consensus": {"type": "array", "items": {"type": "object", "required": ["title", "screens", "personas", "evidence", "suggestion"],
                      "properties": {"severity": {"enum": ["blocker", "major", "minor"]}}}},
        "discoverability": {"type": "array", "items": {"type": "object", "required": ["screen", "expected", "missed_by", "suggestion"],
                            "properties": {"missed_by": {"type": "array", "items": {"type": "object", "required": ["persona", "would_do"]}}}}},
        "tradeoffs": {"type": "array", "items": {"type": "object", "required": ["title", "screens", "tension", "options", "recommendation", "decide_if"],
                      "properties": {"options": {"type": "array", "items": {"type": "object", "required": ["option", "helps", "hurts", "cost"]}}}}},
        "keep": {"type": "array", "items": {"type": "object", "required": ["title", "screens", "personas"]}},
        "persona_fit": {"type": "array", "items": {"type": "object", "required": ["persona", "fit", "note"],
                        "properties": {"fit": {"enum": ["good", "mixed", "poor"]}}}},
    },
}

_VERDICT_SCORE = {"good": 2, "ok": 1, "bad": 0}
_FIT_CLASS = {"good": "good", "mixed": "ok", "poor": "bad"}


def load_answers(panel_dir: Path) -> dict[str, dict[str, Any]]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((panel_dir / "answers").glob("*.json"))}


def screen_matrix(walkthrough: dict[str, Any], answers: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    """One row per screen: measured load, expected action, and each persona's answer."""
    by_screen = {pid: {r["screen"]: r for r in a.get("screens", [])} for pid, a in answers.items()}
    rows = []
    for meta in moderator_input(walkthrough):
        key = meta["screen"]
        cells = {pid: by_screen[pid].get(key) for pid in answers}
        scores = [_VERDICT_SCORE[c["verdict"]] for c in cells.values() if c and c.get("verdict") in _VERDICT_SCORE]
        verdicts = {c["verdict"] for c in cells.values() if c}
        rows.append(
            {
                **meta,
                "cells": cells,
                "score": round(sum(scores) / len(scores), 2) if scores else None,
                "split": "good" in verdicts and "bad" in verdicts,
                "issues": sum(len((c or {}).get("issues") or []) for c in cells.values()),
            }
        )
    return rows


def issue_kinds(answers: dict[str, dict[str, Any]]) -> dict[str, dict[str, int]]:
    """`{kind: {persona: count}}` over every screen."""
    out: dict[str, dict[str, int]] = {}
    for pid, a in answers.items():
        for row in a.get("screens", []):
            for issue in row.get("issues") or []:
                out.setdefault(issue.get("kind", "?"), {}).setdefault(pid, 0)
                out[issue.get("kind", "?")][pid] += 1
    return dict(sorted(out.items(), key=lambda kv: -sum(kv[1].values())))


def _thumb(path: Path, width: int = 216) -> str | None:
    try:
        from PIL import Image  # real Pillow only; the stdlib shim cannot resample

        with Image.open(path) as img:
            img = img.convert("RGB")
            img = img.resize((width, max(1, int(img.height * width / img.width))))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=72)
        return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception:  # noqa: BLE001 — a missing thumbnail must not lose the report
        return None


def _fit_for(persona_id: str, fits: list[dict[str, Any]]) -> dict[str, Any]:
    """The moderator may write "Mia (commuter)" for the persona file `commuter`."""
    for f in fits:
        name = str(f.get("persona", "")).lower()
        if name == persona_id.lower() or persona_id.lower() in name:
            return f
    return {}


def _e(value: Any) -> str:
    return html.escape(str(value))


def _as_list(items: Any) -> list[Any]:
    """Moderator replies sometimes give a list field as one string; never split it into letters."""
    if items is None:
        return []
    return [items] if isinstance(items, str) else list(items)


def _list(items: Any) -> str:
    return ", ".join(_e(i) for i in _as_list(items))


CSS = """
:root{--bg:#fbfaf8;--fg:#1d1d1f;--muted:#6b6b70;--card:#fff;--line:#e4e2dd;--good:#1f8a4c;--ok:#b7791f;--bad:#c0392b;--accent:#e8620c}
@media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#ececef;--muted:#9a9aa2;--card:#1e1e22;--line:#2e2e34;--good:#3fbf74;--ok:#e0a640;--bad:#ff6b5b;--accent:#ff8a3d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1180px;margin:0 auto;padding:24px 16px 64px}h1{font-size:26px;margin:0 0 4px}h2{margin:36px 0 10px;font-size:20px}
.muted{color:var(--muted)}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin:10px 0}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:12px}
.pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600;color:#fff}
.good{background:var(--good)}.ok{background:var(--ok)}.bad{background:var(--bad)}.none{background:var(--muted)}
table{border-collapse:collapse;width:100%}td,th{border-top:1px solid var(--line);padding:8px;vertical-align:top;text-align:left;font-size:13px}
.scroll{overflow-x:auto}.shot{width:108px;border-radius:6px;border:1px solid var(--line)}
.opt{border-left:3px solid var(--accent);padding:4px 10px;margin:6px 0}.q{font-style:italic}
"""


def render_html(walkthrough: dict[str, Any], answers: dict[str, dict[str, Any]], decisions: dict[str, Any] | None, panel_dir: Path) -> str:
    rows = screen_matrix(walkthrough, answers)
    personas = list(answers)
    dev = walkthrough["device"]
    parts = [
        "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>",
        f"<title>Usability Panel {_e(walkthrough['product'])}</title><style>{CSS}</style></head><body><main>",
        f"<h1>Usability panel — {_e(walkthrough['product'])}</h1>",
        f"<p class='muted'>{_e(dev.get('model'))} · Android {_e(dev.get('android'))} · {_e(dev.get('size'))} @ {_e(dev.get('density'))} dpi · "
        f"captured {_e(walkthrough['captured_at'])} · {len(rows)} screens · personas: {_list(personas)}</p>",
    ]
    if decisions:
        parts.append(f"<div class='card'><b>Summary.</b> {_e(decisions.get('summary', ''))}</div>")
        parts.append("<h2>Decisions for you — trade-offs</h2>")
        for t in decisions.get("tradeoffs", []):
            opts = "".join(
                f"<div class='opt'><b>{_e(o.get('option'))}</b><br>helps: {_list(o.get('helps', []))} · hurts: {_list(o.get('hurts', []))}"
                f"<br><span class='muted'>cost: {_e(o.get('cost'))}</span></div>"
                for o in t.get("options", [])
            )
            parts.append(
                f"<div class='card'><b>{_e(t.get('title'))}</b> <span class='muted'>({_list(t.get('screens', []))})</span>"
                f"<p>{_e(t.get('tension'))}</p>{opts}<p><b>Recommendation:</b> {_e(t.get('recommendation'))}</p>"
                f"<p><b>Decide by:</b> {_e(t.get('decide_if'))}</p></div>"
            )
        parts.append("<h2>Everyone agrees — fix</h2>")
        for c in decisions.get("consensus", []):
            parts.append(
                f"<div class='card'><b>{_e(c.get('title'))}</b> <span class='pill {'bad' if c.get('severity') == 'blocker' else 'ok'}'>{_e(c.get('severity', ''))}</span>"
                f" <span class='muted'>({_list(c.get('screens', []))} · {_list(c.get('personas', []))})</span>"
                f"<p>{_e(c.get('evidence'))}</p><p><b>Suggestion:</b> {_e(c.get('suggestion'))}</p></div>"
            )
        parts.append("<h2>Would they find it? (would-do vs expected)</h2>")
        for d in decisions.get("discoverability", []):
            missed = "".join(f"<li><b>{_e(m.get('persona'))}:</b> {_e(m.get('would_do'))}</li>" for m in d.get("missed_by", []))
            parts.append(
                f"<div class='card'><b>{_e(d.get('screen'))}</b> — expected: {_e(d.get('expected'))}<ul>{missed}</ul>"
                f"<b>Suggestion:</b> {_e(d.get('suggestion'))}</div>"
            )
        parts.append("<h2>Personas</h2><div class='grid'>")
        for pid in personas:
            o = answers[pid].get("overall", {})
            f = _fit_for(pid, decisions.get("persona_fit", []))
            parts.append(
                f"<div class='card'><b>{_e(pid)}</b> <span class='pill {_FIT_CLASS.get(f.get('fit'), 'none')}'>"
                f"fit: {_e(f.get('fit', '?'))}</span> · keeps using: <b>{_e(o.get('would_keep_using', '?'))}</b>"
                f"<p class='q'>“{_e(o.get('why', ''))}”</p><p><b>One change:</b> {_e(o.get('one_change', ''))}</p>"
                f"<p class='muted'>{_e(f.get('note', ''))}</p></div>"
            )
        parts.append("</div>")
        parts.append("<h2>Keep</h2><ul>")
        parts += [f"<li>{_e(k.get('title'))} <span class='muted'>({_list(k.get('screens', []))})</span></li>" for k in decisions.get("keep", [])]
        parts.append("</ul>")

    kinds = issue_kinds(answers)
    parts.append("<h2>Issues by kind</h2><div class='scroll'><table><tr><th>kind</th>" + "".join(f"<th>{_e(p)}</th>" for p in personas) + "</tr>")
    for kind, per in kinds.items():
        parts.append(f"<tr><td>{_e(kind)}</td>" + "".join(f"<td>{per.get(p, 0)}</td>" for p in personas) + "</tr>")
    parts.append("</table></div>")

    parts.append("<h2>Every screen</h2><div class='scroll'><table><tr><th>screen</th><th>load</th>"
                 + "".join(f"<th>{_e(p)}</th>" for p in personas) + "</tr>")
    images = panel_dir / "images"
    for scenario in walkthrough["scenarios"]:
        for s in scenario["screens"]:
            key = f"{scenario['scenario']}/{s['name']}"
            row = next(r for r in rows if r["screen"] == key)
            src = _thumb(images / f"{scenario['scenario']}_{s['image']}")
            src = src or f"panel/images/{scenario['scenario']}_{s['image']}"
            img = f"<img class='shot' src='{_e(src)}' alt='{_e(key)}'>"
            parts.append(
                f"<tr><td>{img}<br><b>{_e(key)}</b>{' (system)' if row['system_screen'] else ''}<br><span class='muted'>wants: {_e(row['intent'])}"
                f"<br>expected: {_e(row['expected'])}</span></td><td>{row['words']} words<br>~{row['reading_seconds']} s"
                f"<br>longest {row['longest_block_words']}</td>"
            )
            for p in personas:
                c = row["cells"].get(p)
                if not c:
                    parts.append("<td><span class='pill none'>—</span></td>")
                    continue
                issues = "".join(f"<li>{_e(i.get('severity'))}: {_e(i.get('what'))}</li>" for i in c.get("issues") or [])
                parts.append(
                    f"<td><span class='pill {_e(c.get('verdict'))}'>{_e(c.get('verdict'))}</span> read {_e(c.get('read_text'))}"
                    f"<br><span class='q'>{_e(c.get('first_impression'))}</span><br>→ {_e(c.get('would_do'))}<ul>{issues}</ul></td>"
                )
            parts.append("</tr>")
    parts.append("</table></div></main></body></html>")
    return "".join(parts)


def render_markdown(walkthrough: dict[str, Any], answers: dict[str, dict[str, Any]], decisions: dict[str, Any] | None) -> str:
    rows = screen_matrix(walkthrough, answers)
    personas = list(answers)
    out = [f"# Usability panel — {walkthrough['product']}", ""]
    if decisions:
        out += [decisions.get("summary", ""), "", "## Decisions for you — trade-offs", ""]
        for t in decisions.get("tradeoffs", []):
            out.append(f"### {t.get('title')}  ({', '.join(map(str, _as_list(t.get('screens'))))})")
            out += ["", t.get("tension", ""), ""]
            for o in t.get("options", []):
                out.append(f"- **{o.get('option')}** — helps {', '.join(map(str, _as_list(o.get('helps'))))}; hurts {', '.join(map(str, _as_list(o.get('hurts'))))}; cost: {o.get('cost')}")
            out += ["", f"Recommendation: {t.get('recommendation')}", f"Decide by: {t.get('decide_if')}", ""]
        out += ["## Everyone agrees — fix", ""]
        out += [f"- **{c.get('title')}** ({', '.join(map(str, _as_list(c.get('screens'))))}): {c.get('suggestion')}" for c in decisions.get("consensus", [])]
        out.append("")
    out += ["## Every screen", "", "| screen | words | " + " | ".join(personas) + " |", "|---|---:|" + "---|" * len(personas)]
    for r in rows:
        cells = [(r["cells"].get(p) or {}).get("verdict", "—") for p in personas]
        out.append(f"| {r['screen']} | {r['words']} | " + " | ".join(cells) + " |")
    return "\n".join(out) + "\n"


def write_report(run_dir: Path) -> tuple[Path, Path]:
    run_dir = run_dir.resolve()
    panel_dir = run_dir / "panel"
    walkthrough = json.loads((run_dir / "walkthrough.json").read_text(encoding="utf-8"))
    answers = load_answers(panel_dir)
    if not answers:
        raise FileNotFoundError(f"no persona answers in {panel_dir / 'answers'}")
    dec_path = panel_dir / "decisions.json"
    decisions = json.loads(dec_path.read_text(encoding="utf-8")) if dec_path.is_file() else None
    html_path = ensure_under(run_dir / "usability-report.html", run_dir)
    md_path = ensure_under(run_dir / "usability-report.md", run_dir)
    html_path.write_text(render_html(walkthrough, answers, decisions, panel_dir), encoding="utf-8")
    md_path.write_text(render_markdown(walkthrough, answers, decisions), encoding="utf-8")
    return html_path, md_path


def coverage_problems(run_dir: Path) -> list[str]:
    """Screens a persona left out — reported, so a thin answer is visible rather than averaged away."""
    walkthrough = json.loads((run_dir / "walkthrough.json").read_text(encoding="utf-8"))
    keys = set(expected_screen_keys(walkthrough))
    problems = []
    for pid, a in load_answers(run_dir / "panel").items():
        missing = keys - {r.get("screen") for r in a.get("screens", [])}
        if missing:
            problems.append(f"{pid}: {len(missing)} screens not reviewed")
    return problems
