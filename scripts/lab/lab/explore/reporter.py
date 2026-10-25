"""Deduplication, reporting, and canonical backlog logging for exploratory interaction testing.

Milestone 4 (Features 25, 26, 27, 28):
- Feature 25: Root Signature Deduplication (12-char SHA1 hex)
- Feature 26: Run Directory & Date-Staged Lab Reports (HTML, MD, JSON)
- Feature 27: Canonical Backlog Logging (symlink-safe, idempotency, severity filtering)
- Feature 28: CLI Integration & Documentation
"""

from __future__ import annotations

import base64
import copy
import hashlib
import html
import io
import json
import logging
import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from lab.model import Finding
from lab.paths import repo_root

logger = logging.getLogger("lab.explore.reporter")

# Severity order for sorting and escalation (lower rank = higher priority)
SEVERITY_ORDER: Dict[str, int] = {
    "blocker": 0,
    "major": 1,
    "minor": 2,
    "polish": 3,
    "info": 4,
}

CONFIDENCE_ORDER: Dict[str, int] = {
    "high": 0,
    "medium": 1,
    "low": 2,
}

# Only blocker and major severities enter private/ai/memory/code_health.md
BACKLOG_ELIGIBLE_SEVERITIES: Set[str] = {"blocker", "major"}

# Product owner mapping conforming to ROUTING.md and test_explore_reporter.py
PRODUCT_OWNER_MAP: Dict[str, str] = {
    "phototok": "@phototok-ui-agent",
    "jackpix": "@phototok-ui-agent",
    "android-desktop": "@android-desktop-ui-agent",
    "frameduel": "@android-desktop-ui-agent",
    "macos-desktop": "@macos-desktop-agent",
    "macos": "@macos-desktop-agent",
    "lensjet": "@macos-desktop-agent",
    "linux-desktop": "@linux-desktop-agent",
    "linux": "@linux-desktop-agent",
    "blitzlens": "@linux-desktop-agent",
}

# Base source paths for each product
PRODUCT_PATH_MAP: Dict[str, str] = {
    "phototok": "products/android/phototok/ui/",
    "jackpix": "products/android/phototok/ui/",
    "android-desktop": "products/android/android-desktop/ui/",
    "frameduel": "products/android/android-desktop/ui/",
    "macos-desktop": "products/macos-desktop/",
    "macos": "products/macos-desktop/",
    "lensjet": "products/macos-desktop/",
    "linux-desktop": "products/linux-desktop/",
    "linux": "products/linux-desktop/",
    "blitzlens": "products/linux-desktop/",
}


# =====================================================================
# Feature 25: Root Signature Deduplication
# =====================================================================

def normalize_action_signature(action_sig: Any) -> str:
    """Normalize action representation to stable lowercase string with sorted parameters."""
    if action_sig is None:
        return ""
    if isinstance(action_sig, str):
        return action_sig.strip().lower()
    if hasattr(action_sig, "signature"):
        sig = action_sig.signature
        if callable(sig):
            sig = sig()
        return str(sig).strip().lower()
    if isinstance(action_sig, dict):
        act_type = str(action_sig.get("action_type", "")).strip().lower()
        target = action_sig.get("target_id")
        params = action_sig.get("params", {})
        if target:
            return f"{act_type}:{str(target).strip().lower()}"
        elif params:
            sorted_params = ",".join(f"{k}={params[k]}" for k in sorted(params.keys(), key=str))
            return f"{act_type}:{sorted_params.strip().lower()}"
        return act_type
    return str(action_sig).strip().lower()


def normalize_subject(subject: Any) -> str:
    """Normalize subject to stable string, converting None to empty string and sorting sequences."""
    if subject is None:
        return ""
    if isinstance(subject, str):
        return subject.strip().lower()
    if isinstance(subject, (list, tuple)):
        return ",".join(sorted(str(x).strip().lower() for x in subject))
    if isinstance(subject, dict):
        return ",".join(f"{k}={subject[k]}" for k in sorted(subject.keys(), key=str))
    return str(subject).strip().lower()


