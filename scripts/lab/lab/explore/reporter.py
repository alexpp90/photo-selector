"""Root-signature deduplication and the reports of an exploratory run.

Everything is written inside the run directory. Nothing is filed anywhere else: a person reads
`report.md` / `report.html`, decides which findings are real, and files those, the way the
usability suite's reports are handled.
"""

from __future__ import annotations

import base64
import hashlib
import html
import io
import json
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from ..model import Finding
from ..paths import ensure_under

logger = logging.getLogger("lab.explore.reporter")

# Severity order for sorting and escalation (lower rank = higher priority)
SEVERITY_ORDER: Dict[str, int] = {"blocker": 0, "major": 1, "minor": 2, "polish": 3}
CONFIDENCE_ORDER: Dict[str, int] = {"high": 0, "medium": 1, "low": 2}
BLOCKING_SEVERITIES = ("blocker", "major")


# =====================================================================
# Root signature
# =====================================================================

def normalize_action_signature(action_sig: Any) -> str:
    """Normalize an action (string, `Action`, or its dict) to a stable lowercase string."""
    if action_sig is None:
        return ""
    if isinstance(action_sig, str):
        return action_sig.strip().lower()
    if hasattr(action_sig, "signature"):
        return str(action_sig.signature).strip().lower()
    if isinstance(action_sig, dict):
        act_type = str(action_sig.get("action_type", "")).strip().lower()
        target = action_sig.get("target_id")
        params = action_sig.get("params", {})
        if target:
            return f"{act_type}:{str(target).strip().lower()}"
        if params:
            return f"{act_type}:{','.join(f'{k}={params[k]}' for k in sorted(params, key=str)).strip().lower()}"
        return act_type
    return str(action_sig).strip().lower()


def normalize_subject(subject: Any) -> str:
    """Normalize a subject to a stable string; sequences are sorted."""
    if subject is None:
        return ""
    if isinstance(subject, str):
        return subject.strip().lower()
    if isinstance(subject, (list, tuple)):
        return ",".join(sorted(str(x).strip().lower() for x in subject))
    if isinstance(subject, dict):
        return ",".join(f"{k}={subject[k]}" for k in sorted(subject, key=str))
    return str(subject).strip().lower()


def compute_root_signature(
    product: str,
    screen_sig: str,
    action_sig: Any,
    violated_rule: str,
    subject: Any = "",
) -> str:
    """12-hex-character SHA-1 of `product|screen|action|rule|subject`.

    The step number is deliberately not part of it: the same defect found again, or on
    another device, has the same id and is reported once.
    """
    p = str(product or "").strip().lower()
    if p in ("jackpix", "phototok"):
        p = "phototok"
    elif p in ("frameduel", "android-desktop"):
        p = "android-desktop"
    elif p in ("lensjet", "macos-desktop", "macos"):
        p = "macos"
    elif p in ("blitzlens", "linux-desktop", "linux"):
        p = "linux"
    raw = "|".join(
        [
            p,
            str(screen_sig or "").strip().lower(),
            normalize_action_signature(action_sig),
            str(violated_rule or "").strip().lower(),
            normalize_subject(subject),
        ]
    )
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]


# =====================================================================
# Deduplication
# =====================================================================

@dataclass
class AggregatedFinding(Finding):
    """One root signature with everything it was seen as."""

    occurrences: int = 1
    step_indices: List[int] = field(default_factory=list)
    devices: List[str] = field(default_factory=list)
    configs: List[str] = field(default_factory=list)
    checkpoints: List[str] = field(default_factory=list)
    first_step: Optional[int] = None
    last_step: Optional[int] = None
    first_trace: Optional[Dict[str, Any]] = None
    last_trace: Optional[Dict[str, Any]] = None
    known: bool = False  # triaged in known_findings.yaml: listed, but it does not fail the run
    raw_findings: List[Finding] = field(default_factory=list)

    @property
    def root_signature(self) -> str:
        return self.id

    def to_dict(self) -> Dict[str, Any]:
        d = super().to_dict()
        d.update(
            {
                "root_signature": self.root_signature,
                "occurrences": self.occurrences,
                "step_indices": list(self.step_indices),
                "devices": list(self.devices),
                "configs": list(self.configs),
                "checkpoints": list(self.checkpoints),
                "first_step": self.first_step,
                "last_step": self.last_step,
                "first_trace": self.first_trace,
                "last_trace": self.last_trace,
                "known": self.known,
            }
        )
        return d


