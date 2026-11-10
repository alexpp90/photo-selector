"""Report generator producing `report.md` and self-contained `report.html` for a lab run."""

from __future__ import annotations

import base64
import html
import io
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

from .model import Finding


TEMPLATE_PATH = Path(__file__).resolve().parent.parent / "templates" / "report.html"


def render_thumbnail_with_region(
    image_path: Path,
    region: list[float] | None,
    window_w: float | None = None,
    window_h: float | None = None,
    max_edge: int = 360,
) -> Image.Image:
    """Resize `image_path` so its long edge is <= `max_edge` and draw `region` in red."""
    with Image.open(image_path) as raw_img:
        img = raw_img.convert("RGB")
    orig_w, orig_h = img.size
    scale = min(1.0, float(max_edge) / float(max(orig_w, orig_h, 1)))
    new_w = max(1, int(round(orig_w * scale)))
    new_h = max(1, int(round(orig_h * scale)))
    thumb = img.resize((new_w, new_h), resample=Image.Resampling.BILINEAR)

    if region is not None and len(region) == 4 and region[2] > 0 and region[3] > 0:
        ref_w = float(window_w) if window_w and window_w > 0 else float(orig_w)
        ref_h = float(window_h) if window_h and window_h > 0 else float(orig_h)
        sx = float(new_w) / ref_w
        sy = float(new_h) / ref_h
        x0 = max(0, min(new_w - 1, int(round(float(region[0]) * sx))))
        y0 = max(0, min(new_h - 1, int(round(float(region[1]) * sy))))
        x1 = max(x0 + 1, min(new_w - 1, int(round((float(region[0]) + float(region[2])) * sx))))
        y1 = max(y0 + 1, min(new_h - 1, int(round((float(region[1]) + float(region[3])) * sy))))
        draw = ImageDraw.Draw(thumb)
        draw.rectangle((x0, y0, x1, y1), outline=(255, 0, 0), width=3)

    return thumb


def _thumb_to_data_uri(thumb: Image.Image) -> str:
    buf = io.BytesIO()
    thumb.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return f"data:image/png;base64,{b64}"


def _load_json_list(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError):
        return []


def _load_window_dims(run_dir: Path, evidence_rel: str) -> tuple[float | None, float | None]:
    png_path = run_dir / evidence_rel
    tree_path = png_path.with_suffix(".tree.json")
    if tree_path.is_file():
        try:
            tdata = json.loads(tree_path.read_text(encoding="utf-8"))
            win = tdata.get("window", {})
            return float(win.get("w", 0)) or None, float(win.get("h", 0)) or None
        except (OSError, json.JSONDecodeError, ValueError):
            pass
    return None, None


def _load_known_ids(run_dir: Path) -> set[str]:
    known_file = run_dir / "known_ids.json"
    if known_file.is_file():
        try:
            return {str(x) for x in json.loads(known_file.read_text(encoding="utf-8"))}
        except (OSError, json.JSONDecodeError):
            pass
    return set()