def compute_root_signature(
    product: str,
    screen_sig: str,
    action_sig: Any,
    violated_rule: str,
    subject: Any = "",
) -> str:
    """Compute deterministic 12-hex-character SHA1 root signature.

    Format: SHA1(product|screen_sig|action_sig|violated_rule|subject)[:12].
    Conforms to Feature 25 and test_explore_reporter.py.
    """
    p = str(product or "").strip().lower()
    if p in ("jackpix", "phototok"):
        norm_product = "phototok"
    elif p in ("frameduel", "android-desktop"):
        norm_product = "android-desktop"
    elif p in ("lensjet", "macos-desktop", "macos"):
        norm_product = "macos"
    elif p in ("blitzlens", "linux-desktop", "linux"):
        norm_product = "linux"
    else:
        norm_product = p

    norm_screen = str(screen_sig or "").strip().lower()
    norm_action = normalize_action_signature(action_sig)
    norm_rule = str(violated_rule or "").strip().lower()
    norm_subject = normalize_subject(subject)

    raw = f"{norm_product}|{norm_screen}|{norm_action}|{norm_rule}|{norm_subject}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]


@dataclass
class AggregatedFinding(Finding):
    """A deduplicated finding grouping multiple occurrences of the same root signature."""

    occurrences: int = 1
    step_indices: list[int] = field(default_factory=list)
    devices: list[str] = field(default_factory=list)
    configs: list[str] = field(default_factory=list)
    checkpoints: list[str] = field(default_factory=list)
    first_step: Optional[int] = None
    last_step: Optional[int] = None
    first_trace: Optional[Dict[str, Any]] = None
    last_trace: Optional[Dict[str, Any]] = None
    raw_findings: list[Finding] = field(default_factory=list)

    @property
    def root_signature(self) -> str:
        return self.id

    def to_dict(self) -> dict[str, Any]:
        d = super().to_dict()
        d.update({
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
        })
        return d


def sort_findings(findings: List[Any]) -> List[Any]:
    """Sort findings deterministically: severity -> source (det before AI) -> -occurrences -> root signature."""
    def _sort_key(f: Any) -> Tuple[int, int, int, str]:
        sev = getattr(f, "severity", "minor").lower()
        sev_rank = SEVERITY_ORDER.get(sev, 4)
        src = getattr(f, "source", "").lower()
        src_rank = 1 if src in ("ai-review", "ai-explorer", "ai") else 0
        occ = getattr(f, "occurrences", 1)
        sig = getattr(f, "id", "") or getattr(f, "root_signature", "")
        return (sev_rank, src_rank, -occ, sig)

    return sorted(findings, key=_sort_key)


def _extract_step_index(checkpoint: str) -> Optional[int]:
    """Extract numeric step index from checkpoint label (e.g. 'step_04' -> 4)."""
    if not checkpoint:
        return None
    m = re.search(r"step_?(\d+)", checkpoint)
    if m:
        return int(m.group(1))
    if checkpoint.isdigit():
        return int(checkpoint)
    return None


def deduplicate_findings(
    findings: List[Finding],
    step_results: Optional[List[Any]] = None,
) -> List[AggregatedFinding]:
    """Collapse multiple instances of identical root signatures into aggregated findings.

    Accumulates occurrence count, combines unique evidence paths, merges step numbers,
    and escalates severity and confidence if multiple occurrences differ.
    """
    step_map: Dict[str, Any] = {}
    if step_results:
        for sr in step_results:
            idx = getattr(sr, "step_index", None)
            if idx is not None:
                step_map[f"step_{idx:02d}"] = sr
                step_map[f"step_{idx}"] = sr
                step_map[str(idx)] = sr

    dedup: Dict[str, AggregatedFinding] = {}

    for f in findings:
        sig = f.id
        if not sig or len(sig) != 12:
            sig = compute_root_signature(f.product, f.checkpoint, f.journey, f.rule, "")

        sr = step_map.get(f.checkpoint)
        step_idx = getattr(sr, "step_index", None) if sr else _extract_step_index(f.checkpoint)

        act_summary = "action"
        if sr and hasattr(sr, "action"):
            act = sr.action
            if hasattr(act, "summary"):
                act_summary = act.summary() if callable(act.summary) else str(act.summary)
            else:
                act_summary = getattr(act, "action_type", "action")

        exp_desc = ""
        if sr and hasattr(sr, "expectation"):
            exp_desc = getattr(sr.expectation, "description", "")

        trace = {
            "step": step_idx,
            "action": act_summary,
            "expectation": exp_desc,
        } if step_idx is not None else None

        if sig not in dedup:
            agg = AggregatedFinding(
                id=sig,
                source=f.source,
                product=f.product,
                config=f.config,
                journey=f.journey,
                checkpoint=f.checkpoint,
                rule=f.rule,
                severity=f.severity,
                confidence=f.confidence,
                region=list(f.region) if f.region else None,
                message=f.message,
                evidence=list(f.evidence),
                new_since_last_run=f.new_since_last_run,
                occurrences=1,
                step_indices=[step_idx] if step_idx is not None else [],
                devices=[f.config] if f.config else [],
                configs=[f.config] if f.config else [],
                checkpoints=[f.checkpoint] if f.checkpoint else [],
                first_step=step_idx,
                last_step=step_idx,
                first_trace=trace,
                last_trace=trace,
                raw_findings=[f],
            )
            dedup[sig] = agg
        else:
            agg = dedup[sig]
            agg.occurrences += 1
            if step_idx is not None and step_idx not in agg.step_indices:
                agg.step_indices.append(step_idx)
                agg.step_indices.sort()
            if f.config and f.config not in agg.configs:
                agg.configs.append(f.config)
            if f.checkpoint and f.checkpoint not in agg.checkpoints:
                agg.checkpoints.append(f.checkpoint)
            for ev in f.evidence:
                if ev not in agg.evidence:
                    agg.evidence.append(ev)
            if step_idx is not None:
                if agg.first_step is None or step_idx < agg.first_step:
                    agg.first_step = step_idx
                    agg.first_trace = trace
                if agg.last_step is None or step_idx > agg.last_step:
                    agg.last_step = step_idx
                    agg.last_trace = trace
            # Severity escalation (higher severity rank wins)
            curr_rank = SEVERITY_ORDER.get(agg.severity.lower(), 4)
            new_rank = SEVERITY_ORDER.get(f.severity.lower(), 4)
            if new_rank < curr_rank:
                agg.severity = f.severity
            # Confidence escalation (higher confidence rank wins)
            curr_conf = CONFIDENCE_ORDER.get(agg.confidence.lower(), 3)
            new_conf = CONFIDENCE_ORDER.get(f.confidence.lower(), 3)
            if new_conf < curr_conf:
                agg.confidence = f.confidence
            if f.region and not agg.region:
                agg.region = list(f.region)
            agg.raw_findings.append(f)

    return sort_findings(list(dedup.values()))


