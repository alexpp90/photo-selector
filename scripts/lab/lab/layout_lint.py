"""Layout lint rules R1-R11 on normalised checkpoint trees."""

from __future__ import annotations

import fnmatch
import re
from pathlib import Path
from typing import Any

import yaml

from .feedback import MISSING, UNCHECKED, WRONG, check_feedback
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


def cut_by_scroll(node: Node, by_id: dict[str, Node], tol: float = 0.5) -> bool:
    """True when a scrolling ancestor cuts `node` at its top or bottom edge.

    uiautomator reports only what is on screen of a node that is scrolled partly out: its
    bounds end at (or run past) the scroll container's edge and its off-screen children (often
    the label) are missing. Its height and its name are then unknown, not too small or empty
    (LAB-26, 2026-10-19: `nav_statistics 80x35 dp` with the sidebar scrolled half past it,
    `unnamed-control` on the landing card below the fold). Its width is still real: the view
    toggles cut at the bottom of the narrow control block are 28 dp wide (AD-17). Only vertical
    cuts are recognised; a node reaching both edges fills the container rather than being cut.
    """
    visited: set[str] = set()
    cur = node.parent
    while cur is not None and cur not in visited:
        visited.add(cur)
        anc = by_id.get(cur)
        if anc is None:
            return False
        if anc.scrollable and anc.h > 0 and (node.y <= anc.y + tol) != (node.y + node.h >= anc.y + anc.h - tol):
            return True
        cur = anc.parent
    return False


_TRAILING_ELLIPSIS = re.compile(r"\S\s*(…|\.\.\.)\s*$")
_WORD_BROKEN_BY_HYPHEN = re.compile(r"[^\W\d_]-[ \t]*\n[ \t]*[^\W\d_A-Z]")


def text_truncation(text: str) -> str | None:
    """Why `text` reads as cut short, or None.

    Distinct from R7 (`clipped`, a platform flag): this one reads the words themselves. A text
    that ends in an ellipsis was shortened to fit (`DSC_0042_long…`), and one broken inside a
    word at a line end (`Verschie-\nben`) was too narrow for its longest word. Both are what the
    maintainer sees at large text sizes. Strings that end in an ellipsis on purpose ("Loading…")
    are allowed per product (rules `text_truncation.allow_text`/`allow_ids`).
    """
    if _TRAILING_ELLIPSIS.search(text):
        return "ends in an ellipsis"
    if _WORD_BROKEN_BY_HYPHEN.search(text):
        return "is broken inside a word across lines"
    return None


def truncation_allowed(node: Node, text: str, cfg: dict[str, Any]) -> bool:
    if any(fnmatch.fnmatchcase(node.id, str(g)) for g in cfg.get("allow_ids") or []):
        return True
    return any(re.search(str(rx), text) for rx in cfg.get("allow_text") or [])


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


def dismiss_scrims(tree: Tree) -> dict[int, int]:
    """Scrims behind a sheet or dialog, mapped to their position in drawing order.

    A scrim is an interactive node with no children that covers most of the window: Material's
    modal bottom sheet exposes one ("Close sheet"), and in landscape it shows beside the centred
    sheet. Everything drawn after it lies above it by design (2026-11-06, Jackpix Settings on a
    Pixel 4a: eight overlap majors against the scrim).
    """
    win_area = float(tree.window.get("w", 0)) * float(tree.window.get("h", 0))
    parents = {n.parent for n in tree.nodes}
    return {
        id(n): i
        for i, n in enumerate(tree.nodes)
        if n.interactive and n.id not in parents and win_area > 0 and n.w * n.h >= SCRIM_SHARE * win_area
    }


SCRIM_SHARE = 0.9


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


