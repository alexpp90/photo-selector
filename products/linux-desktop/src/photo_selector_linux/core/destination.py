"""Where Move and Copy put photos: the single resolution of the Selection destination.

Settings shows this resolution and ``CullingWorkspaceViewModel`` hands the same ``root`` and
``selection_name`` to ``FileCullingManager``, so the text the user reads cannot disagree with
where the files go (R-LINUX-PREF-03). Pure and GTK-free.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

MODE_WORKING = "working"
MODE_CUSTOM = "custom"
MODES = (MODE_WORKING, MODE_CUSTOM)
DEFAULT_SELECTION_NAME = "Selection"

PROBLEM_UNSET = "unset"
PROBLEM_MISSING = "missing"
PROBLEM_NOT_A_FOLDER = "not_a_folder"
PROBLEM_NOT_WRITABLE = "not_writable"
PROBLEM_IS_SELECTION = "is_selection"

SELECTION_MARKER_FILENAME = ".photoselector-selection"


def selection_subfolder_name(value: Any) -> str:
    """The Selection subfolder name for ``value``: one path component, blank -> ``Selection``."""
    if not isinstance(value, str):
        return DEFAULT_SELECTION_NAME
    cleaned = value.strip().rstrip("/\\").strip()
    name = Path(cleaned).name.strip() if cleaned else ""
    return name if name else DEFAULT_SELECTION_NAME


def is_selection_folder(folder: Optional[Path], selection_name: Any = None) -> bool:
    """True when ``folder`` is itself a Selection folder: it carries the marker file the app writes,
    or it has the Selection name. Filing into it would nest ``Selection/Selection/``, which the
    scan excludes, so the keeper would seem to vanish."""
    if folder is None:
        return False
    try:
        if (folder / SELECTION_MARKER_FILENAME).exists():
            return True
    except OSError:
        pass
    return folder.name == selection_subfolder_name(selection_name)


@dataclass(frozen=True)
class DestinationResolution:
    """The resolved Selection destination for one (mode, name, open folder, custom folder)."""

    mode: str
    selection_name: str
    working_folder: Optional[Path]
    custom_folder: Optional[Path]
    #: Folder the Selection subfolder is created in; ``None`` while it cannot be determined.
    root: Optional[Path]
    #: ``root / selection_name``; ``None`` while ``root`` is ``None``.
    path: Optional[Path]
    #: ``PROBLEM_*`` when the custom folder cannot receive photos, else ``None``.
    problem: Optional[str]

    @property
    def ok(self) -> bool:
        """True when Move/Copy may write to ``path`` (a problem or an open-folder gap says no)."""
        return self.problem is None and self.path is not None


def _custom_folder_problem(folder: Optional[Path]) -> Optional[str]:
    if folder is None:
        return PROBLEM_UNSET
    if not folder.exists():
        return PROBLEM_MISSING
    if not folder.is_dir():
        return PROBLEM_NOT_A_FOLDER
    if not os.access(folder, os.W_OK | os.X_OK):
        return PROBLEM_NOT_WRITABLE
    return None


def resolve_destination(
    mode: str,
    selection_name: Any,
    working_folder: Optional[Path],
    custom_folder: Optional[Path],
) -> DestinationResolution:
    """Resolve where the next Move/Copy goes.

    ``working``: ``<working_folder>/<name>`` (``root`` is ``None`` with no folder open).
    ``custom``: ``<custom_folder>/<name>``, but only while the custom folder exists, is a
    folder and is writable; otherwise ``problem`` says why and nothing may be written (a vanished
    mount point is never silently recreated as a local directory).
    """
    name = selection_subfolder_name(selection_name)
    working = Path(working_folder) if working_folder is not None else None
    custom = Path(custom_folder) if custom_folder is not None else None
    if mode == MODE_CUSTOM:
        problem = _custom_folder_problem(custom)
        root = custom
    else:
        mode = MODE_WORKING
        problem = None
        root = working
    if problem is None and root is not None and is_selection_folder(root, name):
        problem = PROBLEM_IS_SELECTION
    return DestinationResolution(
        mode=mode,
        selection_name=name,
        working_folder=working,
        custom_folder=custom,
        root=root,
        path=(root / name) if root is not None else None,
        problem=problem,
    )


def display_path(path: Path, home: Optional[Path] = None) -> str:
    """``path`` for reading: the home directory is shortened to ``~``."""
    home = home if home is not None else Path.home()
    try:
        relative = path.relative_to(home)
    except ValueError:
        return str(path)
    return "~" if str(relative) == "." else f"~/{relative}"


def describe_custom_folder(resolution: DestinationResolution, home: Optional[Path] = None) -> str:
    """Subtitle of the Custom folder row: the chosen folder, or why it cannot be used."""
    folder = resolution.custom_folder
    if folder is None:
        return "No folder chosen"
    shown = display_path(folder, home)
    if resolution.mode == MODE_CUSTOM and resolution.problem is not None:
        return _problem_text(resolution.problem, shown)
    problem = _custom_folder_problem(folder)
    return _problem_text(problem, shown) if problem else shown


def _problem_text(problem: str, shown: str) -> str:
    if problem == PROBLEM_UNSET:
        return "No folder chosen"
    if problem == PROBLEM_MISSING:
        return f"Not found: {shown}"
    if problem == PROBLEM_NOT_A_FOLDER:
        return f"Not a folder: {shown}"
    if problem == PROBLEM_IS_SELECTION:
        return "This is a Selection folder. Open its parent folder to keep photos."
    return f"Not writable: {shown}"


def describe_destination(resolution: DestinationResolution, home: Optional[Path] = None) -> str:
    """The line Settings shows for "where photos go now", from the same resolution Move/Copy use."""
    if resolution.problem == PROBLEM_IS_SELECTION and resolution.root is not None:
        return _problem_text(resolution.problem, "")
    if resolution.problem is not None:
        return describe_custom_folder(resolution, home)
    if resolution.path is None:
        return f"«{resolution.selection_name}» inside the folder you open (no folder open yet)"
    return display_path(resolution.path, home) + "/"
