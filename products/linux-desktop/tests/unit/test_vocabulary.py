"""One vocabulary on screen (Blitzlens review 2026-11-11, plan 0.2 and 0.3).

The words below are retired: folder, photo, Sharpness, Selection and ACTIVE / NEXT / PREVIOUS
replace them. The scan reads every user-facing string literal of the UI and the view model, so a
new label that brings one back fails here, on the stubs as on real GTK.
"""

import ast
import re
from pathlib import Path

import pytest

from photo_selector_linux.ui import styling

SRC = Path(__file__).resolve().parents[2] / "src" / "photo_selector_linux"
RETIRED = re.compile(
    r"\b(album|library|directory|photographs?|candidates?|focus|champion|challenger|cryptographic|sha-256)\b",
    re.IGNORECASE,
)
#: Literals that are not words on screen: CSS (the ``focus`` pseudo-class and its selectors).
NOT_ON_SCREEN = ("{", ":focus", "focus-")


def _literals(path: Path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    docstrings = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            body = node.body
            if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant):
                docstrings.add(id(body[0].value))
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docstrings:
            yield node.lineno, node.value


def _ui_and_viewmodel_files():
    return sorted((SRC / "ui").glob("*.py")) + sorted((SRC / "viewmodels").glob("*.py"))


def test_no_retired_word_is_a_user_facing_string():
    found = []
    for path in _ui_and_viewmodel_files():
        if path.name == "lab_hooks.py":
            continue  # test-harness identifiers, not words on screen
        for lineno, text in _literals(path):
            if " " not in text.strip() or any(mark in text for mark in NOT_ON_SCREEN):
                continue
            if RETIRED.search(text):
                found.append(f"{path.name}:{lineno}: {text[:70]!r}")
    assert not found, "retired vocabulary on screen:\n" + "\n".join(found)


def test_no_menu_label_types_its_own_accelerator():
    """Menu rows and section titles only: tooltips name their key on purpose (R-LINUX-UI-10)."""
    found = []
    for path in _ui_and_viewmodel_files():
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)):
                continue
            if node.func.attr not in ("append", "append_section", "new") or not node.args:
                continue
            first = node.args[0]
            if not (isinstance(first, ast.Constant) and isinstance(first.value, str)):
                continue
            owner = ast.unparse(node.func.value)
            if node.func.attr == "new" and "MenuItem" not in owner:
                continue
            if "(Ctrl" in first.value or re.search(r"\([A-Z]\)$", first.value.strip()):
                found.append(f"{path.name}:{node.lineno}: {first.value!r}")
    assert not found, "GTK draws accelerators; a typed hint repeats them:\n" + "\n".join(found)


@pytest.mark.parametrize("mode", [1, 2, 3])
def test_both_compare_views_use_active_previous_next(mode):
    labels = {
        styling.get_slot_role_label(mode, offset, is_active=(offset == 0))
        for offset in (-1, 0, 1)
    }
    assert labels <= {"ACTIVE", "PREVIOUS", "NEXT"}