def split_config(config_label: str) -> Tuple[str, str]:
    """`phone@pixel-4a` -> `("phone", "pixel-4a")`; a label without a device -> `(label, "")`."""
    config, _, device = (config_label or "").partition("@")
    return config, device


def sort_findings(findings: List[Any]) -> List[Any]:
    """Severity, then deterministic oracles before AI, then most frequent, then id."""

    def key(f: Any) -> Tuple[int, int, int, str]:
        return (
            SEVERITY_ORDER.get(f.severity.lower(), 4),
            1 if f.source.lower() in ("ai-review", "ai-explorer") else 0,
            -getattr(f, "occurrences", 1),
            f.id,
        )

    return sorted(findings, key=key)


def _step_number(checkpoint: str) -> Optional[int]:
    m = re.search(r"step_?(\d+)", checkpoint or "")
    return int(m.group(1)) if m else None


def deduplicate_findings(
    findings: Iterable[Finding],
    step_results: Optional[List[Any]] = None,
) -> List[AggregatedFinding]:
    """Collapse findings with one root signature into one `AggregatedFinding`.

    Severity and confidence escalate to the highest seen. The step behind a finding is looked
    up by `(finding.config, finding.checkpoint)`: step numbers repeat on every device.
    """
    steps: Dict[Tuple[str, str], Any] = {}
    for sr in step_results or []:
        steps[(sr.config_label, f"step_{sr.step_index:02d}")] = sr

    dedup: Dict[str, AggregatedFinding] = {}
    for f in findings:
        sr = steps.get((f.config, f.checkpoint))
        step_idx = sr.step_index if sr is not None else _step_number(f.checkpoint)
        config, device = split_config(f.config)
        trace = (
            {
                "step": step_idx,
                "config": f.config,
                "action": sr.action.summary() if sr is not None else "",
                "expectation": sr.expectation.description if sr is not None else "",
            }
            if step_idx is not None
            else None
        )

        agg = dedup.get(f.id)
        if agg is None:
            dedup[f.id] = AggregatedFinding(
                id=f.id, source=f.source, product=f.product, config=f.config, journey=f.journey,
                checkpoint=f.checkpoint, rule=f.rule, severity=f.severity, confidence=f.confidence,
                region=list(f.region) if f.region else None, message=f.message, evidence=list(f.evidence),
                new_since_last_run=f.new_since_last_run,
                step_indices=[step_idx] if step_idx is not None else [],
                devices=[device] if device else [],
                configs=[config] if config else [],
                checkpoints=[f.checkpoint] if f.checkpoint else [],
                first_step=step_idx, last_step=step_idx, first_trace=trace, last_trace=trace,
                raw_findings=[f],
            )
            continue

        agg.occurrences += 1
        agg.raw_findings.append(f)
        if step_idx is not None and step_idx not in agg.step_indices:
            agg.step_indices = sorted(agg.step_indices + [step_idx])
        if device and device not in agg.devices:
            agg.devices.append(device)
        if config and config not in agg.configs:
            agg.configs.append(config)
        if f.checkpoint and f.checkpoint not in agg.checkpoints:
            agg.checkpoints.append(f.checkpoint)
        agg.evidence.extend(e for e in f.evidence if e not in agg.evidence)
        if step_idx is not None:
            if agg.first_step is None or step_idx < agg.first_step:
                agg.first_step, agg.first_trace = step_idx, trace
            if agg.last_step is None or step_idx > agg.last_step:
                agg.last_step, agg.last_trace = step_idx, trace
        if SEVERITY_ORDER.get(f.severity.lower(), 4) < SEVERITY_ORDER.get(agg.severity.lower(), 4):
            agg.severity = f.severity
        if CONFIDENCE_ORDER.get(f.confidence.lower(), 3) < CONFIDENCE_ORDER.get(agg.confidence.lower(), 3):
            agg.confidence = f.confidence
        if f.region and not agg.region:
            agg.region = list(f.region)

    return sort_findings(list(dedup.values()))


