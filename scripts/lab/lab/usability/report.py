"""Turn persona answers, exploration traces, and the moderator's brief into the maintainer's decision report."""

from __future__ import annotations

import base64
import html
import io
import json
import re
from pathlib import Path
from typing import Any

from ..paths import ensure_under, repo_root
from .panel import expected_screen_keys, moderator_input, text_load_rows

_UC_HEADING = re.compile(r"^## (UC-\d+) — (.+?)\s*$", re.M)


def use_case_titles(root: Path | None = None) -> dict[str, str]:
    """`{UC-n: title}` from the use-case catalogue (`private/docs/USE_CASES.md`), {} without it.

    Read, not copied: a second list of titles drifts from the catalogue (it already had UC-7 as
    "sidecar safety" while the catalogue's UC-7 is the phone-to-desk hand-over).
    """
    path = (root or repo_root()) / "private" / "docs" / "USE_CASES.md"
    try:
        return {m.group(1): m.group(2) for m in _UC_HEADING.finditer(path.read_text(encoding="utf-8"))}
    except OSError:
        return {}


MODERATOR_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["summary", "requirement_checks", "text_load", "consensus", "discoverability", "tradeoffs", "keep", "persona_fit"],
    "properties": {
        "summary": {"type": "string"},
        "requirement_checks": {"type": "array", "description": "one entry per binding requirement in requirements.json",
                               "items": {"type": "object", "required": ["id", "verdict", "screens", "evidence"],
                                         "properties": {"verdict": {"enum": ["pass", "fail", "unclear"]},
                                                        "variants": {"type": "array", "items": {"type": "string"}}}}},
        "text_load": {"type": "object", "required": ["verdict", "worst_screens", "note"],
                      "properties": {"verdict": {"enum": ["ok", "over"]},
                                     "worst_screens": {"type": "array", "items": {"type": "string"}}}},
        "consensus": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["title", "screens", "personas", "evidence", "suggestion"],
                "properties": {"severity": {"enum": ["blocker", "major", "minor"]}},
            },
        },
        "discoverability": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["screen", "expected", "missed_by", "suggestion"],
                "properties": {
                    "source": {"enum": ["walkthrough", "exploration", "both"]},
                    "severity": {"enum": ["blocker", "major", "minor"]},
                    "missed_by": {
                        "type": "array",
                        "items": {"type": "object", "required": ["persona", "would_do"]},
                    },
                },
            },
        },
        "tradeoffs": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["title", "screens", "tension", "options", "recommendation", "decide_if"],
                "properties": {
                    "options": {
                        "type": "array",
                        "items": {"type": "object", "required": ["option", "helps", "hurts", "cost"]},
                    }
                },
            },
        },
        "keep": {"type": "array", "items": {"type": "object", "required": ["title", "screens", "personas"]}},
        "persona_fit": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["persona", "fit", "note"],
                "properties": {"fit": {"enum": ["good", "mixed", "poor"]}},
            },
        },
        "use_case_fit": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["use_case", "fit"],
                "properties": {
                    "use_case": {"type": "string"},
                    "title": {"type": "string"},
                    "fit": {"enum": ["good", "mixed", "poor"]},
                    "score": {"type": "number"},
                    "persona_scores": {"type": "object"},
                    "summary": {"type": "string"},
                },
            },
        },
    },
}

_VERDICT_SCORE = {"good": 2, "ok": 1, "bad": 0}
_FIT_CLASS = {"good": "good", "mixed": "ok", "poor": "bad"}
_SEVERITY_ORDER = {"blocker": 0, "major": 1, "minor": 2}


class UseCaseFitList(list):
    """List of use-case fits supporting both list iteration and dict-like key access by UC code."""

    def __getitem__(self, key: Any) -> Any:
        if isinstance(key, str):
            for item in self:
                if item.get("use_case") == key:
                    return item
            raise KeyError(key)
        return super().__getitem__(key)

    def __contains__(self, key: Any) -> bool:
        if isinstance(key, str):
            return any(item.get("use_case") == key for item in self)
        return super().__contains__(key)

    def get(self, key: str, default: Any = None) -> Any:
        for item in self:
            if item.get("use_case") == key:
                return item
        return default


