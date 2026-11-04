#!/usr/bin/env python3
"""Fail when a tracked Markdown/HTML document links to a file or `#anchor` that does not exist.

Checked: every relative link and image in the tracked ``*.md`` and ``*.html`` files, resolved
against the tracked tree with GitHub's rules (case-sensitive paths, a leading ``/`` is the
repository root), and the ``#fragment`` of a link into a Markdown or HTML file against that
file's headings (GitHub's slug rules, ``-1`` for duplicates) and explicit ``id``/``name``
anchors. Not checked: external URLs (no network in a gate), line anchors (``#L10``), and links
that point at the maintainer's private overlay, which a public clone does not have (the paths
``check_private_paths.py`` forbids tracking, e.g. ``private/…`` and ``docs/products/*/REQUIREMENTS.md``).

2026-11-03: seven ``#anchor`` links were broken, among them the three platform badges at the top
of the root README, and nothing noticed. Standard library only (the hygiene job has no PyYAML).

    python3 scripts/check_doc_links.py [--root DIR] [--check]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_private_paths import PRIVATE_PATTERNS  # noqa: E402

FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"`+[^`\n]*`+")
MD_LINK = re.compile(r"!?\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REF_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$")
HTML_ATTR = re.compile(r"""\b(?:href|src)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
HTML_ANCHOR = re.compile(r"""\b(?:id|name)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
HEADING = re.compile(r"^ {0,3}(#{1,6})\s+(.+?)\s*#*\s*$")
SCHEME = re.compile(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)")
# Documents that are templates for another site: their links resolve there, not in this tree.
DEPLOYED_ELSEWHERE = {
    # Copied to the root of the alexpp90/apt Pages site by package_debian.sh.
    "products/linux-desktop/packaging/apt/index.html",
}


def slug(heading: str) -> str:
    """GitHub's heading anchor: formatting dropped, lowercase, punctuation and emoji removed."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", heading)  # [text](url) -> text
    text = re.sub(r"<[^>]+>", "", text)  # inline HTML
    text = text.replace("`", "").replace("*", "")
    return re.sub(r"[^\w\- ]", "", text.lower()).replace(" ", "-")


def prose_lines(text: str):
    """(line number, line) outside fenced code blocks, inline code spans removed."""
    fenced = False
    for number, line in enumerate(text.splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
            continue
        if not fenced:
            yield number, INLINE_CODE.sub("", line)


def anchors_of(path: str, text: str) -> set[str]:
    found: set[str] = set()
    if path.endswith(".html"):
        return set(HTML_ANCHOR.findall(text))
    seen: dict[str, int] = {}
    for _number, line in prose_lines(text):
        heading = HEADING.match(line)
        if heading:
            base = slug(heading.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            found.add(base if count == 0 else f"{base}-{count}")
    found |= set(HTML_ANCHOR.findall(text))
    return found


def links_of(path: str, text: str):
    """(line number, target) for every relative-looking link in the document."""
    if path.endswith(".html"):
        for number, line in enumerate(text.splitlines(), 1):
            for target in HTML_ATTR.findall(line):
                yield number, target
        return
    for number, line in prose_lines(text):
        for target in MD_LINK.findall(line):
            yield number, target
        ref = REF_DEF.match(line)
        if ref:
            yield number, ref.group(1)
        for target in HTML_ATTR.findall(line):
            yield number, target


def is_overlay_only(path: str) -> bool:
    return any(pattern.search(path) for pattern, _why in PRIVATE_PATTERNS)


def tracked_files(root: Path) -> list[str]:
    def git(*args: str) -> list[str]:
        out = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, check=True)
        return [line for line in out.stdout.splitlines() if line]

    return sorted(set(git("ls-files")) | set(git("ls-files", "--others", "--exclude-standard")))


def normalise(base_dir: str, target: str) -> str | None:
    """Repository-relative path a link points at, or None when it leaves the repository."""
    parts: list[str] = [] if target.startswith("/") else [p for p in base_dir.split("/") if p]
    for part in target.lstrip("/").split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                return None
            parts.pop()
        else:
            parts.append(part)
    return "/".join(parts)


def check(root: Path, files: list[str] | None = None) -> list[str]:
    tracked = files if files is not None else tracked_files(root)
    known_files = set(tracked)
    known_dirs = {""} | {"/".join(f.split("/")[:i]) for f in tracked for i in range(1, len(f.split("/")))}
    documents = [f for f in tracked if f.endswith((".md", ".html")) and f not in DEPLOYED_ELSEWHERE
                 and (root / f).is_file()]
    texts: dict[str, str] = {}

    def text_of(path: str) -> str:
        if path not in texts:
            texts[path] = (root / path).read_text(encoding="utf-8", errors="replace")
        return texts[path]

    problems: list[str] = []
    for doc in documents:
        base_dir = doc.rpartition("/")[0]
        own_anchors: set[str] | None = None
        for number, raw in links_of(doc, text_of(doc)):
            if SCHEME.match(raw) or raw.startswith("{{") or raw.startswith("$"):
                continue
            target, _, fragment = raw.partition("#")
            target = unquote(target.partition("?")[0])
            fragment = unquote(fragment)
            where = f"{doc}:{number}: {raw}"
            if not target:  # a fragment of this very document
                if fragment and not re.fullmatch(r"L\d+(-L\d+)?", fragment):
                    own_anchors = anchors_of(doc, text_of(doc)) if own_anchors is None else own_anchors
                    if fragment.lower() not in {a.lower() for a in own_anchors}:
                        problems.append(f"{where} -> no heading or id '{fragment}' in this file")
                continue
            resolved = normalise(base_dir, target)
            if resolved is None:
                problems.append(f"{where} -> leaves the repository")
                continue
            if resolved.rstrip("/") not in known_files and resolved.rstrip("/") not in known_dirs:
                if is_overlay_only(resolved.rstrip("/")):
                    continue
                problems.append(f"{where} -> {resolved} does not exist")
                continue
            if fragment and resolved in known_files and resolved.endswith((".md", ".html")) \
                    and not re.fullmatch(r"L\d+(-L\d+)?", fragment):
                if fragment.lower() not in {a.lower() for a in anchors_of(resolved, text_of(resolved))}:
                    problems.append(f"{where} -> no heading or id '{fragment}' in {resolved}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]), help="repository root")
    parser.add_argument("--check", action="store_true", help="accepted for symmetry with the other gates")
    args = parser.parse_args()
    problems = check(Path(args.root))
    if problems:
        print(f"{len(problems)} broken documentation link(s):", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print("✔ Documentation links and anchors resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