# =====================================================================
# Rendering
# =====================================================================

def _render_thumbnail_data_uri(run_dir: Path, image_rel: str, region: Optional[List[float]] = None, max_edge: int = 360) -> str:
    """The evidence image (path relative to `run_dir`) as a small data URI, with `region` boxed."""
    img_path = run_dir / image_rel
    if not img_path.is_file():
        return ""
    try:
        from PIL import Image, ImageDraw

        with Image.open(img_path) as raw:
            img = raw.convert("RGB")
        w, h = img.size
        scale = min(1.0, float(max_edge) / float(max(w, h, 1)))
        nw, nh = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
        thumb = img.resize((nw, nh), resample=Image.Resampling.BILINEAR)
        if region and len(region) == 4 and region[2] > 0 and region[3] > 0:
            sx, sy = float(nw) / float(w), float(nh) / float(h)
            x0 = max(0, min(nw - 1, int(round(float(region[0]) * sx))))
            y0 = max(0, min(nh - 1, int(round(float(region[1]) * sy))))
            x1 = max(x0 + 1, min(nw - 1, int(round((float(region[0]) + float(region[2])) * sx))))
            y1 = max(y0 + 1, min(nh - 1, int(round((float(region[1]) + float(region[3])) * sy))))
            ImageDraw.Draw(thumb).rectangle((x0, y0, x1, y1), outline=(255, 0, 0), width=3)
        buf = io.BytesIO()
        thumb.save(buf, format="PNG")
        return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception as exc:  # noqa: BLE001 - the link to the full image stays
        logger.warning("No thumbnail for %s: %s", img_path, exc)
        return ""


def _worker_rows(summary: Any) -> List[Tuple[str, str, int, str]]:
    return [
        (w.target.label, w.status, w.total_steps, w.error or "")
        for w in getattr(summary, "worker_results", [])
    ]


def _step_line(sr: Any) -> Tuple[str, str, str, str, str, str]:
    findings = ", ".join(f.id for f in sr.findings) or "-"
    return (
        str(sr.step_index), sr.config_label or "-", sr.action.summary(), sr.expectation.description,
        sr.status.upper(), findings,
    )


def _cell(text: str, limit: int = 60) -> str:
    text = str(text).replace("|", "/").replace("\n", " ")
    return text if len(text) <= limit else text[: limit - 3] + "..."


def render_markdown_report(summary: Any, deduped: List[AggregatedFinding]) -> str:
    """Report as Markdown: run facts, devices, findings by severity, every step."""
    duration = max(0.0, summary.end_time - summary.start_time)
    open_findings = [f for f in deduped if not f.known]
    known = [f for f in deduped if f.known]
    lines: List[str] = [
        "# Exploratory Test Run Report",
        "",
        f"- Product: {summary.product}",
        f"- Run ID: {summary.run_id}",
        f"- Status: {summary.status}",
        f"- Duration: {duration:.0f}s",
        f"- Seed: {summary.seed}",
        f"- Steps: {summary.total_steps} ({summary.passed_steps} passed, {summary.failed_steps} failed or errored)",
        f"- Unique findings: {len(open_findings)} open, {len(known)} known (triaged)",
        "",
        "Findings are leads, not verdicts: a person triages them before anything is filed.",
        "",
        "## Devices",
        "",
        "| Target | Status | Steps | Detail |",
        "|--------|--------|-------|--------|",
    ]
    for target, status, steps, detail in _worker_rows(summary):
        lines.append(f"| {_cell(target)} | {status} | {steps} | {_cell(detail, 120)} |")
    for note in getattr(summary, "skipped_devices", []):
        lines.append(f"| - | not run | 0 | {_cell(note, 120)} |")
    lines.append("")

    def section(title: str, items: List[AggregatedFinding]) -> None:
        lines.extend([f"## {title}", ""])
        if not items:
            lines.extend(["_None._", ""])
            return
        for f in items:
            where = f" on {', '.join(f.devices)}" if f.devices else ""
            times = f" (x{f.occurrences})" if f.occurrences > 1 else ""
            lines.append(f"- **[{f.severity.upper()}] `{f.rule}`** (`{f.id}`){times}{where}: {f.message}")
            if f.first_trace:
                lines.append(f"  - First seen: step {f.first_trace['step']} -- {f.first_trace['action']}")
            if f.evidence:
                lines.append(f"  - Evidence: {', '.join(f.evidence[:6])}")
        lines.append("")

    section("Blockers and Majors", [f for f in open_findings if f.severity in BLOCKING_SEVERITIES])
    section("Minors and Polish", [f for f in open_findings if f.severity not in BLOCKING_SEVERITIES])
    if known:
        section("Known (triaged)", known)

    if summary.step_results:
        lines.extend(
            [
                "## Steps",
                "",
                "| Step | Config | Action | Expectation | Result | Findings |",
                "|------|--------|--------|-------------|--------|----------|",
            ]
        )
        for sr in summary.step_results:
            lines.append("| " + " | ".join(_cell(c) for c in _step_line(sr)) + " |")
        lines.append("")
    return "\n".join(lines)


