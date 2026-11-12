#!/usr/bin/env python3
"""Cell-wise three-way merge driver for Markdown tables (git merge driver, stdlib only).

docs/shared/FEATURE_PARITY.md holds one row per feature and one cell per product, so parallel
product PRs each edit a different cell of the same line and git conflicts on the whole line
(five PRs in the 2026-11-10 sweep, each resolved by hand). This driver lets git merge the file
as text first; for every conflict hunk whose three sides are table rows of equal count and
width it merges cell by cell (a cell changed on one side only takes that side). A cell changed
on both sides, or a hunk that is not a table, stays a normal conflict.

    python3 scripts/md_table_merge.py install     # once per clone: registers the driver
    (git calls)  md_table_merge.py %O %A %B       # base, ours (result is written here), theirs

.gitattributes names the files (`merge=mdtable`); without the registration git falls back to its
default text merge, so an unregistered clone loses nothing.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

CELL_SPLIT = re.compile(r"(?<!\\)\|")
DRIVER = "python3 scripts/md_table_merge.py %O %A %B"


def is_row(line: str) -> bool:
    return line.lstrip().startswith("|")


def cells(line: str) -> list[str]:
    return CELL_SPLIT.split(line.rstrip("\n"))


def merge_rows(base: list[str], ours: list[str], theirs: list[str]) -> list[str] | None:
    """Merge equal-length row blocks cell by cell; None if any cell or any shape conflicts."""
    if not (len(base) == len(ours) == len(theirs)):
        return None
    merged = []
    for b, o, t in zip(base, ours, theirs):
        if not (is_row(b) and is_row(o) and is_row(t)):
            return None
        bc, oc, tc = cells(b), cells(o), cells(t)
        if not (len(bc) == len(oc) == len(tc)):
            return None
        out = []
        for bx, ox, tx in zip(bc, oc, tc):
            if ox == tx or tx == bx:
                out.append(ox)
            elif ox == bx:
                out.append(tx)
            else:
                return None
        merged.append("|".join(out) + "\n")
    return merged


def merge_texts(base: str, ours: str, theirs: str) -> tuple[str, bool]:
    """Return (merged text, True if a conflict remains)."""
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for name, text in (("ours", ours), ("base", base), ("theirs", theirs)):
            p = Path(tmp) / name
            p.write_text(text, encoding="utf-8")
            paths.append(str(p))
        proc = subprocess.run(["git", "merge-file", "-p", "--diff3", *paths],
                              capture_output=True, text=True, encoding="utf-8")
    if proc.returncode < 0 or proc.returncode > 127:
        raise RuntimeError(proc.stderr)
    if proc.returncode == 0:
        return proc.stdout, False
    out: list[str] = []
    lines = proc.stdout.splitlines(keepends=True)
    conflict = False
    i = 0
    while i < len(lines):
        if not lines[i].startswith("<<<<<<< "):
            out.append(lines[i])
            i += 1
            continue
        j = i + 1
        sections: list[list[str]] = [[], [], []]
        idx = 0
        while not lines[j].startswith(">>>>>>> "):
            if lines[j].startswith("||||||| "):
                idx = 1
            elif lines[j].rstrip("\n") == "=======":
                idx = 2
            else:
                sections[idx].append(lines[j])
            j += 1
        merged = merge_rows(sections[1], sections[0], sections[2])
        if merged is None:
            conflict = True
            out.extend(lines[i:j + 1])
        else:
            out.extend(merged)
        i = j + 1
    return "".join(out), conflict


def install() -> int:
    for key, value in (("merge.mdtable.name", "Markdown table, cell-wise"), ("merge.mdtable.driver", DRIVER)):
        subprocess.run(["git", "config", key, value], check=True)
    print("registered merge.mdtable in this clone's git config")
    return 0


def main(argv: list[str]) -> int:
    if argv == ["install"]:
        return install()
    if len(argv) != 3:
        print(__doc__)
        return 2
    base, ours, theirs = (Path(a).read_text(encoding="utf-8") for a in argv)
    text, conflict = merge_texts(base, ours, theirs)
    Path(argv[1]).write_text(text, encoding="utf-8")
    return 1 if conflict else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