def load_panel_variants(panel_dir: Path) -> dict[str, tuple[Path, dict[str, Any]]]:
    out: dict[str, tuple[Path, dict[str, Any]]] = {}
    vdir = panel_dir / "variants"
    if vdir.is_dir():
        for p in sorted(vdir.glob("*.json")):
            out[p.stem] = (p, json.loads(p.read_text(encoding="utf-8")))
    return out


def _requirements(panel_dir: Path) -> list[dict[str, Any]]:
    p = panel_dir / "requirements.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else []


def load_answers(panel_dir: Path) -> dict[str, dict[str, Any]]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((panel_dir / "answers").glob("*.json"))}


def parse_use_cases(raw: Any) -> list[str]:
    """Parse string, list or comma-separated UC identifiers like 'UC-1, UC-3'."""
    if not raw:
        return []
    if isinstance(raw, list):
        items: list[str] = []
        for x in raw:
            items.extend(parse_use_cases(x))
        return items
    parts = [p.strip() for p in str(raw).split(",") if p.strip()]
    return [p for p in parts if p.startswith("UC-") or "UC" in p]


def scenario_use_cases(scenario: dict[str, Any], product: str | None = None) -> list[str]:
    """Extract UC-* codes for a scenario from walkthrough or scenario spec."""
    raw = scenario.get("use_case")
    if raw:
        return parse_use_cases(raw)
    if product:
        try:
            try:
                from .capture import load_scenarios
            except (ImportError, ValueError):
                from lab.usability.capture import load_scenarios

            spec = load_scenarios(product)
            for s in spec.get("scenarios", []):
                if s.get("id") == scenario.get("scenario") and s.get("use_case"):
                    return parse_use_cases(s["use_case"])
        except Exception:
            pass
    return []