_CSS = """
:root { --bg:#0f172a; --fg:#f8fafc; --card:#1e293b; --border:#334155; --pass:#10b981; --fail:#ef4444;
  --blocker:#dc2626; --major:#ea580c; --minor:#f59e0b; --polish:#64748b; }
@media (prefers-color-scheme: light) { :root { --bg:#f8fafc; --fg:#0f172a; --card:#fff; --border:#e2e8f0; } }
body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--fg); margin:0; padding:2rem; line-height:1.5; }
.container { max-width:1200px; margin:0 auto; }
.stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:1rem; margin:1.5rem 0; }
.stat, .finding { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:1rem; }
.stat b { display:block; font-size:2rem; }
.finding { margin-bottom:1rem; }
.badge { display:inline-block; padding:.2rem .5rem; border-radius:4px; font-size:.75rem; font-weight:bold; text-transform:uppercase; color:#fff; background:var(--polish); }
.badge-blocker { background:var(--blocker); } .badge-major { background:var(--major); }
.badge-minor { background:var(--minor); color:#000; } .badge-pass { background:var(--pass); }
.badge-fail, .badge-error { background:var(--fail); }
.thumbs { display:flex; gap:1rem; flex-wrap:wrap; margin-top:.5rem; }
.thumbs img { display:block; max-height:240px; width:auto; border:1px solid var(--border); background:#000; }
table { width:100%; border-collapse:collapse; margin:1rem 0; background:var(--card); }
th, td { padding:.5rem .75rem; border:1px solid var(--border); text-align:left; vertical-align:top; }
code { font-family: ui-monospace, Menlo, monospace; font-size:.9em; }
"""