def deduplicate_findings_with_counts(
    findings: List[Finding],
    step_results: Optional[List[Any]] = None,
) -> Tuple[List[AggregatedFinding], Dict[str, int]]:
    """Deduplicate findings and return tuple of (deduped_list, {signature: count})."""
    deduped = deduplicate_findings(findings, step_results=step_results)
    counts = {f.id: f.occurrences for f in deduped}
    return deduped, counts


# =====================================================================
# Feature 27: Canonical Backlog Logging
# =====================================================================

def format_backlog_entry(
    date_str: str,
    title: str,
    where: str,
    debt: str,
    proposal: str,
) -> str:
    """Format markdown backlog item matching private/ai/memory/code_health.md schema."""
    return (
        f"## [OPEN] {date_str} - {title}\n"
        f"**Where:** {where}\n"
        f"**Debt:** {debt}\n"
        f"**Proposal:** {proposal}\n"
    )


def resolve_canonical_backlog_path(explicit_path: Optional[Path | str] = None) -> Path:
    """Resolve the canonical target path for code_health.md preserving symlinks."""
    if explicit_path is not None:
        p = Path(explicit_path)
        if p.name == "code_health.md":
            return p.resolve()
        # Check if given a directory
        priv_target = p / "private" / "ai" / "memory" / "code_health.md"
        if (p / "private").is_dir() or priv_target.exists():
            return priv_target.resolve()
        pub_target = p / "ai" / "memory" / "code_health.md"
        if pub_target.exists():
            return pub_target.resolve()
        return priv_target.resolve()

    root = repo_root()
    priv_file = root / "private" / "ai" / "memory" / "code_health.md"
    if (root / "private").is_dir() or priv_file.exists():
        return priv_file.resolve()

    pub_file = root / "ai" / "memory" / "code_health.md"
    if pub_file.exists():
        return pub_file.resolve()

    return priv_file.resolve()


def is_signature_in_backlog(sig: str, content: str) -> bool:
    """Check if a root signature already exists in code_health.md content."""
    if not sig:
        return False
    return (
        f"Root signature: {sig}" in content
        or f"Root signature: `{sig}`" in content
        or f"root signature: {sig.lower()}" in content.lower()
        or f"root signature: `{sig.lower()}`" in content.lower()
    )