def _collect_timings(run_dir: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for tfile in sorted(run_dir.rglob("*.timing.json")):
        try:
            rel = tfile.relative_to(run_dir).parts
            if len(rel) >= 4:
                product, config, journey = rel[0], rel[1], rel[2]
            else:
                product, config, journey = "unknown", "default", "manual"
            tdata = json.loads(tfile.read_text(encoding="utf-8"))
            if not isinstance(tdata, dict) or ("t_ms" not in tdata and "step_ms" not in tdata):
                # Lensjet's `<stem>.timing.json` holds app metrics (first_photo_drawn_ms, …), not
                # checkpoint step times; as a row it read "01_opened.timing | 0 | 0" (2026-11-09).
                continue
            rows.append(
                {
                    "product": product,
                    "config": config,
                    "journey": journey,
                    "checkpoint": str(tdata.get("checkpoint", tfile.stem)),
                    "t_ms": int(tdata.get("t_ms", 0)),
                    "step_ms": int(tdata.get("step_ms", 0)),
                }
            )
        except (OSError, json.JSONDecodeError, ValueError):
            continue
    return rows


def generate_report(run_dir: Path | str) -> tuple[Path, Path]:
    """Generate `report.md` and `report.html` inside `run_dir` in the required section order."""
    rdir = Path(run_dir).expanduser().resolve()
    run_json_path = rdir / "run.json"
    run_meta: dict[str, Any] = {}
    if run_json_path.is_file():
        run_meta = json.loads(run_json_path.read_text(encoding="utf-8"))

    started = str(run_meta.get("started", "unknown"))
    finished = str(run_meta.get("finished", "unknown"))
    commit = str(run_meta.get("commit", "unknown"))
    duration_str = "n/a"
    try:
        dt_start = datetime.fromisoformat(started.replace("Z", "+00:00"))
        dt_end = datetime.fromisoformat(finished.replace("Z", "+00:00"))
        duration_str = f"{(dt_end - dt_start).total_seconds():.1f}s"
    except ValueError:
        pass

    det_raw = _load_json_list(rdir / "findings.det.json")
    ai_raw = _load_json_list(rdir / "findings.ai.json")
    all_raw = det_raw + ai_raw
    all_findings = [Finding.from_dict(item) for item in all_raw]
    known_ids = _load_known_ids(rdir)

    active_findings = [f for f in all_findings if f.id not in known_ids]

    def _is_det(f: Finding) -> bool:
        return f.source not in ("ai-review", "ai-explorer")

    new_blockers_majors = [
        f
        for f in active_findings
        if f.new_since_last_run and f.severity in ("blocker", "major")
    ]
    # Deterministic first, then AI
    new_blockers_majors.sort(
        key=lambda f: (0 if _is_det(f) else 1, 0 if f.severity == "blocker" else 1, f.id)
    )

    journeys = run_meta.get("journeys", [])
    # A journey that raised (adb dropped, app crashed the runner) is as broken as a failed flow.
    broken_flows = [j for j in journeys if j.get("result") in ("flow-broken", "error")]

    new_minors_polish = [
        f
        for f in active_findings
        if f.new_since_last_run and f.severity in ("minor", "polish")
    ]
    new_minors_polish.sort(key=lambda f: (0 if _is_det(f) else 1, f.id))

    timings = _collect_timings(rdir)

    products_meta = run_meta.get("products", {})
    not_run_items: list[str] = []
    for prod, pinfo in sorted(products_meta.items()):
        if isinstance(pinfo, dict) and pinfo.get("status") == "not-run":
            not_run_items.append(f"{prod}: {pinfo.get('reason', 'not run')}")
    for j in journeys:
        if j.get("result") == "not-run":
            reason = j.get("reason", "precondition missing")
            not_run_items.append(f"{j.get('product')}/{j.get('config')}/{j.get('journey')}: {reason}")

    # Build Markdown and HTML in exact required order:
    # 1. Header
    # 2. New blockers and majors
    # 3. Flows broken
    # 4. New minors/polish
    # 5. Timing trend table
    # 6. Not-run list
    # 7. Appendix (all findings including known ones)
    md_lines: list[str] = []
    html_parts: list[str] = []

    # 1. Header
    md_lines.append(f"# Lab Run Report — `{commit}`")
    md_lines.append("")
    md_lines.append(f"- **Started:** {started}")
    md_lines.append(f"- **Finished:** {finished}")
    md_lines.append(f"- **Duration:** {duration_str}")
    prod_summary = ", ".join(
        f"{p} ({info.get('status', 'unknown')})" for p, info in sorted(products_meta.items())
    )
    md_lines.append(f"- **Products:** {prod_summary or 'none'}")
    device_summary = ", ".join(
        f"{name} ({d.get('status', 'unknown')})"
        for info in products_meta.values()
        if isinstance(info, dict)
        for name, d in sorted((info.get("devices") or {}).items())
    )
    if device_summary:
        md_lines.append(f"- **Devices:** {device_summary}")
    md_lines.append("")

    html_parts.append(f"<h1>Lab Run Report — <code>{html.escape(commit)}</code></h1>")
    html_parts.append('<div class="meta-card">')
    html_parts.append(
        f"<p><strong>Started:</strong> {html.escape(started)} &middot; "
        f"<strong>Finished:</strong> {html.escape(finished)} &middot; "
        f"<strong>Duration:</strong> {html.escape(duration_str)}</p>"
    )
    html_parts.append(f"<p><strong>Products:</strong> {html.escape(prod_summary or 'none')}</p>")
    if device_summary:
        html_parts.append(f"<p><strong>Devices:</strong> {html.escape(device_summary)}</p>")
    html_parts.append("</div>")

    def render_finding_section(title: str, items: list[Finding]) -> None:
        md_lines.append(f"## {title}")
        md_lines.append("")
        html_parts.append(f"<h2>{html.escape(title)}</h2>")
        if not items:
            md_lines.append("_None._")
            md_lines.append("")
            html_parts.append("<p><em>None.</em></p>")
            return
        for f in items:
            ev_rel = f.evidence[0] if f.evidence else ""
            md_lines.append(
                f"- **[{f.severity.upper()}] `{f.rule}`** ({f.source}) — "
                f"`{f.product}/{f.config}/{f.journey}/{f.checkpoint}` (`{f.id}`): {f.message}"
            )
            if ev_rel:
                md_lines.append(f"  - Screenshot: [{ev_rel}]({ev_rel})")

            thumb_html = ""
            if ev_rel and (rdir / ev_rel).is_file() and ev_rel.lower().endswith(".png"):
                win_w, win_h = _load_window_dims(rdir, ev_rel)
                thumb_img = render_thumbnail_with_region(rdir / ev_rel, f.region, win_w, win_h)
                data_uri = _thumb_to_data_uri(thumb_img)
                thumb_html = (
                    f'<div class="finding-thumb"><a href="{html.escape(ev_rel)}">'
                    f'<img src="{data_uri}" alt="{html.escape(f.id)}"></a></div>'
                )
            html_parts.append('<div class="finding-card"><div class="finding-row">')
            if thumb_html:
                html_parts.append(thumb_html)
            html_parts.append("<div>")
            html_parts.append(
                f'<span class="badge badge-{html.escape(f.severity)}">{html.escape(f.severity)}</span> '
                f"<strong><code>{html.escape(f.rule)}</code></strong> "
                f"({html.escape(f.source)}) &mdash; "
                f"<code>{html.escape(f.product)}/{html.escape(f.config)}/"
                f"{html.escape(f.journey)}/{html.escape(f.checkpoint)}</code> "
                f"(<code>{html.escape(f.id)}</code>)"
            )
            html_parts.append(f"<p>{html.escape(f.message)}</p>")
            if ev_rel:
                html_parts.append(f'<p><a href="{html.escape(ev_rel)}">Full screenshot ({html.escape(ev_rel)})</a></p>')
            html_parts.append("</div></div></div>")
        md_lines.append("")

    # 2. New blockers and majors
    render_finding_section("New blockers and majors", new_blockers_majors)

    # 3. Flows broken
    md_lines.append("## Flows broken")
    md_lines.append("")
    html_parts.append("<h2>Flows broken</h2>")
    if not broken_flows:
        md_lines.append("_None._")
        md_lines.append("")
        html_parts.append("<p><em>None.</em></p>")
    else:
        html_parts.append("<ul>")
        for bf in broken_flows:
            line = f"{bf.get('product')}/{bf.get('config')}/{bf.get('journey')} ({bf.get('dir', '')})"
            if bf.get("reason"):
                line += f" — {bf['reason']}"
            md_lines.append(f"- `{line}`")
            html_parts.append(f"<li><code>{html.escape(line)}</code></li>")
        html_parts.append("</ul>")
        md_lines.append("")

    # 4. New minors/polish
    render_finding_section("New minors and polish", new_minors_polish)

    # 5. Timing trend table
    md_lines.append("## Timing trend")
    md_lines.append("")
    html_parts.append("<h2>Timing trend</h2>")
    if not timings:
        md_lines.append("_No timing records._")
        md_lines.append("")
        html_parts.append("<p><em>No timing records.</em></p>")
    else:
        md_lines.append("| Product | Config | Journey | Checkpoint | Step (ms) | Total (ms) |")
        md_lines.append("|---|---|---|---|---:|---:|")
        html_parts.append(
            "<table><thead><tr><th>Product</th><th>Config</th><th>Journey</th>"
            "<th>Checkpoint</th><th>Step (ms)</th><th>Total (ms)</th></tr></thead><tbody>"
        )
        for row in timings:
            md_lines.append(
                f"| {row['product']} | {row['config']} | {row['journey']} | "
                f"{row['checkpoint']} | {row['step_ms']} | {row['t_ms']} |"
            )
            html_parts.append(
                f"<tr><td>{html.escape(row['product'])}</td>"
                f"<td>{html.escape(row['config'])}</td>"
                f"<td>{html.escape(row['journey'])}</td>"
                f"<td>{html.escape(row['checkpoint'])}</td>"
                f"<td>{row['step_ms']}</td><td>{row['t_ms']}</td></tr>"
            )
        html_parts.append("</tbody></table>")
        md_lines.append("")

    # 6. Not-run list
    md_lines.append("## Not-run")
    md_lines.append("")
    html_parts.append("<h2>Not-run</h2>")
    if not not_run_items:
        md_lines.append("_None._")
        md_lines.append("")
        html_parts.append("<p><em>None.</em></p>")
    else:
        html_parts.append("<ul>")
        for item in not_run_items:
            md_lines.append(f"- {item}")
            html_parts.append(f"<li>{html.escape(item)}</li>")
        html_parts.append("</ul>")
        md_lines.append("")

    # 7. Appendix: all findings including known ones
    md_lines.append("## Appendix: All findings")
    md_lines.append("")
    html_parts.append("<h2>Appendix: All findings</h2>")
    html_parts.append("<details><summary>Show all findings (including known/triaged)</summary><ul>")
    if not all_findings:
        md_lines.append("_None._")
    else:
        for f in all_findings:
            status_tag = "known" if f.id in known_ids else ("new" if f.new_since_last_run else "existing")
            line = (
                f"[{f.severity}] {f.rule} ({f.source}, {status_tag}) "
                f"{f.product}/{f.config}/{f.journey}/{f.checkpoint} [{f.id}]: {f.message}"
            )
            md_lines.append(f"- {line}")
            html_parts.append(f"<li><code>{html.escape(line)}</code></li>")
    html_parts.append("</ul></details>")
    md_lines.append("")

    md_path = rdir / "report.md"
    html_path = rdir / "report.html"
    md_path.write_text("\n".join(md_lines), encoding="utf-8")

    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    rendered_html = template.replace("{{COMMIT}}", html.escape(commit)).replace(
        "{{BODY}}", "\n".join(html_parts)
    )
    html_path.write_text(rendered_html, encoding="utf-8")
    return md_path, html_path
