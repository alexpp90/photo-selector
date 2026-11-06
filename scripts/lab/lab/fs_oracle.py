"""File-system oracle comparing checkpoint `fs.json` against `fs_expect`."""

from __future__ import annotations

import fnmatch
from collections import Counter
from pathlib import Path
from typing import Any

from .model import Finding
from .paths import ensure_under


def snapshot_directory(
    fixture_root: Path | str,
    lab_root: Path | str,
    trash_dir: Path | str | None = None,
    root_label: str | None = None,
) -> dict[str, Any]:
    """Build a `fs.json` dictionary for `fixture_root` (guarded under `lab_root`)."""
    root_path = ensure_under(fixture_root, lab_root)
    files: list[dict[str, Any]] = []
    if root_path.is_dir():
        for p in sorted(root_path.rglob("*")):
            if p.is_file():
                rel = p.relative_to(root_path).as_posix()
                files.append({"path": rel, "size": p.stat().st_size})

    trash: list[dict[str, Any]] = []
    if trash_dir is not None:
        tpath = Path(trash_dir).expanduser()
        if tpath.is_dir():
            for p in sorted(tpath.rglob("*")):
                if p.is_file():
                    rel = p.relative_to(tpath).as_posix()
                    trash.append({"path": rel, "size": p.stat().st_size})

    return {
        "root": root_label or root_path.name,
        "files": files,
        "trash": trash,
    }


def photo_stem(path: str) -> str:
    """The photo a file belongs to: `DSC_0011` for `RAW/DSC_0011.ARW` and `DSC_0011.JPG.xmp`.

    `removals_allowed: N` in an `unchanged` expectation lets the files of at most N photos
    disappear, and nothing appear or change size. Apps that confirm a delete and commit it later
    (Jackpix keeps it undoable until, among others, a reload or a folder change) remove the
    file on an unrelated step: on 2026-11-06 Jackpix's reload committed a delete confirmed three
    steps earlier, and "unchanged" reported it as a blocker.
    """
    return path.replace("\\", "/").rsplit("/", 1)[-1].split(".", 1)[0]


def evaluate_fs_oracle(
    fs_data: dict[str, Any],
    expect: dict[str, Any] | str | None,
    initial_fs: dict[str, Any] | None = None,
    *,
    product: str = "android-desktop",
    config: str = "default",
    journey: str = "manual",
    checkpoint: str = "checkpoint",
    evidence: list[str] | None = None,
) -> list[Finding]:
    """Compare `fs.json` against `fs_expect` for one checkpoint and return findings."""
    if not expect:
        return []
    if isinstance(expect, str):
        expect_dict: dict[str, Any] = {"unchanged": True} if expect == "unchanged" else {}
    else:
        expect_dict = dict(expect)

    ev = list(evidence or [])
    file_paths = [str(entry["path"]) for entry in fs_data.get("files", [])]
    trash_paths = [str(entry["path"]) for entry in fs_data.get("trash", [])]
    findings: list[Finding] = []

    def add_finding(rule: str, severity: str, message: str, subject: list[str]) -> None:
        fid = Finding.make_id(product, journey, checkpoint, rule, subject)
        findings.append(
            Finding(
                id=fid,
                source="fs-oracle",
                product=product,
                config=config,
                journey=journey,
                checkpoint=checkpoint,
                rule=rule,
                severity=severity,
                confidence="high",
                region=None,
                message=message,
                evidence=ev,
                new_since_last_run=True,
            )
        )

    # unchanged: true -> multiset of (path, size) equals initial_fs
    if expect_dict.get("unchanged") is True:
        if initial_fs is None:
            add_finding(
                "fs-unchanged-missing-baseline",
                "blocker",
                "unchanged expectation specified but 00_initial.fs.json is missing",
                ["unchanged"],
            )
        else:
            cur_bag = Counter((str(e["path"]), int(e["size"])) for e in fs_data.get("files", []))
            init_bag = Counter((str(e["path"]), int(e["size"])) for e in initial_fs.get("files", []))
            missing = sorted(list((init_bag - cur_bag).elements()))
            added = sorted(list((cur_bag - init_bag).elements()))
            allowed = int(expect_dict.get("removals_allowed", 0))
            if missing and not added and len({photo_stem(p) for p, _ in missing}) <= allowed:
                pass  # a delete the user confirmed earlier was committed (see photo_stem)
            elif cur_bag != init_bag:
                changed_paths = sorted({p for p, _ in missing} | {p for p, _ in added})
                add_finding(
                    "fs-unchanged",
                    "blocker",
                    f"expected unchanged files, but differed on: {', '.join(changed_paths)}",
                    changed_paths or ["unchanged"],
                )

    # present: [glob, ...] -> major if missing (or blocker if a companion/expected file is missing)
    for pattern in expect_dict.get("present", []):
        pat = str(pattern)
        if not any(fnmatch.fnmatchcase(p, pat) for p in file_paths):
            add_finding(
                "fs-present",
                "major",
                f"expected file matching {pat!r} to be present, but it was not found",
                [pat],
            )

    # absent: [glob, ...] -> blocker (possible data loss or unexecuted file operation)
    for pattern in expect_dict.get("absent", []):
        pat = str(pattern)
        matches = [p for p in file_paths if fnmatch.fnmatchcase(p, pat)]
        if matches:
            add_finding(
                "fs-absent",
                "blocker",
                f"expected no file matching {pat!r}, but found: {', '.join(matches)}",
                [pat],
            )

    # trashed: [glob, ...] -> major
    for pattern in expect_dict.get("trashed", []):
        pat = str(pattern)
        if not any(fnmatch.fnmatchcase(p, pat) for p in trash_paths):
            add_finding(
                "fs-trashed",
                "major",
                f"expected file matching {pat!r} in trash, but it was not found in trash",
                [pat],
            )

    return findings