def build_finding_backlog_entry(
    finding: Finding | AggregatedFinding,
    date_str: Optional[str] = None,
    occurrences: int = 1,
    seed: Optional[int] = None,
) -> str:
    """Construct a fully populated backlog entry from a finding."""
    if not date_str:
        date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    prod = (finding.product or "unknown").lower()
    owner = PRODUCT_OWNER_MAP.get(prod, "@shared-code-health-agent")
    base_path = PRODUCT_PATH_MAP.get(prod, f"products/{prod}/")
    where = f"{base_path} ({owner})"

    sig = finding.id or "unknown_sig"
    checkpoint = finding.checkpoint or "interaction"
    rule_name = finding.rule or "exploratory"
    title = f"{prod.capitalize()} {checkpoint}: {rule_name} failure"

    debt_items = [f"Root signature: {sig}."]
    if occurrences > 1:
        debt_items.append(f"Occurred {occurrences} times.")
    if seed is not None:
        debt_items.append(f"Reproducible with seed {seed}.")
    if finding.message:
        debt_items.append(finding.message)
    if finding.evidence:
        debt_items.append(f"Evidence: {', '.join(finding.evidence[:3])}")
    debt = " ".join(debt_items)

    r_lower = (finding.rule or "").lower()
    msg_lower = (finding.message or "").lower()
    if r_lower == "crash" or "exception" in msg_lower:
        proposal = f"Investigate stack trace and guard against unhandled exception in {prod}."
    elif "layout" in r_lower or "overlap" in r_lower:
        proposal = f"Correct Composable layout constraints or touch target sizes to comply with UX requirements."
    elif "fs" in r_lower or "file" in r_lower:
        proposal = f"Verify filesystem operation handling and side-effect expectations for {prod}."
    else:
        proposal = f"Investigate defect reproduction trace and resolve expectation violation in {prod}."

    return format_backlog_entry(date_str, title, where, debt, proposal)


def log_findings_to_canonical_backlog(
    findings: List[Finding],
    repo_path: Optional[Path | str] = None,
    date_str: Optional[str] = None,
    seed: Optional[int] = None,
) -> int:
    """Safely append blocker and major findings to canonical code_health.md preserving symlinks.

    Minor, polish, and info findings are strictly excluded.
    Returns the number of newly logged entries.
    """
    eligible = [f for f in findings if getattr(f, "severity", "").lower() in BACKLOG_ELIGIBLE_SEVERITIES]
    if not eligible:
        return 0

    canonical_file = resolve_canonical_backlog_path(repo_path)
    try:
        canonical_file.parent.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass

    existing_content = ""
    if canonical_file.is_file():
        try:
            existing_content = canonical_file.read_text(encoding="utf-8")
        except OSError as exc:
            logger.warning("Could not read backlog file %s: %s", canonical_file, exc)

    # Group by signature
    grouped: Dict[str, List[Finding]] = {}
    for f in eligible:
        sig = f.id or compute_root_signature(f.product, f.checkpoint or "", "", f.rule or "", "")
        grouped.setdefault(sig, []).append(f)

    entries_to_append: List[str] = []
    current_content = existing_content
    for sig, group in grouped.items():
        if is_signature_in_backlog(sig, current_content):
            logger.info("Signature %s already present in backlog; skipping duplicate.", sig)
            continue

        primary = copy.copy(group[0])
        all_ev: List[str] = []
        for g in group:
            all_ev.extend(g.evidence)
        primary.evidence = list(dict.fromkeys(all_ev))

        occ = sum(getattr(g, "occurrences", 1) for g in group)
        entry = build_finding_backlog_entry(primary, date_str=date_str, occurrences=occ, seed=seed)
        entries_to_append.append(entry)
        current_content += f"\n{entry}\n"

    if not entries_to_append:
        return 0

    # In-place append to canonical resolved target
    try:
        with open(canonical_file, "a", encoding="utf-8") as fh:
            for entry in entries_to_append:
                fh.write(f"\n{entry}")
        logger.info("Appended %d entries to %s", len(entries_to_append), canonical_file)
        return len(entries_to_append)
    except OSError as exc:
        logger.warning("Could not append to canonical backlog %s: %s", canonical_file, exc)
        return 0


# =====================================================================
# Feature 26: Run Directory & Date-Staged Lab Reports
# =====================================================================