def render_html_report(summary: Any, deduped: List[AggregatedFinding], run_dir: Path) -> str:
    """Self-contained HTML report; evidence images are linked relative to `run_dir`."""
    e = html.escape
    duration = max(0.0, summary.end_time - summary.start_time)
    open_findings = [f for f in deduped if not f.known]
    parts: List[str] = [
        "<!DOCTYPE html>", '<html lang="en"><head><meta charset="utf-8">',
        f"<title>Exploratory Test Report - {e(str(summary.run_id))}</title>", f"<style>{_CSS}</style></head>",
        '<body><div class="container">',
        f"<h1>Exploratory Test Report - <code>{e(str(summary.run_id))}</code></h1>",
        f"<p>Product: <strong>{e(summary.product)}</strong> &middot; Status: <strong>{e(summary.status)}</strong>"
        f" &middot; Duration: <strong>{duration:.0f}s</strong> &middot; Seed: <strong>{summary.seed}</strong></p>",
        '<div class="stats">',
        f'<div class="stat">Steps<b>{summary.total_steps}</b></div>',
        f'<div class="stat">Passed<b>{summary.passed_steps}</b></div>',
        f'<div class="stat">Failed or errored<b>{summary.failed_steps}</b></div>',
        f'<div class="stat">Open findings<b>{len(open_findings)}</b></div>',
        "</div>",
        "<p><em>Findings are leads, not verdicts: a person triages them before anything is filed.</em></p>",
        "<h2>Devices</h2><table><thead><tr><th>Target</th><th>Status</th><th>Steps</th><th>Detail</th></tr></thead><tbody>",
    ]
    for target, status, steps, detail in _worker_rows(summary):
        parts.append(f"<tr><td>{e(target)}</td><td>{e(status)}</td><td>{steps}</td><td>{e(detail)}</td></tr>")
    for note in getattr(summary, "skipped_devices", []):
        parts.append(f"<tr><td>-</td><td>not run</td><td>0</td><td>{e(note)}</td></tr>")
    parts.append("</tbody></table><h2>Findings</h2>")

    if not deduped:
        parts.append("<p><em>None.</em></p>")
    for f in deduped:
        sev = re.sub(r"[^a-z0-9_-]", "", f.severity.lower()) or "polish"
        known = " (known, triaged)" if f.known else ""
        times = f" &times;{f.occurrences}" if f.occurrences > 1 else ""
        where = f" on {e(', '.join(f.devices))}" if f.devices else ""
        parts.append(
            f'<div class="finding"><span class="badge badge-{sev}">{e(f.severity)}</span> '
            f"<strong><code>{e(f.rule)}</code></strong> <code>[{e(f.id)}]</code>{times}{where}{known}"
            f"<p>{e(f.message)}</p>"
        )
        thumbs = []
        for ev in f.evidence:
            if ev.lower().endswith(".png"):
                uri = _render_thumbnail_data_uri(run_dir, ev, f.region)
                if uri:
                    thumbs.append(f'<a href="{e(ev)}"><img src="{uri}" alt="{e(ev)}"></a>')
        if thumbs:
            parts.append('<div class="thumbs">' + "".join(thumbs) + "</div>")
        links = [f'<a href="{e(ev)}">{e(ev)}</a>' for ev in f.evidence if not ev.lower().endswith(".png")]
        if links:
            parts.append("<p>" + " &middot; ".join(links) + "</p>")
        parts.append("</div>")

    if summary.step_results:
        parts.append("<h2>Steps</h2><table><thead><tr><th>Step</th><th>Config</th><th>Action</th><th>Expectation</th><th>Result</th><th>Findings</th></tr></thead><tbody>")
        for sr in summary.step_results:
            step, config, action, expectation, result, findings = _step_line(sr)
            parts.append(
                f"<tr><td>{e(step)}</td><td>{e(config)}</td><td>{e(action)}</td><td>{e(expectation)}</td>"
                f'<td><span class="badge badge-{e(result.lower())}">{e(result)}</span></td><td><code>{e(findings)}</code></td></tr>'
            )
        parts.append("</tbody></table>")
    parts.append("</div></body></html>")
    return "\n".join(parts)


def generate_explore_report(
    summary: Any,
    known_ids: Optional[Set[str]] = None,
) -> Dict[str, Path]:
    """Write `findings.json`, `report.md` and `report.html` into the run directory.

    `known_ids` are root signatures a person has already triaged: they stay in the report,
    marked, and do not count as open. Nothing outside `summary.run_dir` is written.
    """
    run_dir = Path(summary.run_dir).expanduser().resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    deduped = deduplicate_findings(summary.findings, step_results=summary.step_results)
    for f in deduped:
        f.known = f.id in (known_ids or set())

    outputs = {
        "findings_json": ("findings.json", json.dumps([f.to_dict() for f in deduped], indent=2)),
        "report_md": ("report.md", render_markdown_report(summary, deduped)),
        "report_html": ("report.html", render_html_report(summary, deduped, run_dir)),
    }
    artifacts: Dict[str, Path] = {"run_dir": run_dir}
    for key, (name, text) in outputs.items():
        target = ensure_under(run_dir / name, run_dir)
        target.write_text(text, encoding="utf-8")
        artifacts[key] = target
    return artifacts
