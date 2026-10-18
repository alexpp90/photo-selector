"""Layout lint rules R1-R8 on normalised checkpoint trees."""

from __future__ import annotations

import fnmatch
from pathlib import Path
from typing import Any

import yaml

from .model import Finding, Node, Tree


RULES_DIR = Path(__file__).resolve().parent.parent / "rules"


def load_rules(product: str) -> dict[str, Any]:
    """Load `scripts/lab/rules/<product>.yaml`."""
    path = RULES_DIR / f"{product}.yaml"
    if not path.is_file():
        raise FileNotFoundError(f"Missing layout rules file for product {product!r}: {path}")
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def intersection_rect(a: list[float], b: list[float]) -> list[float] | None:
    """Return `[x, y, w, h]` of the intersection of `a` and `b`, or `None` if disjoint."""
    x1 = max(float(a[0]), float(b[0]))
    y1 = max(float(a[1]), float(b[1]))
    x2 = min(float(a[0]) + float(a[2]), float(b[0]) + float(b[2]))
    y2 = min(float(a[1]) + float(a[3]), float(b[1]) + float(b[3]))
    if x2 <= x1 or y2 <= y1:
        return None
    return [x1, y1, x2 - x1, y2 - y1]


def rect_area(bounds: list[float]) -> float:
    return max(0.0, float(bounds[2])) * max(0.0, float(bounds[3]))


def is_ancestor(ancestor_id: str, node: Node, by_id: dict[str, Node]) -> bool:
    """Return True if `ancestor_id` is in the parent chain of `node`."""
    visited: set[str] = set()
    cur = node.parent
    while cur is not None and cur not in visited:
        if cur == ancestor_id:
            return True
        visited.add(cur)
        parent_node = by_id.get(cur)
        cur = parent_node.parent if parent_node else None
    return False


def is_overlay_or_descendant(node: Node, overlay_ids: set[str], by_id: dict[str, Node]) -> bool:
    """Return True if `node` is marked `overlay`, in `overlay_ids`, or inside one."""
    if node.overlay or node.id in overlay_ids:
        return True
    visited: set[str] = set()
    cur = node.parent
    while cur is not None and cur not in visited:
        if cur in overlay_ids:
            return True
        visited.add(cur)
        parent_node = by_id.get(cur)
        if parent_node and parent_node.overlay:
            return True
        cur = parent_node.parent if parent_node else None
    return False


def visible_bounds(node: Node, by_id: dict[str, Node]) -> list[float] | None:
    """`node`'s bounds clipped to every ancestor's, or None when nothing of it is visible.

    Android reports a control's touch area: Compose grows a 22 dp star to 48 dp, past the
    22 dp star bar that holds it, so raw bounds "overlapped" the filter chips 8 dp below
    (2026-10-17, ~50 false majors per tablet run). What a person sees ends at the parent.
    """
    rect: list[float] | None = list(node.bounds)
    visited: set[str] = set()
    cur = node.parent
    while rect is not None and cur is not None and cur not in visited:
        visited.add(cur)
        parent = by_id.get(cur)
        if parent is None:
            break
        if parent.w > 0 and parent.h > 0:
            rect = intersection_rect(rect, parent.bounds)
        cur = parent.parent
    return rect


def named_ids(nodes: list[Node]) -> set[str]:
    """Ids of nodes that carry a name or text themselves or in a descendant.

    Compose exposes a clickable's label on a child node (`sidebar_open_folder` has a child
    "Open a folder"); TalkBack merges it, so the control is named (2026-10-17: ~1400 false
    `unnamed-control` findings across the Android runs).
    """
    by_id = {n.id: n for n in nodes}
    named: set[str] = set()
    for n in nodes:
        if not (n.name.strip() or n.text.strip()):
            continue
        cur: str | None = n.id
        while cur is not None and cur not in named:
            named.add(cur)
            parent = by_id.get(cur)
            cur = parent.parent if parent else None
    return named


def _under(node: Node, pattern: str, by_id: dict[str, Node]) -> bool:
    """True if `node` or one of its ancestors has an id matching the glob `pattern`."""
    visited: set[str] = set()
    cur: Node | None = node
    while cur is not None and cur.id not in visited:
        if fnmatch.fnmatchcase(cur.id, pattern):
            return True
        visited.add(cur.id)
        cur = by_id.get(cur.parent) if cur.parent else None
    return False