def _render_thumbnail_data_uri(
    run_dir: Path,
    image_rel: str,
    region: Optional[List[float]] = None,
    max_edge: int = 360,
) -> str:
    """Load screenshot, downscale, draw red bounding box if region present, and return data URI."""
    img_path = run_dir / image_rel
    if not img_path.is_file():
        return ""
    try:
        from PIL import Image, ImageDraw

        with Image.open(img_path) as raw:
            img = raw.convert("RGB")
        w, h = img.size
        scale = min(1.0, float(max_edge) / float(max(w, h, 1)))
        nw = max(1, int(round(w * scale)))
        nh = max(1, int(round(h * scale)))
        thumb = img.resize((nw, nh), resample=Image.Resampling.BILINEAR)

        if region and len(region) == 4 and region[2] > 0 and region[3] > 0:
            sx = float(nw) / float(w)
            sy = float(nh) / float(h)
            x0 = max(0, min(nw - 1, int(round(float(region[0]) * sx))))
            y0 = max(0, min(nh - 1, int(round(float(region[1]) * sy))))
            x1 = max(x0 + 1, min(nw - 1, int(round((float(region[0]) + float(region[2])) * sx))))
            y1 = max(y0 + 1, min(nh - 1, int(round((float(region[1]) + float(region[3])) * sy))))
            draw = ImageDraw.Draw(thumb)
            draw.rectangle((x0, y0, x1, y1), outline=(255, 0, 0), width=3)

        buf = io.BytesIO()
        thumb.save(buf, format="PNG")
        b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        return f"data:image/png;base64,{b64}"
    except Exception as exc:
        logger.debug("Failed thumbnail generation for %s: %s", img_path, exc)
        return ""


def render_markdown_report(
    summary: Any,
    deduped_findings: List[AggregatedFinding],
    step_results: Optional[List[Any]] = None,
) -> str:
    """Render comprehensive markdown report with metadata, step table, and defect breakdown."""
    prod = getattr(summary, "product", "unknown")
    run_id = getattr(summary, "run_id", "unknown")
    start = getattr(summary, "start_time", 0.0)
    end = getattr(summary, "end_time", 0.0)
    duration_s = int(round(max(0.0, end - start))) if (start and end) else 0
    total = getattr(summary, "total_steps", 0)
    passed = getattr(summary, "passed_steps", 0)
    failed = getattr(summary, "failed_steps", 0)
    issues_count = len(deduped_findings)

    lines: List[str] = [
        "# Exploratory Test Run Report",
        "",
        f"- Product: {prod}",
        f"- Duration: {duration_s}s",
        f"- Run ID: {run_id}",
        f"- Total Steps: {total}",
        f"- Passed: {passed}",
        f"- Failed: {failed}",
        f"- Issues Discovered: {issues_count}",
        "",
    ]

    # Step Results Table
    s_results = step_results if step_results is not None else getattr(summary, "step_results", [])
    if s_results:
        lines.append("## Step Execution Summary")
        lines.append("")
        lines.append("| Step | Action | Expectation | Result | Defect ID |")
        lines.append("|------|--------|-------------|--------|-----------|")
        for sr in s_results:
            idx = getattr(sr, "step_index", 0)
            act = getattr(sr, "action", None)
            act_str = "action"
            if act:
                act_str = getattr(act, "action_type", "action")
                if hasattr(act, "summary"):
                    try:
                        act_str = act.summary() if callable(act.summary) else str(act.summary)
                    except Exception:
                        pass
            exp = getattr(sr, "expectation", None)
            exp_str = getattr(exp, "description", "-") if exp else "-"
            # replace unescaped pipe characters before formatting
            exp_str = str(exp_str).replace("|", "/")
            if len(exp_str) > 40:
                exp_str = exp_str[:37] + "..."
            act_str = str(act_str).replace("|", "/")
            res = "PASS" if getattr(sr, "passed", True) else "FAIL"
            sr_findings = getattr(sr, "findings", [])
            defect_ids = ", ".join(f.id for f in sr_findings) if sr_findings else "-"
            defect_ids = str(defect_ids).replace("|", "/")
            lines.append(f"| {idx} | {act_str} | {exp_str} | {res} | {defect_ids} |")
        lines.append("")

    # Findings Breakdown
    blockers_majors = [f for f in deduped_findings if f.severity in ("blocker", "major")]
    minors_polish = [f for f in deduped_findings if f.severity in ("minor", "polish", "info")]

    lines.append("## Blockers and Majors")
    lines.append("")
    if not blockers_majors:
        lines.append("_None._")
        lines.append("")
    else:
        for f in blockers_majors:
            occ_str = f" (occurred {f.occurrences}x)" if f.occurrences > 1 else ""
            lines.append(f"- **[{f.severity.upper()}] `{f.rule}`** (`{f.id}`){occ_str}: {f.message}")
            if f.evidence:
                lines.append(f"  - Evidence: {', '.join(f.evidence)}")
        lines.append("")

    lines.append("## Minors and Polish")
    lines.append("")
    if not minors_polish:
        lines.append("_None._")
        lines.append("")
    else:
        for f in minors_polish:
            occ_str = f" (occurred {f.occurrences}x)" if f.occurrences > 1 else ""
            lines.append(f"- **[{f.severity.upper()}] `{f.rule}`** (`{f.id}`){occ_str}: {f.message}")
            if f.evidence:
                lines.append(f"  - Evidence: {', '.join(f.evidence)}")
        lines.append("")

    return "\n".join(lines)