def cut_by_overlap(node: Node, nodes: list[Node], by_id: dict[str, Node]) -> tuple[bool, bool]:
    """`(width_cut, height_cut)`: an overlapping node cuts `node`'s reported bounds on that axis.

    Accessibility reports a control's visible bounds, so a snackbar drawn over the edge of the
    first-run pill's Got-it button reported it 26 dp high instead of 48 (2026-11-05, Pixel Tablet,
    `first_run_hint_dismiss` 56x26 beside 56x48 in 15 other checkpoints). Evidence of the cut, all
    from the tree: a descendant of `node` reaches past one of its edges, another node (neither
    ancestor nor descendant) covers that reach, and that node's edge meets `node`'s on the cut
    edge. The axis of the cut is then unknown, not too small (as `cut_by_scroll`, LAB-26); the
    other axis is still real.
    """
    tol = TOUCH_TOLERANCE

    def related(a: Node, b: Node) -> bool:
        return is_ancestor(a.id, b, by_id) or is_ancestor(b.id, a, by_id)

    def spans(a0: float, a1: float, b0: float, b1: float) -> bool:
        return min(a1, b1) - max(a0, b0) > tol

    inner = [d for d in nodes if d is not node and d.w > 0 and d.h > 0 and is_ancestor(node.id, d, by_id)]
    width_cut = height_cut = False
    for d in inner:
        reach_top, reach_bottom = d.y < node.y - tol, d.y + d.h > node.y + node.h + tol
        reach_left, reach_right = d.x < node.x - tol, d.x + d.w > node.x + node.w + tol
        if not (reach_top or reach_bottom or reach_left or reach_right):
            continue
        for o in nodes:
            if o is node or o is d or o.w <= 0 or o.h <= 0 or related(o, node) or intersection_rect(d.bounds, o.bounds) is None:
                continue
            across_x = spans(o.x, o.x + o.w, node.x, node.x + node.w)
            across_y = spans(o.y, o.y + o.h, node.y, node.y + node.h)
            if across_x and ((reach_top and abs(o.y + o.h - node.y) <= tol) or (reach_bottom and abs(o.y - (node.y + node.h)) <= tol)):
                height_cut = True
            if across_y and ((reach_left and abs(o.x + o.w - node.x) <= tol) or (reach_right and abs(o.x - (node.x + node.w)) <= tol)):
                width_cut = True
    return width_cut, height_cut


def _contains(outer: Node, inner: Node, tol: float = TOUCH_TOLERANCE) -> bool:
    return (
        outer.x <= inner.x + tol
        and outer.y <= inner.y + tol
        and outer.x + outer.w >= inner.x + inner.w - tol
        and outer.y + outer.h >= inner.y + inner.h - tol
    )


