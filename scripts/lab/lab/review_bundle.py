"""Build AI review bundles from a lab run directory (C-20)."""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from typing import Any

import yaml
from PIL import Image

from .paths import ensure_under, repo_root


def compute_image_change_fraction(
    current_png: Path,
    prev_png: Path,
    width: int = 320,
    threshold: int = 16,
) -> float:
    """Compute fraction of pixels whose RGB differs by > threshold in any channel after resizing to 320px wide."""
    with Image.open(current_png) as img_cur, Image.open(prev_png) as img_prev:
        cur_rgb = img_cur.convert("RGB")
        prev_rgb = img_prev.convert("RGB")
        cur_h = max(1, int(round(cur_rgb.height * (width / max(1, cur_rgb.width)))))
        cur_small = cur_rgb.resize((width, cur_h), Image.Resampling.BILINEAR)
        prev_small = prev_rgb.resize((width, cur_h), Image.Resampling.BILINEAR)

        cur_bytes = cur_small.tobytes()
        prev_bytes = prev_small.tobytes()
        # Count the pixels actually compared: the built-in Pillow fallback keeps a smaller buffer
        # than width x height.
        total_pixels = min(len(cur_bytes), len(prev_bytes)) // 3
        if total_pixels == 0:
            return 0.0

        changed = 0
        for idx in range(0, total_pixels * 3, 3):
            if (
                abs(cur_bytes[idx] - prev_bytes[idx]) > threshold
                or abs(cur_bytes[idx + 1] - prev_bytes[idx + 1]) > threshold
                or abs(cur_bytes[idx + 2] - prev_bytes[idx + 2]) > threshold
            ):
                changed += 1
        return changed / total_pixels


def downscale_image(src: Path, dst: Path, max_long_edge: int = 1600) -> tuple[int, int]:
    """Copy or downscale src PNG to dst so its long edge is <= max_long_edge. Returns (orig_w, orig_h)."""
    with Image.open(src) as img:
        orig_w, orig_h = img.size
        long_edge = max(orig_w, orig_h)
        if long_edge <= max_long_edge:
            if src.resolve() != dst.resolve():
                shutil.copy2(src, dst)
        else:
            scale = max_long_edge / long_edge
            new_size = (max(1, int(round(orig_w * scale))), max(1, int(round(orig_h * scale))))
            resized = img.resize(new_size, Image.Resampling.LANCZOS)
            resized.save(dst, format="PNG")
        return orig_w, orig_h


def parse_expectations(journey_id: str, expectations_dir: Path | None = None) -> dict[str, str]:
    """Parse private/lab/expectations/<journey>.md into {checkpoint_name: expectation_text}."""
    exp_dir = expectations_dir or (repo_root() / "private" / "lab" / "expectations")
    md_path = exp_dir / f"{journey_id}.md"
    if not md_path.is_file():
        return {}
    text = md_path.read_text(encoding="utf-8")
    if text.startswith("---"):
        parts = text.split("---", 2)
        if len(parts) == 3:
            text = parts[2]
    sections: dict[str, str] = {}
    current_cp: str | None = None
    buf: list[str] = []
    for line in text.splitlines():
        m = re.match(r"^##\s+(.+?)\s*$", line)
        if m:
            if current_cp is not None:
                sections[current_cp] = "\n".join(buf).strip()
            current_cp = m.group(1).strip()
            buf = []
        elif current_cp is not None:
            buf.append(line)
    if current_cp is not None:
        sections[current_cp] = "\n".join(buf).strip()
    return sections


def _find_previous_run_dir(run_dir: Path) -> Path | None:
    parent = run_dir.parent
    if not parent.is_dir():
        return None
    siblings = sorted(
        d for d in parent.iterdir() if d.is_dir() and (d / "run.json").is_file() and d.name < run_dir.name
    )
    return siblings[-1] if siblings else None