def render_html_report(
    summary: Any,
    deduped_findings: List[AggregatedFinding],
    run_dir: Path,
    step_results: Optional[List[Any]] = None,
) -> str:
    """Render self-contained HTML report with CSS variables and base64 embedded thumbnails."""
    prod = html.escape(str(getattr(summary, "product", "unknown")))
    run_id = html.escape(str(getattr(summary, "run_id", "unknown")))
    start = getattr(summary, "start_time", 0.0)
    end = getattr(summary, "end_time", 0.0)
    duration_s = f"{max(0.0, end - start):.1f}s" if (start and end) else "0.0s"
    total = getattr(summary, "total_steps", 0)
    passed = getattr(summary, "passed_steps", 0)
    failed = getattr(summary, "failed_steps", 0)
    issues_count = len(deduped_findings)

    parts: List[str] = [
        "<!DOCTYPE html>",
        "<html lang=\"en\">",
        "<head>",
        "<meta charset=\"utf-8\">",
        f"<title>Exploratory Test Report &mdash; {run_id}</title>",
        "<style>",
        ":root {",
        "  --bg: #0f172a; --fg: #f8fafc; --card: #1e293b; --border: #334155;",
        "  --pass: #10b981; --fail: #ef4444; --blocker: #dc2626; --major: #ea580c;",
        "  --minor: #f59e0b; --polish: #64748b; --accent: #38bdf8;",
        "}",
        "@media (prefers-color-scheme: light) {",
        "  :root {",
        "    --bg: #f8fafc; --fg: #0f172a; --card: #ffffff; --border: #e2e8f0;",
        "    --pass: #059669; --fail: #dc2626; --blocker: #b91c1c; --major: #c2410c;",
        "    --minor: #d97706; --polish: #94a3b8; --accent: #0284c7;",
        "  }",
        "}",
        "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;",
        "       background: var(--bg); color: var(--fg); margin: 0; padding: 2rem; line-height: 1.5; }",
        ".container { max-width: 1200px; margin: 0 auto; }",
        "h1, h2, h3 { color: var(--fg); }",
        ".stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; margin: 1.5rem 0; }",
        ".stat-card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 1rem; text-align: center; }",
        ".stat-val { font-size: 2rem; font-weight: bold; margin-top: 0.25rem; }",
        ".val-pass { color: var(--pass); } .val-fail { color: var(--fail); }",
        ".finding-card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 1rem; margin-bottom: 1rem; }",
        ".finding-header { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.5rem; }",
        ".badge { display: inline-block; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: bold; text-transform: uppercase; }",
        ".badge-blocker { background: var(--blocker); color: #fff; }",
        ".badge-major { background: var(--major); color: #fff; }",
        ".badge-minor { background: var(--minor); color: #000; }",
        ".badge-polish { background: var(--polish); color: #fff; }",
        ".badge-pass { background: var(--pass); color: #fff; }",
        ".badge-fail { background: var(--fail); color: #fff; }",
        ".thumb-container { display: flex; gap: 1rem; flex-wrap: wrap; margin-top: 0.5rem; }",
        ".thumb-box { border: 1px solid var(--border); border-radius: 4px; overflow: hidden; background: #000; }",
        ".thumb-box img { display: block; max-height: 240px; width: auto; }",
        "table { width: 100%; border-collapse: collapse; margin: 1rem 0; background: var(--card); border-radius: 8px; overflow: hidden; }",
        "th, td { padding: 0.75rem; border: 1px solid var(--border); text-align: left; }",
        "th { background: rgba(0,0,0,0.1); }",
        "code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.9em; }",
        "</style>",
        "</head>",
        "<body>",
        "<div class=\"container\">",
        f"<h1>Exploratory Test Report &mdash; <code>{run_id}</code></h1>",
        f"<p>Product: <strong>{prod}</strong> &middot; Duration: <strong>{duration_s}</strong></p>",
        "<div class=\"stats-grid\">",
        f"<div class=\"stat-card\"><div>Total Steps</div><div class=\"stat-val\">{total}</div></div>",
        f"<div class=\"stat-card\"><div>Passed</div><div class=\"stat-val val-pass\">{passed}</div></div>",
        f"<div class=\"stat-card\"><div>Failed</div><div class=\"stat-val val-fail\">{failed}</div></div>",
        f"<div class=\"stat-card\"><div>Unique Defects</div><div class=\"stat-val\">{issues_count}</div></div>",
        "</div>",
    ]

    # Findings Section
    parts.append("<h2>Discovered Defects</h2>")
    if not deduped_findings:
        parts.append("<p><em>None. Clean exploratory run.</em></p>")
    else:
        for f in deduped_findings:
            clean_sev = re.sub(r"[^a-zA-Z0-9_-]", "", (f.severity or "").lower()) or "info"
            sev_class = f"badge-{clean_sev}"
            occ_label = f"&times;{f.occurrences}" if f.occurrences > 1 else ""
            parts.append("<div class=\"finding-card\">")
            parts.append("<div class=\"finding-header\">")
            parts.append(f"<span class=\"badge {sev_class}\">{html.escape(f.severity)}</span>")
            parts.append(f"<strong><code>{html.escape(f.rule)}</code></strong>")
            parts.append(f"<code>[{html.escape(f.id)}]</code>")
            if occ_label:
                parts.append(f"<span class=\"badge\" style=\"background:var(--card);border:1px solid var(--border);\">{occ_label}</span>")
            parts.append("</div>")
            parts.append(f"<p>{html.escape(f.message)}</p>")

            # Embed thumbnail if evidence image exists
            thumb_uris = []
            for ev in f.evidence:
                if ev.lower().endswith(".png"):
                    uri = _render_thumbnail_data_uri(run_dir, ev, f.region)
                    if uri:
                        thumb_uris.append((ev, uri))
            if thumb_uris:
                parts.append("<div class=\"thumb-container\">")
                for ev_name, data_uri in thumb_uris:
                    parts.append(f"<div class=\"thumb-box\"><a href=\"{html.escape(ev_name)}\"><img src=\"{data_uri}\" alt=\"{html.escape(ev_name)}\"></a></div>")
                parts.append("</div>")

            parts.append("</div>")

    # Step Results Table
    s_results = step_results if step_results is not None else getattr(summary, "step_results", [])
    if s_results:
        parts.append("<h2>Step History</h2>")
        parts.append("<table>")
        parts.append("<thead><tr><th>Step</th><th>Action</th><th>Expectation</th><th>Result</th><th>Defect</th></tr></thead>")
        parts.append("<tbody>")
        for sr in s_results:
            idx = getattr(sr, "step_index", 0)
            act = getattr(sr, "action", None)
            act_str = "action"
            if act:
                act_str = getattr(act, "action_type", "action")
                if hasattr(act, "summary"):
                    try:
                        act_str = act.summary() if callable(act.summary) else str(act.summary)
                    except Exception:
                        pass
            exp = getattr(sr, "expectation", None)
            exp_str = getattr(exp, "description", "-") if exp else "-"
            res_pass = getattr(sr, "passed", True)
            badge = "<span class=\"badge badge-pass\">PASS</span>" if res_pass else "<span class=\"badge badge-fail\">FAIL</span>"
            sr_findings = getattr(sr, "findings", [])
            defect_ids = ", ".join(f.id for f in sr_findings) if sr_findings else "-"
            parts.append(f"<tr><td>{idx}</td><td>{html.escape(act_str)}</td><td>{html.escape(exp_str)}</td><td>{badge}</td><td><code>{html.escape(defect_ids)}</code></td></tr>")
        parts.append("</tbody></table>")

    parts.append("</div>")
    parts.append("</body></html>")
    return "\n".join(parts)