def nested_control_groups(
    nodes: list[Node], by_id: dict[str, Node], min_target: float
) -> dict[int, tuple[list[float], list[Node]]]:
    """Interactive nodes that are one control, keyed by `id()` of the member that stands for it.

    Compose nests two interactive nodes for one control: a tooltip anchor (long-clickable, 32x28
    dp) around the IconButton that grows to 48 dp past it (Jackpix's sheet drag handle, #9 and
    #10, 2026-11-05: touch-target and unnamed-control each reported twice). A node joins its
    nearest interactive ancestor when one's bounds contain the other's and that ancestor is
    under-sized itself. An ancestor of full size keeps ruling its children through R5's
    `parent_covers`, so no small control is hidden inside a big one.

    Nodes are told apart by identity, not by id: Frameduel repeats ids (one `filmstrip_thumbnail`
    per frame). Value: (the union of the members' bounds `[x, y, w, h]`, all members). The key is
    the member with the largest area (the first in tree order on a tie): the one judged.
    """
    tol = TOUCH_TOLERANCE
    root: dict[int, int] = {}

    def find(i: int) -> int:
        while root.get(i, i) != i:
            i = root[i]
        return i

    joined: set[int] = set()
    for node in nodes:
        if not node.interactive:
            continue
        visited: set[str] = set()
        cur = by_id.get(node.parent) if node.parent else None
        while cur is not None and cur.id not in visited:
            visited.add(cur.id)
            if cur.interactive:
                small = cur.w < min_target - tol or (not cut_by_scroll(cur, by_id) and cur.h < min_target - tol)
                if small and (_contains(cur, node) or _contains(node, cur)):
                    root[find(id(node))] = find(id(cur))
                    joined.update((id(node), id(cur)))
                break
            cur = by_id.get(cur.parent) if cur.parent else None

    groups: dict[int, list[Node]] = {}
    for node in nodes:
        if id(node) in joined:
            groups.setdefault(find(id(node)), []).append(node)
    out: dict[int, tuple[list[float], list[Node]]] = {}
    for group in groups.values():
        x1, y1 = min(m.x for m in group), min(m.y for m in group)
        x2, y2 = max(m.x + m.w for m in group), max(m.y + m.h for m in group)
        stand_in = max(group, key=lambda m: m.w * m.h)
        out[id(stand_in)] = ([x1, y1, x2 - x1, y2 - y1], group)
    return out


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
    # Per node, not per id: Frameduel repeats ids (one `filmstrip_thumbnail` per frame), and a dict
    # by id kept the last duplicate, so R1 intersected it with itself (2026-11-05: 233 false majors).
    seen_bounds = {id(n): visible_bounds(n, by_id) for n in tree.nodes}
    id_count: dict[str, int] = {}
    for n in tree.nodes:
        id_count[n.id] = id_count.get(n.id, 0) + 1

    def ref(n: Node) -> str:
        """A node's name in a finding's id: its id, plus its bounds where the id is not unique.

        Findings of repeated ids shared one id per checkpoint; a unique id keeps the name it had,
        so triaged finding ids stay valid.
        """
        if id_count[n.id] == 1:
            return n.id
        return f"{n.id}@{n.x:.0f},{n.y:.0f},{n.w:.0f},{n.h:.0f}"
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

    # R3b: forbidden-node-present (major): a node that must be gone, e.g. the guide after Back closed it
    for bad_id in expect.get("forbidden", []):
        shown = [n for n in active_nodes if n.id == str(bad_id)]
        if shown:
            add_finding(
                "forbidden-node-present",
                "major",
                shown[0].bounds,
                f"node {bad_id!r} must not be on screen at this checkpoint",
                [str(bad_id)],
            )

    # R1: overlap-interactive (major)
    interactive_nodes = [
        n
        for n in active_nodes
        if n.interactive and not is_overlay_or_descendant(n, overlay_ids, by_id)
    ]
    scrims = dismiss_scrims(tree)
    order = {id(n): i for i, n in enumerate(tree.nodes)}
    for i, a in enumerate(interactive_nodes):
        for b in interactive_nodes[i + 1:]:
            if is_ancestor(a.id, b, by_id) or is_ancestor(b.id, a, by_id):
                continue
            # A scrim drawn first lies under everything drawn after it.
            if id(min((a, b), key=lambda n: order[id(n)])) in scrims:
                continue
            va, vb = seen_bounds.get(id(a)), seen_bounds.get(id(b))
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
                    [ref(a), ref(b)],
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
            seen = seen_bounds.get(id(node))
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
                    [ref(frame), ref(node)],
                )

    # R4-R7: per active node checks. Nested interactive nodes of one control (a tooltip anchor
    # around its button) are judged once, for R5 and R6, by the member that stands for them.
    groups = nested_control_groups([n for n in active_nodes if n.interactive], by_id, min_target)
    grouped = {id(m) for _, members in groups.values() for m in members}
    for node in active_nodes:
        cut = node.interactive and cut_by_scroll(node, by_id)
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
                    [ref(node)],
                )

            group = groups.get(id(node))
            if id(node) not in grouped or group is not None:  # a member is judged by its stand-in
                ctrl = group[0] if group else node.bounds  # the union of a nested control
                ctrl_w, ctrl_h = float(ctrl[2]), float(ctrl[3])
                ctrl_members = group[1] if group else [node]
                cut = any(cut_by_scroll(m, by_id) for m in ctrl_members)

                # R5: touch-target (minor); a control cut by its scroll container has no known height,
                # one cut by an overlapping node no known size on the cut axis
                too_narrow = ctrl_w < min_target - TOUCH_TOLERANCE
                too_short = not cut and ctrl_h < min_target - TOUCH_TOLERANCE
                if too_narrow or too_short:
                    w_cut, h_cut = cut_by_overlap(node, active_nodes, by_id)
                    too_narrow, too_short = too_narrow and not w_cut, too_short and not h_cut
                if too_narrow or too_short:
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
                            list(ctrl),
                            f"{node.id} is {ctrl_w:.0f}x{ctrl_h:.0f} {unit} (min {min_target:.0f} {unit})",
                            [ref(node)],
                        )

                # R6: unnamed-control (minor); a cut control's label may be the part off screen
                if not any(m.id in labelled for m in ctrl_members) and not cut:
                    add_finding(
                        "unnamed-control",
                        "minor",
                        list(ctrl),
                        f"interactive control {node.id} has empty name and text",
                        [ref(node)],
                    )

        # R7: clipped-text (minor)
        if node.clipped is True:
            add_finding(
                "clipped-text",
                "minor",
                node.bounds,
                f"text in {node.id} is clipped/ellipsized",
                [ref(node)],
            )

    # R10: truncated-text (minor): text shortened with an ellipsis or broken inside a word
    trunc_cfg = rules.get("text_truncation") or {}
    for node in active_nodes:
        for text in dict.fromkeys(t for t in (node.text, node.name) if t and t.strip()):
            why = text_truncation(text)
            if why and not truncation_allowed(node, text, trunc_cfg):
                add_finding(
                    "truncated-text",
                    "minor",
                    node.bounds,
                    f"text {text!r} in {node.id} {why}",
                    [ref(node), text],
                )

    # R11: missing-feedback (major): the checkpoint after an action must show feedback that names it
    fb = check_feedback(tree, rules, expect.get("expect_feedback"))
    if fb is not None and fb.status != "ok":
        rule, severity = {
            MISSING: ("missing-feedback", "major"),
            WRONG: ("wrong-feedback", "major"),
            UNCHECKED: ("feedback-unchecked", "minor"),
        }[fb.status]
        wanted = " or ".join(fb.actions) or "any action"
        add_finding(rule, severity, fb.region, f"after {wanted}: {fb.message}", [wanted])

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
                        [ref(g), ref(c)],
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
                    [ref(fnode)],
                )

    return findings