def overlap_allowed(a: Node, b: Node, pairs: list[list[str]], by_id: dict[str, Node]) -> bool:
    """True for a by-design overlap listed in the rules' `allowed_overlaps` ([a, b] globs)."""
    for pair in pairs:
        x, y = str(pair[0]), str(pair[1])
        if (_under(a, x, by_id) and _under(b, y, by_id)) or (_under(a, y, by_id) and _under(b, x, by_id)):
            return True
    return False


# Touch-target sizes are compared with this tolerance: dp values are px / density and a
# 1.15x density factor reports a 48 dp button as 47.86 dp.
TOUCH_TOLERANCE = 0.5


def lint(
    tree: Tree,
    rules: dict[str, Any],
    expect: dict[str, Any] | None = None,
    *,
    product: str = "android-desktop",
    config: str = "default",
    journey: str = "manual",
    checkpoint: str = "checkpoint",
    evidence: list[str] | None = None,
) -> list[Finding]:
    """Run layout lint rules R1-R8 on `tree` and return findings."""
    expect = expect or {}
    ev = list(evidence or [])
    unit = tree.unit
    min_target = float(rules.get("min_target", 48))
    overlay_ids = {str(x) for x in rules.get("overlay_ids", [])}
    frame_ids = {str(x) for x in rules.get("frame_ids", [])}
    for extra_frame in expect.get("frames", []):
        frame_ids.add(str(extra_frame))
    ignore_ids = {str(x) for x in rules.get("ignore_ids", [])}
    allowed_pairs = [list(p) for p in rules.get("allowed_overlaps", [])]

    by_id: dict[str, Node] = {n.id: n for n in tree.nodes}
    labelled = named_ids(tree.nodes)
    seen_bounds = {n.id: visible_bounds(n, by_id) for n in tree.nodes}
    active_nodes = [
        n
        for n in tree.nodes
        if n.id not in ignore_ids and n.visible and n.w > 0 and n.h > 0
    ]

    findings: list[Finding] = []

    def add_finding(
        rule: str,
        severity: str,
        region: list[float] | None,
        message: str,
        subject: list[str],
    ) -> None:
        fid = Finding.make_id(product, journey, checkpoint, rule, subject)
        findings.append(
            Finding(
                id=fid,
                source="layout-lint",
                product=product,
                config=config,
                journey=journey,
                checkpoint=checkpoint,
                rule=rule,
                severity=severity,
                confidence="high",
                region=region,
                message=message,
                evidence=ev,
                new_since_last_run=True,
            )
        )

    # R3: missing-or-zero-size (blocker); an entry may be a list: any one of them
    def present(node_id: str) -> bool:
        node = by_id.get(node_id)
        return node is not None and node.visible and node.w >= 1 and node.h >= 1

    for req_id in expect.get("required", []):
        alternatives = [str(x) for x in req_id] if isinstance(req_id, list) else [str(req_id)]
        if not any(present(a) for a in alternatives):
            node = by_id.get(alternatives[0])
            reg = node.bounds if node is not None else [0.0, 0.0, 0.0, 0.0]
            req_str = " | ".join(alternatives)
            add_finding(
                "missing-or-zero-size",
                "blocker",
                reg,
                f"required node {req_str!r} is missing, hidden, or smaller than 1x1 {unit}",
                alternatives,
            )

    # R1: overlap-interactive (major)
    interactive_nodes = [
        n
        for n in active_nodes
        if n.interactive and not is_overlay_or_descendant(n, overlay_ids, by_id)
    ]
    for i, a in enumerate(interactive_nodes):
        for b in interactive_nodes[i + 1:]:
            if is_ancestor(a.id, b, by_id) or is_ancestor(b.id, a, by_id):
                continue
            va, vb = seen_bounds.get(a.id), seen_bounds.get(b.id)
            if va is None or vb is None:
                continue
            inter = intersection_rect(va, vb)
            if inter is None or overlap_allowed(a, b, allowed_pairs, by_id):
                continue
            inter_area = rect_area(inter)
            smaller_area = min(rect_area(va), rect_area(vb))
            if inter_area >= 4.0 and smaller_area > 0 and (inter_area / smaller_area) >= 0.10:
                add_finding(
                    "overlap-interactive",
                    "major",
                    inter,
                    f"{a.id} overlaps {b.id} by {inter_area:.0f} {unit}²",
                    [a.id, b.id],
                )

    # R2: occludes-frame (major)
    frame_nodes = [n for n in active_nodes if n.id in frame_ids]
    for frame in frame_nodes:
        f_area = rect_area(frame.bounds)
        if f_area <= 0:
            continue
        for node in active_nodes:
            if node.id == frame.id or node.id in frame_ids:
                continue
            if not (node.interactive or bool(node.text.strip())):
                continue
            if is_overlay_or_descendant(node, overlay_ids, by_id):
                continue
            if is_ancestor(frame.id, node, by_id) or is_ancestor(node.id, frame, by_id):
                continue
            if overlap_allowed(node, frame, allowed_pairs, by_id):
                continue
            seen = seen_bounds.get(node.id)
            inter = intersection_rect(seen, frame.bounds) if seen is not None else None
            if inter is None:
                continue
            inter_area = rect_area(inter)
            if (inter_area / f_area) >= 0.02:
                add_finding(
                    "occludes-frame",
                    "major",
                    inter,
                    f"{node.id} occludes frame {frame.id} by {(inter_area / f_area) * 100:.1f}%",
                    [frame.id, node.id],
                )

    # R4-R7: per active node checks
    for node in active_nodes:
        if node.interactive:
            # R4: off-screen (major)
            if (
                node.x < -1.0
                or node.y < -1.0
                or (node.x + node.w) > (tree.window_w + 1.0)
                or (node.y + node.h) > (tree.window_h + 1.0)
            ):
                add_finding(
                    "off-screen",
                    "major",
                    node.bounds,
                    f"{node.id} extends outside window ({tree.window_w:.0f}x{tree.window_h:.0f} {unit})",
                    [node.id],
                )

            # R5: touch-target (minor)
            if node.w < min_target - TOUCH_TOLERANCE or node.h < min_target - TOUCH_TOLERANCE:
                parent_node = by_id.get(node.parent) if node.parent else None
                parent_covers = (
                    parent_node is not None
                    and parent_node.interactive
                    and parent_node.w >= min_target - TOUCH_TOLERANCE
                    and parent_node.h >= min_target - TOUCH_TOLERANCE
                )
                if not parent_covers:
                    add_finding(
                        "touch-target",
                        "minor",
                        node.bounds,
                        f"{node.id} is {node.w:.0f}x{node.h:.0f} {unit} (min {min_target:.0f} {unit})",
                        [node.id],
                    )

            # R6: unnamed-control (minor)
            if node.id not in labelled:
                add_finding(
                    "unnamed-control",
                    "minor",
                    node.bounds,
                    f"interactive control {node.id} has empty name and text",
                    [node.id],
                )

        # R7: clipped-text (minor)
        if node.clipped is True:
            add_finding(
                "clipped-text",
                "minor",
                node.bounds,
                f"text in {node.id} is clipped/ellipsized",
                [node.id],
            )

    # R9: guidance-covers-control (major). Guidance is exempt from the overlap rules, so the
    # rules name the controls it must leave free (AD-4: the first-run pill sat on the Next
    # frame's maximise badge, invisible to R1 while both were exempt).
    for guide_id, control_ids in (rules.get("guidance_must_not_cover") or {}).items():
        guides = [n for n in active_nodes if n.id == str(guide_id)]
        controls = [n for n in active_nodes if n.id in {str(c) for c in control_ids}]
        reported: set[str] = set()
        for g in guides:
            for c in controls:
                # Repeated ids (one badge per frame) share an id; bounds tell them apart.
                vg, vc = visible_bounds(g, by_id) or g.bounds, visible_bounds(c, by_id) or c.bounds
                inter = intersection_rect(vg, vc)
                key = f"{g.id}|{c.id}|{inter}"
                if inter is not None and rect_area(inter) >= 4.0 and key not in reported:
                    reported.add(key)
                    add_finding(
                        "guidance-covers-control",
                        "major",
                        inter,
                        f"{g.id} covers {c.id} by {rect_area(inter):.0f} {unit}²",
                        [g.id, c.id],
                    )

    # R8: frame-size (major)
    frame_size_cfg = rules.get("frame_size", {}).get(config)
    if isinstance(frame_size_cfg, dict):
        min_w = float(frame_size_cfg.get("min_w", 0))
        min_h = float(frame_size_cfg.get("min_h", 0))
        for fid in frame_size_cfg.get("ids", []):
            fnode = by_id.get(str(fid))
            if fnode is not None and fnode.visible and (fnode.w < min_w or fnode.h < min_h):
                add_finding(
                    "frame-size",
                    "major",
                    fnode.bounds,
                    f"frame {fnode.id} is {fnode.w:.0f}x{fnode.h:.0f} {unit} "
                    f"(expected >= {min_w:.0f}x{min_h:.0f} {unit} in {config})",
                    [fnode.id],
                )

    return findings
