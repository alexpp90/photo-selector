"""Journey bindings: names read from a checkpoint's UI tree, used in later `fs_expect` (LAB-5).

An expectation written against a fixed file ("Selection/*DSC_0003*") silently assumes how many
keys the app handled before it. On 2026-10-17 the first DPAD_RIGHT after launch was swallowed
(AD-11, cause unsettled), M moved DSC_0002, and the lab reported a blocker for a correct Move.
A binding names the photo the app actually showed:

    bind:
      moved: {kept: column_current}      # at checkpoint `moved`, read the frame's file name
    fs_expect:
      after-keep:
        present: ["Selection/*{kept}"]
        absent: ["{kept}"]

The value is the first file name (`name.ext`) in the node's own name or text, else in its
descendants' (Compose puts the label on a child node). A placeholder whose binding was not
found stays unresolved and is reported, never guessed.
"""

from __future__ import annotations

import re
from typing import Any

from .model import Finding, Node, Tree

FILE_NAME_RE = re.compile(r"[\w.-]+\.[A-Za-z0-9]{2,5}")
PLACEHOLDER_RE = re.compile(r"\{(\w+)\}")


def _descendants(tree: Tree, node_id: str) -> list[Node]:
    by_id = {n.id: n for n in tree.nodes}
    out: list[Node] = []
    for n in tree.nodes:
        seen: set[str] = set()
        cur = n.parent
        while cur is not None and cur not in seen:
            if cur == node_id:
                out.append(n)
                break
            seen.add(cur)
            parent = by_id.get(cur)
            cur = parent.parent if parent else None
    return out


def file_name_at(tree: Tree, node_id: str) -> str | None:
    """The first file name shown by `node_id` or its descendants, in tree order."""
    node = next((n for n in tree.nodes if n.id == node_id), None)
    if node is None:
        return None
    for cand in [node, *_descendants(tree, node_id)]:
        for value in (cand.name, cand.text):
            m = FILE_NAME_RE.search(value or "")
            if m:
                return m.group(0)
    return None


def bind_from_tree(tree: Tree, spec: dict[str, Any] | None) -> dict[str, str | None]:
    """`{var: node_id}` -> `{var: file name or None}` for one checkpoint's tree."""
    return {str(var): file_name_at(tree, str(node_id)) for var, node_id in (spec or {}).items()}


def _escape_glob(value: str) -> str:
    """Make a bound file name match literally inside an fnmatch pattern."""
    return re.sub(r"([*?\[])", r"[\1]", value)


def resolve_expect(
    expect: dict[str, Any] | str | None, values: dict[str, str | None]
) -> tuple[dict[str, Any] | str | None, list[str]]:
    """Substitute `{var}` in every pattern of `expect`; return it and the unresolved names.

    A pattern with an unresolved placeholder is dropped from the expectation (it cannot be
    checked); the caller reports the names.
    """
    if not isinstance(expect, dict):
        return expect, []
    missing: list[str] = []
    out: dict[str, Any] = {}
    for key, val in expect.items():
        if not isinstance(val, list):
            out[key] = val
            continue
        kept: list[str] = []
        for pattern in val:
            names = PLACEHOLDER_RE.findall(str(pattern))
            unbound = [n for n in names if not values.get(n)]
            if unbound:
                missing.extend(n for n in unbound if n not in missing)
                continue
            kept.append(PLACEHOLDER_RE.sub(lambda m: _escape_glob(str(values[m.group(1)])), str(pattern)))
        out[key] = kept
    return out, missing


def unbound_finding(
    names: list[str],
    *,
    product: str,
    config: str,
    journey: str,
    checkpoint: str,
    evidence: list[str],
) -> Finding:
    return Finding(
        id=Finding.make_id(product, journey, checkpoint, "fs-binding-missing", names),
        source="fs-oracle",
        product=product,
        config=config,
        journey=journey,
        checkpoint=checkpoint,
        rule="fs-binding-missing",
        severity="major",
        confidence="high",
        region=None,
        message=f"fs_expect uses {', '.join('{' + n + '}' for n in names)}, but no file name was "
        "bound (the bound node was missing or showed no file name)",
        evidence=evidence,
        new_since_last_run=True,
    )