def compute_use_case_fit(
    walkthrough: dict[str, Any],
    answers: dict[str, dict[str, Any]],
    scenario_spec: dict[str, Any] | None = None,
) -> UseCaseFitList:
    """Compute use-case fit per flow and persona from scenario ratings and issues."""
    product = walkthrough.get("product")
    spec_scenarios = {s["id"]: s for s in (scenario_spec or {}).get("scenarios", [])} if scenario_spec else {}

    # Map UC -> list of screen keys and scenario titles
    uc_screens: dict[str, list[str]] = {}
    uc_scenario_titles: dict[str, list[str]] = {}

    for sc in walkthrough.get("scenarios", []):
        sc_id = sc.get("scenario", "")
        spec_entry = spec_scenarios.get(sc_id, {})
        raw_uc = sc.get("use_case") or spec_entry.get("use_case")
        ucs = parse_use_cases(raw_uc) or scenario_use_cases(sc, product)
        if not ucs:
            ucs = ["UC-General"]

        for uc in ucs:
            uc_screens.setdefault(uc, [])
            uc_scenario_titles.setdefault(uc, [])
            if sc.get("title") and sc["title"] not in uc_scenario_titles[uc]:
                uc_scenario_titles[uc].append(sc["title"])
            for s in sc.get("screens", []):
                key = f"{sc_id}/{s['name']}"
                if key not in uc_screens[uc]:
                    uc_screens[uc].append(key)

    by_screen: dict[str, dict[str, dict[str, Any]]] = {
        pid: {r["screen"]: r for r in a.get("screens", [])} for pid, a in answers.items()
    }

    results = UseCaseFitList()
    personas = list(answers.keys())
    titles = use_case_titles()

    for uc, screen_keys in sorted(uc_screens.items()):
        persona_scores: dict[str, dict[str, Any]] = {}
        numeric_scores: list[float] = []

        for pid in personas:
            reviewed = [by_screen[pid].get(k) for k in screen_keys if by_screen[pid].get(k)]
            if not reviewed:
                persona_scores[pid] = {
                    "fit": "mixed",
                    "score": 0.5,
                    "verdicts": {"good": 0, "ok": 0, "bad": 0},
                    "blockers": 0,
                    "majors": 0,
                }
                continue

            v_counts = {"good": 0, "ok": 0, "bad": 0}
            blockers = 0
            majors = 0
            for r in reviewed:
                v = r.get("verdict", "ok")
                if v in v_counts:
                    v_counts[v] += 1
                for iss in r.get("issues", []) or []:
                    sev = iss.get("severity")
                    if sev == "blocker":
                        blockers += 1
                    elif sev == "major":
                        majors += 1

            total_v = sum(v_counts.values())
            raw_ratio = (v_counts["good"] * 2 + v_counts["ok"]) / (2 * total_v) if total_v > 0 else 0.5
            # Shares of screens, not issue counts: a persona that lists five minor points on every
            # screen used to drive every use case to 0 % ("poor" beside a moderator's "mixed").
            sev = [{i.get("severity") for i in (r.get("issues") or [])} for r in reviewed]
            blocker_share = sum("blocker" in s for s in sev) / len(reviewed)
            major_share = sum("major" in s and "blocker" not in s for s in sev) / len(reviewed)
            score = max(0.0, min(1.0, round(raw_ratio - 0.5 * blocker_share - 0.2 * major_share, 2)))
            numeric_scores.append(score)

            if blocker_share > 0.25 or score < 0.35:
                p_fit = "poor"
            elif score >= 0.7 and blocker_share == 0:
                p_fit = "good"
            else:
                p_fit = "mixed"

            persona_scores[pid] = {
                "fit": p_fit,
                "score": score,
                "verdicts": v_counts,
                "blockers": blockers,
                "majors": majors,
            }

        avg_score = round(sum(numeric_scores) / len(numeric_scores), 2) if numeric_scores else 0.5
        poor_count = sum(1 for ps in persona_scores.values() if ps["fit"] == "poor")
        good_count = sum(1 for ps in persona_scores.values() if ps["fit"] == "good")

        if poor_count > 0 and good_count > 0:
            overall_fit = "mixed"
        elif poor_count > len(persona_scores) / 2 or avg_score < 0.45:
            overall_fit = "poor"
        elif avg_score >= 0.75 and poor_count == 0:
            overall_fit = "good"
        else:
            overall_fit = "mixed"

        title = titles.get(uc) or ", ".join(uc_scenario_titles.get(uc, [])) or uc
        summary_parts = []
        if good_count:
            summary_parts.append(f"{good_count} personas satisfied")
        if poor_count:
            summary_parts.append(f"{poor_count} personas blocked/struggling")
        summary = "; ".join(summary_parts) if summary_parts else "Evaluation balanced across personas."

        results.append({
            "use_case": uc,
            "title": title,
            "fit": overall_fit,
            "overall_fit": overall_fit,
            "score": avg_score,
            "screens": screen_keys,
            "persona_scores": persona_scores,
            "personas": persona_scores,
            "summary": summary,
        })

    return results


def use_case_fits(
    walkthrough: dict[str, Any], answers: dict[str, dict[str, Any]], decisions: dict[str, Any] | None
) -> list[dict[str, Any]]:
    """The moderator's use-case verdicts with the screens each covers (the moderator does not
    list them); the computed fit when there is no moderator."""
    computed = compute_use_case_fit(walkthrough, answers)
    mine = [u for u in (decisions or {}).get("use_case_fit") or [] if u.get("use_case") in computed]
    if not mine:  # none, or keyed by something else (a moderator once rated scenarios: "U-TOK-01")
        return computed
    merged = []
    for u in mine:
        base = computed.get(u.get("use_case")) or {}
        # The moderator's verdict stands alone: a computed score next to it would contradict it.
        # A moderator may give a persona's score as a bare number or as {fit, score}.
        scores = {pid: (ps if isinstance(ps, dict) else {"score": ps})
                  for pid, ps in (u.get("persona_scores") or {}).items()}
        merged.append({"use_case": u.get("use_case"), "title": base.get("title") or u.get("title", ""),
                       "screens": u.get("screens") or base.get("screens", []), "score": u.get("score"),
                       **{k: v for k, v in u.items() if k not in ("screens", "persona_scores")},
                       "persona_scores": scores})
    return merged