def build_review_bundles(
    run_dir: Path,
    include_all: bool = False,
    max_images: int = 15,
    prev_run_dir: Path | None = None,
    private_lab_dir: Path | None = None,
) -> list[Path]:
    """Build review/bundle-<n>/ folders for a lab run directory."""
    run_dir = run_dir.resolve()
    r = repo_root()
    priv_dir = private_lab_dir or (r / "private" / "lab")
    schemas_dir = r / "scripts" / "lab" / "schemas"
    if prev_run_dir is None:
        prev_run_dir = _find_previous_run_dir(run_dir)

    candidates: list[dict[str, Any]] = []
    for res_path in sorted(run_dir.glob("*/*/*/result.json")):
        rel = res_path.relative_to(run_dir)
        product, config, journey = rel.parts[0], rel.parts[1], rel.parts[2]
        j_dir = res_path.parent
        expectations = parse_expectations(journey, priv_dir / "expectations")

        for png_path in sorted(j_dir.glob("[0-9][0-9]_*.png")):
            m = re.match(r"^(\d{2})_(.+)\.png$", png_path.name)
            if not m:
                continue
            prefix, cp_name = m.group(1), m.group(2)
            prev_png: Path | None = None
            if prev_run_dir is not None:
                cand_prev = prev_run_dir / product / config / journey / png_path.name
                if cand_prev.is_file():
                    prev_png = cand_prev

            if not include_all and prev_png is not None:
                diff_frac = compute_image_change_fraction(png_path, prev_png)
                if diff_frac <= 0.005:
                    continue

            expect_text = expectations.get(cp_name) or "(none written)"
            candidates.append(
                {
                    "product": product,
                    "config": config,
                    "journey": journey,
                    "checkpoint": cp_name,
                    "prefix": prefix,
                    "png_path": png_path,
                    "prev_png": prev_png,
                    "expectation": expect_text,
                }
            )

    review_root = run_dir / "review"
    ensure_under(review_root, run_dir)
    review_root.mkdir(parents=True, exist_ok=True)

    known_path = priv_dir / "review" / "known_findings.yaml"
    known_items: list[dict[str, Any]] = []
    if known_path.is_file():
        loaded = yaml.safe_load(known_path.read_text(encoding="utf-8"))
        if isinstance(loaded, list):
            known_items = loaded

    instructions_src = priv_dir / "review" / "instructions.md"
    schema_src = schemas_dir / "finding.schema.json"

    if not candidates:
        candidates_chunks: list[list[dict[str, Any]]] = [[]]
    else:
        candidates_chunks = [
            candidates[i : i + max(1, max_images)] for i in range(0, len(candidates), max(1, max_images))
        ]

    created_bundles: list[Path] = []
    for bundle_idx, chunk in enumerate(candidates_chunks, start=1):
        b_dir = review_root / f"bundle-{bundle_idx}"
        ensure_under(b_dir, run_dir)
        img_dir = b_dir / "images"
        img_dir.mkdir(parents=True, exist_ok=True)

        context_lines = ["# Review Bundle Context", ""]
        manifest_entries: list[dict[str, Any]] = []

        for item_idx, item in enumerate(chunk, start=1):
            base_name = f"{item_idx:02d}_{item['journey']}_{item['config']}_{item['checkpoint']}"
            cur_img_name = f"{base_name}.png"
            dst_cur = img_dir / cur_img_name
            ensure_under(dst_cur, run_dir)
            orig_w, orig_h = downscale_image(item["png_path"], dst_cur)
            with Image.open(dst_cur) as d_img:
                bundle_w, bundle_h = d_img.size

            prev_img_name: str | None = None
            if item["prev_png"] is not None:
                prev_img_name = f"{base_name}.prev.png"
                dst_prev = img_dir / prev_img_name
                ensure_under(dst_prev, run_dir)
                downscale_image(item["prev_png"], dst_prev)

            context_lines.extend(
                [
                    f"## `images/{cur_img_name}`",
                    f"- **Product:** `{item['product']}`",
                    f"- **Config:** `{item['config']}`",
                    f"- **Journey:** `{item['journey']}`",
                    f"- **Checkpoint:** `{item['checkpoint']}`",
                    f"- **Previous image:** `{f'images/{prev_img_name}' if prev_img_name else 'none'}`",
                    f"- **Expectation:** {item['expectation']}",
                    "",
                ]
            )
            manifest_entries.append(
                {
                    "image": f"images/{cur_img_name}",
                    "image_basename": cur_img_name,
                    "product": item["product"],
                    "config": item["config"],
                    "journey": item["journey"],
                    "checkpoint": item["checkpoint"],
                    "prefix": item["prefix"],
                    "orig_size": [orig_w, orig_h],
                    "bundle_size": [bundle_w, bundle_h],
                }
            )

        (b_dir / "context.md").write_text("\n".join(context_lines), encoding="utf-8")
        (b_dir / "manifest.json").write_text(json.dumps(manifest_entries, indent=2), encoding="utf-8")

        if instructions_src.is_file():
            shutil.copy2(instructions_src, b_dir / "instructions.md")
        else:
            (b_dir / "instructions.md").write_text("Follow context.md and output JSON array.\n", encoding="utf-8")

        if schema_src.is_file():
            shutil.copy2(schema_src, b_dir / "schema.json")

        known_lines = ["# Known Findings", ""]
        for kf in known_items:
            known_lines.append(f"- `{kf.get('id', '')}`: {kf.get('reason', '')} (until {kf.get('until', '')})")
        if len(known_lines) == 2:
            known_lines.append("(none)")
        (b_dir / "known.md").write_text("\n".join(known_lines) + "\n", encoding="utf-8")

        created_bundles.append(b_dir)

    return created_bundles
