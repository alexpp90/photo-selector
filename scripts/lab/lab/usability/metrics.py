"""Objective per-screen numbers that back the personas' opinions.

Opinions differ; word counts do not. A persona saying "too much to read" is weighed against
how many words the screen really asks the user to read, and how long that takes.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

from ..model import Node, Tree
from .textload import analyse

# On-screen reading of UI copy, not prose: ~210 words per minute.
WORDS_PER_SECOND = 3.5
MIN_TARGET_DP = 48.0
DEFAULT_TOUCH_MIN_TARGET = 48.0
DEFAULT_MOUSE_MIN_TARGET = 24.0

# A screen asking for more than this is flagged; the personas decide whether it was worth it.
TEXT_HEAVY_WORDS = 40
LONG_BLOCK_WORDS = 20

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
_WORD = re.compile(r"[\w'’-]+", re.UNICODE)


def _bounds(el: ET.Element) -> tuple[int, int, int, int] | None:
    m = _BOUNDS.match(el.get("bounds", ""))
    if not m:
        return None
    b = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return b if b[2] > b[0] and b[3] > b[1] else None


def _has_label(el: ET.Element) -> bool:
    return any((n.get("text") or n.get("content-desc")) for n in el.iter("node"))


def count_words(text: str) -> int:
    return len(_WORD.findall(text))


def _normalize_tree(source: Tree | dict[str, Any]) -> Tree:
    if isinstance(source, Tree):
        return source
    if isinstance(source, dict):
        platform = str(source.get("platform", "android"))
        window = dict(source.get("window") or {})
        raw_nodes = source.get("nodes", [])
        nodes: list[Node] = []
        for n in raw_nodes:
            if isinstance(n, Node):
                nodes.append(n)
            elif isinstance(n, dict):
                nodes.append(Node.from_dict(n))
        return Tree(platform=platform, window=window, nodes=nodes)
    raise TypeError(f"Expected Tree or dict, got {type(source).__name__}")


def _labelled_node_ids(nodes: list[Node]) -> set[str]:
    """Ids of nodes that carry a human label or text either directly or in a descendant."""
    by_id = {n.id: n for n in nodes}
    labelled: set[str] = set()
    for n in nodes:
        has_text = bool((n.text or "").strip())
        has_name = bool((n.name or "").strip()) and (n.name.strip() != n.id)
        if has_text or has_name or (n.role == "text" and bool((n.name or "").strip())):
            cur: str | None = n.id
            visited: set[str] = set()
            while cur is not None and cur not in labelled and cur not in visited:
                visited.add(cur)
                labelled.add(cur)
                parent_node = by_id.get(cur)
                cur = parent_node.parent if parent_node else None
    return labelled


def tree_metrics(tree: Tree | dict[str, Any], min_target: float | None = None) -> dict[str, Any]:
    """Calculate objective usability metrics from a normalised UI Tree or dictionary."""
    normalized = _normalize_tree(tree)
    platform = (normalized.platform or "").lower()

    if min_target is not None:
        target = float(min_target)
    elif platform in ("macos", "macos-desktop", "linux", "linux-desktop"):
        target = DEFAULT_MOUSE_MIN_TARGET
    else:
        target = DEFAULT_TOUCH_MIN_TARGET

    labelled = _labelled_node_ids(normalized.nodes)
    blocks: list[str] = []
    seen: set[tuple[str, tuple[float, float, float, float]]] = set()
    controls = small = unlabeled = 0

    for node in normalized.nodes:
        if not node.visible:
            continue

        b = (
            round(node.x, 1),
            round(node.y, 1),
            round(node.w, 1),
            round(node.h, 1),
        )

        raw_text = (node.text or "").strip()
        if not raw_text and (node.name or "").strip():
            name_val = node.name.strip()
            if name_val != node.id or node.role == "text":
                raw_text = name_val

        if raw_text and node.w > 0 and node.h > 0:
            if (raw_text, b) not in seen:
                seen.add((raw_text, b))
                blocks.append(raw_text)

        if (node.interactive or node.role == "button") and node.w > 0 and node.h > 0:
            controls += 1
            if min(node.w, node.h) < target:
                small += 1
            if node.id not in labelled:
                unlabeled += 1

    word_counts = [count_words(t) for t in blocks]
    words = sum(word_counts)
    longest = max(word_counts, default=0)

    win_w = float(normalized.window.get("w", 0) or 0)
    win_h = float(normalized.window.get("h", 0) or 0)
    area = win_w * win_h
    if area <= 0 and normalized.nodes:
        max_x = max((n.x + n.w for n in normalized.nodes), default=0.0)
        max_y = max((n.y + n.h for n in normalized.nodes), default=0.0)
        area = max_x * max_y
    control_density = round(controls / (area / 100_000.0), 2) if area > 0 else 0.0

    return {
        "texts": blocks,
        "words": words,
        "text_blocks": len(blocks),
        "longest_block_words": longest,
        "reading_seconds": round(words / WORDS_PER_SECOND, 1),
        "controls": controls,
        "small_targets": small,
        "unlabeled_controls": unlabeled,
        "text_heavy": words > TEXT_HEAVY_WORDS or longest > LONG_BLOCK_WORDS,
        "control_density": control_density,
    }


def _xml_metrics(
    xml_text: str,
    density_dpi: int = 420,
    min_target: float | None = None,
    locale: str = "en",
) -> dict[str, Any]:
    """Visible text, words, reading time, touch-target facts and per-control text load of one uiautomator dump."""
    start = xml_text.find("<")
    root = ET.fromstring(xml_text[start:] if start > 0 else xml_text)
    px_per_dp = density_dpi / 160.0 if density_dpi > 0 else 1.0
    target_dp = float(min_target) if min_target is not None else DEFAULT_TOUCH_MIN_TARGET

    blocks: list[str] = []
    seen: set[tuple[str, tuple[int, int, int, int]]] = set()
    controls = small = unlabeled = 0

    first_node = root.find("node")
    root_b = _bounds(first_node) if first_node is not None else _bounds(root)

    for el in root.iter("node"):
        b = _bounds(el)
        if b is None:
            continue
        text = (el.get("text") or "").strip()
        if text and (text, b) not in seen:
            seen.add((text, b))
            blocks.append(text)
        if el.get("clickable") == "true" or el.get("long-clickable") == "true":
            controls += 1
            w_dp, h_dp = (b[2] - b[0]) / px_per_dp, (b[3] - b[1]) / px_per_dp
            if min(w_dp, h_dp) < target_dp:
                small += 1
            if not _has_label(el):
                unlabeled += 1

    word_counts = [count_words(t) for t in blocks]
    words = sum(word_counts)
    longest = max(word_counts, default=0)

    if root_b is not None:
        area_dp = ((root_b[2] - root_b[0]) / px_per_dp) * ((root_b[3] - root_b[1]) / px_per_dp)
    else:
        area_dp = 0.0
    control_density = round(controls / (area_dp / 100_000.0), 2) if area_dp > 0 else 0.0

    return {
        "texts": blocks,
        "words": words,
        "text_blocks": len(blocks),
        "longest_block_words": longest,
        "reading_seconds": round(words / WORDS_PER_SECOND, 1),
        "controls": controls,
        "small_targets": small,
        "unlabeled_controls": unlabeled,
        "text_heavy": words > TEXT_HEAVY_WORDS or longest > LONG_BLOCK_WORDS,
        "control_density": control_density,
        "text_load": analyse(xml_text, density_dpi=density_dpi, locale=locale),
    }


def screen_metrics(
    source: str | Tree | dict[str, Any] | Path,
    density_dpi: int = 420,
    min_target: float | None = None,
    locale: str = "en",
) -> dict[str, Any]:
    """Polymorphic screen metrics accepting UiAutomator XML string, Tree instance, dict, or Path."""
    if isinstance(source, (Tree, dict)):
        return tree_metrics(source, min_target=min_target)

    if isinstance(source, Path):
        content = source.read_text(encoding="utf-8")
        return screen_metrics(content, density_dpi=density_dpi, min_target=min_target, locale=locale)

    if isinstance(source, str):
        trimmed = source.strip()
        if trimmed.startswith("{"):
            try:
                parsed = json.loads(trimmed)
                if isinstance(parsed, dict) and ("nodes" in parsed or "platform" in parsed):
                    return tree_metrics(parsed, min_target=min_target)
            except (json.JSONDecodeError, ValueError):
                pass
        elif not trimmed.startswith("<"):
            try:
                p = Path(trimmed)
                if p.is_file():
                    content = p.read_text(encoding="utf-8")
                    return screen_metrics(content, density_dpi=density_dpi, min_target=min_target, locale=locale)
            except OSError:
                pass

        return _xml_metrics(source, density_dpi=density_dpi, min_target=min_target, locale=locale)

    raise TypeError(f"Unsupported source type for screen_metrics: {type(source).__name__}")