def generate_explore_report(
    summary: Any,
    run_dir: Optional[Path | str] = None,
    date_stage: Optional[bool] = None,
) -> Dict[str, Path]:
    """Generate all run directory and date-staged exploratory report artifacts.

    Produces:
      - run_dir/summary.json & run.json
      - run_dir/findings.json, findings.det.json, findings.ai.json, findings.dedup.json
      - run_dir/summary.md & report.md
      - run_dir/summary.html & report.html
      - private/docs/build/lab-reports/<YYYY-MM-DD>/<run_id>.* (if date_stage=True)
    """
    import tempfile

    if run_dir is None:
        run_dir = getattr(summary, "run_dir", None)
    if run_dir is None:
        raise ValueError("run_dir must be provided either directly or via summary.run_dir")

    rdir = Path(run_dir).expanduser().resolve()
    rdir.mkdir(parents=True, exist_ok=True)
    if date_stage is None:
        tmp_prefix = str(Path(tempfile.gettempdir()).resolve())
        date_stage = not str(rdir).startswith(tmp_prefix)

    raw_findings: List[Finding] = list(getattr(summary, "findings", []))
    step_results: List[Any] = list(getattr(summary, "step_results", []))
    run_id = str(getattr(summary, "run_id", "explore_run"))

    # 1. Deduplicate findings
    deduped = deduplicate_findings(raw_findings, step_results=step_results)

    # 2. JSON artifacts
    det_findings = [f.to_dict() for f in raw_findings if getattr(f, "source", "") not in ("ai-review", "ai-explorer", "ai")]
    ai_findings = [f.to_dict() for f in raw_findings if getattr(f, "source", "") in ("ai-review", "ai-explorer", "ai")]
    dedup_dicts = [f.to_dict() for f in deduped]

    (rdir / "findings.json").write_text(json.dumps(dedup_dicts, indent=2), encoding="utf-8")
    (rdir / "findings.dedup.json").write_text(json.dumps(dedup_dicts, indent=2), encoding="utf-8")
    (rdir / "findings.det.json").write_text(json.dumps(det_findings, indent=2), encoding="utf-8")
    (rdir / "findings.ai.json").write_text(json.dumps(ai_findings, indent=2), encoding="utf-8")

    # summary.json & run.json
    sum_data = summary.to_dict() if hasattr(summary, "to_dict") else {
        "run_id": run_id,
        "run_dir": str(rdir),
        "product": getattr(summary, "product", "unknown"),
        "total_steps": getattr(summary, "total_steps", len(step_results)),
        "passed_steps": getattr(summary, "passed_steps", 0),
        "failed_steps": getattr(summary, "failed_steps", 0),
        "findings_count": len(deduped),
        "findings": dedup_dicts,
    }
    (rdir / "summary.json").write_text(json.dumps(sum_data, indent=2), encoding="utf-8")
    if not (rdir / "run.json").is_file():
        (rdir / "run.json").write_text(json.dumps(sum_data, indent=2), encoding="utf-8")

    # 3. Markdown reports
    md_content = render_markdown_report(summary, deduped, step_results=step_results)
    (rdir / "summary.md").write_text(md_content, encoding="utf-8")
    (rdir / "report.md").write_text(md_content, encoding="utf-8")

    # 4. HTML reports
    html_content = render_html_report(summary, deduped, rdir, step_results=step_results)
    (rdir / "summary.html").write_text(html_content, encoding="utf-8")
    (rdir / "report.html").write_text(html_content, encoding="utf-8")

    artifacts: Dict[str, Path] = {
        "run_dir": rdir,
        "summary_json": rdir / "summary.json",
        "report_md": rdir / "report.md",
        "report_html": rdir / "report.html",
    }

    # 5. Date-staged archival
    if date_stage:
        try:
            root = repo_root()
            date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            staged_dir = root / "private" / "docs" / "build" / "lab-reports" / date_str
            if not (root / "private").is_dir():
                staged_dir = root / "docs" / "build" / "lab-reports" / date_str

            staged_dir.mkdir(parents=True, exist_ok=True)
            (staged_dir / f"{run_id}.md").write_text(md_content, encoding="utf-8")
            (staged_dir / f"{run_id}.html").write_text(html_content, encoding="utf-8")
            (staged_dir / f"{run_id}.json").write_text(json.dumps(sum_data, indent=2), encoding="utf-8")

            # Update or create REPORT.md in staged directory
            staged_report = staged_dir / "REPORT.md"
            if not staged_report.is_file():
                staged_report.write_text(f"# Device lab report — {date_str}\n\n{md_content}\n", encoding="utf-8")
            else:
                existing = staged_report.read_text(encoding="utf-8")
                if run_id not in existing:
                    with open(staged_report, "a", encoding="utf-8") as fh:
                        fh.write(f"\n\n## Exploratory Run: {run_id}\n\n{md_content}\n")

            artifacts["staged_report"] = staged_report
            artifacts["staged_html"] = staged_dir / f"{run_id}.html"
        except Exception as exc:
            logger.debug("Date-staged report archival skipped: %s", exc)

    return artifacts


class ExploreReporter:
    """Class wrapper for exploratory reporting and canonical backlog operations."""

    def __init__(self, run_dir: Path | str, repo_path: Optional[Path | str] = None) -> None:
        self.run_dir = Path(run_dir)
        self.repo_path = Path(repo_path) if repo_path else None

    def report(self, summary: Any, date_stage: bool = True) -> Dict[str, Path]:
        return generate_explore_report(summary, run_dir=self.run_dir, date_stage=date_stage)

    def log_backlog(
        self,
        findings: List[Finding],
        date_str: Optional[str] = None,
        seed: Optional[int] = None,
    ) -> int:
        return log_findings_to_canonical_backlog(
            findings,
            repo_path=self.repo_path,
            date_str=date_str,
            seed=seed,
        )