def extract_discoverability_misses(
    walkthrough: dict[str, Any],
    answers: dict[str, dict[str, Any]],
    exploration: dict[str, Any] | None = None,
    decisions: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Screens where people could not find their way: the moderator's list, else the personas' own
    discoverability/understanding issues, plus the explorer's dead ends.

    A persona's free-text `would_do` is never compared with `expected` as a string: two
    phrasings of the same action always differ, and that comparison flagged almost every screen.
    """
    misses: list[dict[str, Any]] = []
    meta_by_screen = {m["screen"]: m for m in moderator_input(walkthrough)}

    if decisions and decisions.get("discoverability"):
        for d in decisions["discoverability"]:
            misses.append({
                "screen": d.get("screen", ""),
                "expected": d.get("expected", ""),
                "source": d.get("source", "walkthrough"),
                "severity": d.get("severity") or "major",
                "missed_by": list(d.get("missed_by", [])),
                "suggestion": d.get("suggestion", ""),
                "has_exploration_dead_end": d.get("source") in ("exploration", "both"),
            })
    else:
        by_screen: dict[str, dict[str, Any]] = {}
        for pid, a in answers.items():
            for r in a.get("screens", []):
                found = [i for i in (r.get("issues") or []) if i.get("kind") in ("discoverability", "understanding")]
                if not found:
                    continue
                key = r.get("screen", "")
                item = by_screen.setdefault(key, {
                    "screen": key,
                    "expected": meta_by_screen.get(key, {}).get("expected", ""),
                    "source": "walkthrough",
                    "severity": "minor",
                    "missed_by": [],
                    "suggestion": "",
                    "has_exploration_dead_end": False,
                })
                item["missed_by"].append({"persona": pid, "would_do": r.get("would_do", ""),
                                          "issue": "; ".join(str(i.get("what", "")) for i in found)})
                worst = min((i.get("severity") or "minor" for i in found), key=lambda s: _SEVERITY_ORDER.get(s, 2))
                if _SEVERITY_ORDER.get(worst, 2) < _SEVERITY_ORDER.get(item["severity"], 2):
                    item["severity"] = worst
        misses.extend(by_screen.values())

    # Explorer dead ends: cycles and "stuck" only; a hesitation is not a dead end.
    for f in (exploration or {}).get("findings", []):
        if f.get("rule") != "discoverability-dead-end":
            continue
        key = str(f.get("checkpoint") or f.get("screen") or "")
        dead = {"persona": exploration.get("persona", "AI explorer"),  # type: ignore[union-attr]
                "would_do": f.get("message") or f.get("detail", "")}
        existing = next((m for m in misses if key and m["screen"] == key), None)
        if existing:
            existing["source"] = "both"
            existing["has_exploration_dead_end"] = True
            existing["missed_by"].append(dead)
        else:
            misses.append({
                "screen": key or "(exploration)",
                "expected": meta_by_screen.get(key, {}).get("expected", ""),
                "source": "exploration",
                "severity": f.get("severity") or "major",
                "missed_by": [dead],
                "suggestion": "",
                "has_exploration_dead_end": True,
            })

    misses.sort(key=lambda m: _SEVERITY_ORDER.get(m.get("severity", "minor"), 2))
    return misses


# Alias for backwards compatibility
extract_discoverability_gaps = extract_discoverability_misses


def synthesize_tradeoffs(
    walkthrough: dict[str, Any],
    answers: dict[str, dict[str, Any]],
    decisions: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """The moderator's trade-offs; without a moderator, the screens where personas split.

    Without a moderator there are no options, costs or recommendations: inventing them here
    would put boilerplate in front of the maintainer as if the panel had weighed it.
    """
    if decisions and decisions.get("tradeoffs"):
        return decisions["tradeoffs"]
    tradeoffs: list[dict[str, Any]] = []
    for r in screen_matrix(walkthrough, answers):
        liked = [pid for pid, c in r["cells"].items() if c and c.get("verdict") == "good"]
        disliked = [pid for pid, c in r["cells"].items() if c and c.get("verdict") == "bad"]
        if not liked or not disliked:
            continue
        why = sorted({str(i.get("what", "")) for pid in disliked for i in (r["cells"][pid] or {}).get("issues") or []})
        tradeoffs.append({
            "title": f"Personas split on {r['screen']}",
            "screens": [r["screen"]],
            "tension": f"good for {', '.join(liked)}; bad for {', '.join(disliked)}" + (f": {'; '.join(why)}" if why else ""),
            "options": [],
            "recommendation": "",
            "decide_if": "",
        })
    return tradeoffs


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


def _pct(score: Any) -> str:
    return f" ({float(score):.0%})" if isinstance(score, (int, float)) else ""


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
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}
.pill{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:600;color:#fff}
.good{background:var(--good)}.ok{background:var(--ok)}.bad{background:var(--bad)}.none{background:var(--muted)}
table{border-collapse:collapse;width:100%}td,th{border-top:1px solid var(--line);padding:8px;vertical-align:top;text-align:left;font-size:13px}
.scroll{overflow-x:auto}.shot{width:108px;border-radius:6px;border:1px solid var(--line)}
.opt{border-left:3px solid var(--accent);padding:6px 12px;margin:8px 0;background:rgba(232,98,12,0.04);border-radius:0 6px 6px 0}
.q{font-style:italic}
.uc-chip{display:inline-block;padding:1px 6px;margin:2px;border-radius:4px;font-size:11px;font-weight:600}
.uc-chip.good{background:var(--good);color:#fff}.uc-chip.ok{background:var(--ok);color:#fff}.uc-chip.poor,.uc-chip.bad{background:var(--bad);color:#fff}
.dead-end-alert{border-left:4px solid var(--bad);padding:8px 12px;margin:8px 0;background:rgba(192,57,43,0.06);border-radius:0 6px 6px 0}
"""


def render_html(
    walkthrough: dict[str, Any],
    answers: dict[str, dict[str, Any]],
    decisions: dict[str, Any] | None,
    panel_dir: Path,
    exploration: dict[str, Any] | None = None,
) -> str:
    rows = screen_matrix(walkthrough, answers)
    personas = list(answers.keys())
    dev = walkthrough.get("device", {})
    uc_fits = use_case_fits(walkthrough, answers, decisions)
    misses = extract_discoverability_misses(walkthrough, answers, exploration=exploration, decisions=decisions)
    tradeoffs = synthesize_tradeoffs(walkthrough, answers, decisions=decisions)

    parts = [
        "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>",
        f"<title>Usability Panel {_e(walkthrough.get('product', ''))}</title><style>{CSS}</style></head><body><main>",
        f"<h1>Usability panel — {_e(walkthrough.get('product', ''))}</h1>",
        f"<p class='muted'>{_e(dev.get('model', dev.get('os', 'Unknown')))} · {_e(dev.get('size', ''))} · "
        f"captured {_e(walkthrough.get('captured_at', ''))} · {len(rows)} screens · personas: {_list(personas)}</p>",
    ]

    summary_text = (decisions or {}).get("summary") or "No moderator brief yet: the sections below are computed from the persona answers alone."
    parts.append(f"<div class='card'><b>Summary.</b> {_e(summary_text)}</div>")

    if decisions:
        parts.append("<h2>Binding requirements (stated by you)</h2><div class='scroll'><table><tr><th>requirement</th><th>verdict</th><th>screens</th><th>evidence</th></tr>")
        checks = {c.get("id"): c for c in decisions.get("requirement_checks", [])}
        for req in _requirements(panel_dir) or [{"id": k, "title": ""} for k in checks]:
            c = checks.get(req["id"], {})
            verdict = c.get("verdict", "not checked")
            cls = {"pass": "good", "fail": "bad"}.get(verdict, "ok")
            parts.append(f"<tr><td><b>{_e(req['id'])}</b> {_e(req.get('title', ''))}</td><td><span class='pill {cls}'>{_e(verdict)}</span></td>"
                         f"<td>{_list(c.get('screens', []))}</td><td>{_e(c.get('evidence', ''))}</td></tr>")
        parts.append("</table></div>")
        tl = decisions.get("text_load") or {}
        if tl:
            parts.append(f"<div class='card'><b>Text load:</b> <span class='pill {'bad' if tl.get('verdict') == 'over' else 'good'}'>{_e(tl.get('verdict'))}</span> "
                         f"{_e(tl.get('note', ''))} <span class='muted'>({_list(tl.get('worst_screens', []))})</span></div>")

    # 1. Use-Case Fit (UC-* Flows)
    if uc_fits:
        parts.append("<h2>Use-case fit (UC-* flows)</h2><div class='grid'>")
        for u in uc_fits:
            fit_cls = _FIT_CLASS.get(u.get("fit"), "ok")
            p_chips = "".join(
                f"<span class='uc-chip {ps.get('fit', 'ok')}'>{_e(pid)}{_pct(ps.get('score'))}</span>"
                for pid, ps in (u.get("persona_scores") or {}).items()
            )
            parts.append(
                f"<div class='card'><b>{_e(u.get('use_case'))}: {_e(u.get('title'))}</b> "
                f"<span class='pill {fit_cls}'>{_e(u.get('fit', '?'))}{_pct(u.get('score'))}</span>"
                f"<p class='muted'>{len(u.get('screens', []))} screens · {_e(u.get('summary', ''))}</p>"
                f"<div>{p_chips}</div></div>"
            )
        parts.append("</div>")

    # 2. Design Trade-off Cards
    if tradeoffs:
        parts.append("<h2>Decisions for you — trade-offs</h2>")
        for t in tradeoffs:
            opts = "".join(
                f"<div class='opt'><b>{_e(o.get('option'))}</b><br>helps: {_list(o.get('helps', []))} · "
                f"hurts: {_list(o.get('hurts', []))}<br><span class='muted'>cost: {_e(o.get('cost'))}</span></div>"
                for o in t.get("options", [])
            )
            rec = f"<p><b>Recommendation:</b> {_e(t.get('recommendation'))}</p>" if t.get("recommendation") else ""
            dec = f"<p><b>Decide by:</b> {_e(t.get('decide_if'))}</p>" if t.get("decide_if") else ""
            parts.append(
                f"<div class='card'><b>{_e(t.get('title'))}</b> <span class='muted'>({_list(t.get('screens', []))})</span>"
                f"<p><b>Tension:</b> {_e(t.get('tension'))}</p>{opts}{rec}{dec}</div>"
            )

    # 3. Discoverability Misses & Dead Ends
    if misses:
        parts.append("<h2>Would they find it? (discoverability misses & dead ends)</h2>")
        for d in misses:
            sev = d.get("severity", "major")
            sev_cls = "bad" if sev == "blocker" else "ok"
            source_tag = f" <span class='muted'>[{_e(d.get('source', 'walkthrough'))}]</span>"
            missed = "".join(
                f"<li><b>{_e(m.get('persona'))}:</b> {_e(m.get('would_do'))}</li>"
                for m in d.get("missed_by", [])
            )
            parts.append(
                f"<div class='card'><b>{_e(d.get('screen'))}</b> <span class='pill {sev_cls}'>{_e(sev)}</span>{source_tag}"
                f"<br><span class='muted'>expected: {_e(d.get('expected'))}</span><ul>{missed}</ul>"
                f"<b>Suggestion:</b> {_e(d.get('suggestion'))}</div>"
            )

    # 4. Consensus Issues by Severity
    consensus_items = (decisions or {}).get("consensus", [])
    if consensus_items:
        sorted_consensus = sorted(consensus_items, key=lambda c: _SEVERITY_ORDER.get(c.get("severity") or "major", 1))
        parts.append("<h2>Everyone agrees — consensus issues</h2>")
        for c in sorted_consensus:
            sev = c.get("severity") or "major"
            sev_cls = "bad" if sev == "blocker" else "ok" if sev == "major" else "none"
            parts.append(
                f"<div class='card'><b>{_e(c.get('title'))}</b> <span class='pill {sev_cls}'>{_e(sev)}</span>"
                f" <span class='muted'>({_list(c.get('screens', []))} · {_list(c.get('personas', []))})</span>"
                f"<p>{_e(c.get('evidence'))}</p><p><b>Suggestion:</b> {_e(c.get('suggestion'))}</p></div>"
            )

    # 5. Personas Overview
    parts.append("<h2>Personas</h2><div class='grid'>")
    persona_fits = (decisions or {}).get("persona_fit", [])
    for pid in personas:
        o = answers[pid].get("overall", {})
        f = _fit_for(pid, persona_fits)
        parts.append(
            f"<div class='card'><b>{_e(pid)}</b> <span class='pill {_FIT_CLASS.get(f.get('fit'), 'none')}'>"
            f"fit: {_e(f.get('fit', '?'))}</span> · keeps using: <b>{_e(o.get('would_keep_using', '?'))}</b>"
            f"<p class='q'>“{_e(o.get('why', ''))}”</p><p><b>One change:</b> {_e(o.get('one_change', ''))}</p>"
            f"<p class='muted'>{_e(f.get('note', ''))}</p></div>"
        )
    parts.append("</div>")

    # 6. Keep
    keep_items = (decisions or {}).get("keep", [])
    if keep_items:
        parts.append("<h2>Keep</h2><ul>")
        parts += [f"<li>{_e(k.get('title'))} <span class='muted'>({_list(k.get('screens', []))})</span></li>" for k in keep_items]
        parts.append("</ul>")

    # 7. Issues by Kind
    kinds = issue_kinds(answers)
    parts.append("<h2>Issues by kind</h2><div class='scroll'><table><tr><th>kind</th>" + "".join(f"<th>{_e(p)}</th>" for p in personas) + "</tr>")
    for kind, per in kinds.items():
        parts.append(f"<tr><td>{_e(kind)}</td>" + "".join(f"<td>{per.get(p, 0)}</td>" for p in personas) + "</tr>")
    parts.append("</table></div>")

    variants = load_panel_variants(panel_dir)
    if variants:
        labels = list(variants)
        parts.append("<h2>Text load, measured (words on screen · labelled icons · wordy controls)</h2><div class='scroll'><table><tr><th>screen</th>"
                     + "".join(f"<th>{_e(v)}</th>" for v in labels) + "</tr>")
        for r in text_load_rows(variants):
            cells = []
            for v in labels:
                c = r["cells"].get(v)
                if c is None:
                    cells.append("<td>—</td>")
                    continue
                flagged = "".join(f"<li>{_e(' / '.join(f['texts']))} <span class='muted'>{_e(', '.join(f['flags']))}</span></li>" for f in c["flagged"][:6])
                cls = "bad" if c["verdict"] == "over" and not r["system_screen"] else "good"
                cells.append(f"<td><span class='pill {cls}'>{c['words']} · {c['labelled_icons']} · {c['wordy_controls']}</span><ul>{flagged}</ul></td>")
            parts.append(f"<tr><td>{_e(r['screen'])}{' (system)' if r['system_screen'] else ''}</td>{''.join(cells)}</tr>")
        parts.append("</table></div>")

    # 8. Every Screen
    parts.append("<h2>Every screen</h2><div class='scroll'><table><tr><th>screen</th><th>load</th>"
                 + "".join(f"<th>{_e(p)}</th>" for p in personas) + "</tr>")
    images = panel_dir / "images"
    for scenario in walkthrough.get("scenarios", []):
        for s in scenario.get("screens", []):
            key = f"{scenario['scenario']}/{s['name']}"
            row = next((r for r in rows if r["screen"] == key), None)
            if not row:
                continue
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


def render_markdown(
    walkthrough: dict[str, Any],
    answers: dict[str, dict[str, Any]],
    decisions: dict[str, Any] | None,
    exploration: dict[str, Any] | None = None,
    panel_dir: Path | None = None,
) -> str:
    rows = screen_matrix(walkthrough, answers)
    personas = list(answers.keys())
    uc_fits = use_case_fits(walkthrough, answers, decisions)
    misses = extract_discoverability_misses(walkthrough, answers, exploration=exploration, decisions=decisions)
    tradeoffs = synthesize_tradeoffs(walkthrough, answers, decisions=decisions)

    out = [f"# Usability panel — {walkthrough.get('product', '')}", ""]
    summary_text = (decisions or {}).get("summary") or "No moderator brief yet: the sections below are computed from the persona answers alone."
    out += [f"> {summary_text}", ""]

    # 1. Use-case fit
    if uc_fits:
        out += ["## Use-case fit (UC-* flows)", "", "| Use Case | Title | Fit | Score | Screens | Persona Breakdown |", "|---|---|:---:|:---:|:---:|---|"]
        for u in uc_fits:
            breakdown = ", ".join(f"{pid}: {ps.get('fit', '?')}" for pid, ps in (u.get("persona_scores") or {}).items())
            out.append(f"| {u.get('use_case')} | {u.get('title')} | {u.get('fit')} | {_pct(u.get('score')).strip(' ()') or '—'} | {len(u.get('screens', []))} | {breakdown or u.get('summary', '')} |")
        out.append("")

    # 2. Trade-offs
    if tradeoffs:
        out += ["## Decisions for you — trade-offs", ""]
        for t in tradeoffs:
            screens_str = f" ({', '.join(map(str, _as_list(t.get('screens'))))})" if t.get("screens") else ""
            out.append(f"### {t.get('title')}{screens_str}")
            out += ["", f"**Tension:** {t.get('tension', '')}", ""]
            if t.get("options"):
                out.append("**Options:**")
            for o in t.get("options", []):
                out.append(f"- **{o.get('option')}** — helps {', '.join(map(str, _as_list(o.get('helps'))))}; hurts {', '.join(map(str, _as_list(o.get('hurts'))))}; cost: {o.get('cost')}")
            out.append("")
            if t.get("recommendation"):
                out.append(f"Recommendation: {t['recommendation']}")
            if t.get("decide_if"):
                out.append(f"Decide by: {t['decide_if']}")
            out.append("")
    if decisions:
        checks = decisions.get("requirement_checks", [])
        if checks:
            out += ["## Binding requirements (stated by you)", "", "| requirement | verdict | screens | evidence |", "|---|---|---|---|"]
            out += [f"| {c.get('id')} | {c.get('verdict')} | {', '.join(map(str, _as_list(c.get('screens'))))} | {str(c.get('evidence', '')).replace('|', '/')} |" for c in checks]
            out.append("")
        tl = decisions.get("text_load") or {}
        if tl:
            out += [f"**Text load: {tl.get('verdict')}** — {tl.get('note', '')} ({', '.join(map(str, _as_list(tl.get('worst_screens'))))})", ""]


    # 3. Discoverability misses
    if misses:
        out += ["## Would they find it? (discoverability misses & dead ends)", ""]
        for d in misses:
            out.append(f"### {d.get('screen')} [{d.get('severity', 'major')}] ({d.get('source', 'walkthrough')})")
            out.append(f"- **Expected:** {d.get('expected')}")
            for m in d.get("missed_by", []):
                out.append(f"- **{m.get('persona')}:** {m.get('would_do')}")
            out.append(f"- **Suggestion:** {d.get('suggestion')}")
            out.append("")

    # 4. Consensus issues
    consensus_items = (decisions or {}).get("consensus", [])
    if consensus_items:
        sorted_consensus = sorted(consensus_items, key=lambda c: _SEVERITY_ORDER.get(c.get("severity") or "major", 1))
        out += ["## Everyone agrees — consensus issues", ""]
        for c in sorted_consensus:
            out.append(f"- **[{(c.get('severity') or 'major').upper()}] {c.get('title')}** ({', '.join(map(str, _as_list(c.get('screens'))))}): {c.get('suggestion')}")
            if c.get("evidence"):
                out.append(f"  *Evidence:* {c.get('evidence')}")
        out.append("")

    # 5. Persona assessments
    out += ["## Persona assessments", "", "| Persona | Fit | Keep Using | Top requested change |", "|---|:---:|:---:|---|"]
    persona_fits = (decisions or {}).get("persona_fit", [])
    for pid in personas:
        o = answers[pid].get("overall", {})
        f = _fit_for(pid, persona_fits)
        out.append(f"| {pid} | {f.get('fit', '?')} | {o.get('would_keep_using', '?')} | {o.get('one_change', '')} |")
    out.append("")

    variants = load_panel_variants(panel_dir) if panel_dir else {}
    if variants:
        from .panel import text_load_table

        table, _ = text_load_table(variants)
        out += ["## Text load, measured", "", table]


    # 6. Screen matrix
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

    # Load exploration results if available in run_dir or panel_dir
    exp_path = run_dir / "exploration.json"
    if not exp_path.is_file():
        exp_path = panel_dir / "exploration.json"
    exploration = json.loads(exp_path.read_text(encoding="utf-8")) if exp_path.is_file() else None

    html_path = ensure_under(run_dir / "usability-report.html", run_dir)
    md_path = ensure_under(run_dir / "usability-report.md", run_dir)
    html_path.write_text(render_html(walkthrough, answers, decisions, panel_dir, exploration=exploration), encoding="utf-8")
    md_path.write_text(
        render_markdown(walkthrough, answers, decisions, exploration=exploration, panel_dir=panel_dir), encoding="utf-8"
    )
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
