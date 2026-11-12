"""Blitzlens applies the shared visible-text budget (``docs/shared/UI_TEXT.md`` § Budgets).

``scripts/check_ui_text_budget.py`` does not cover Blitzlens yet, so this is its product-local
stand-in. It reads the shared table (``scripts/ui_text_budget.json``, read-only here) and applies
it to

* the visible labels of the main window (action buttons, view switcher, counters), measured on
  the real widgets, and
* every message the product can show (``_notify``, ``show_toast``, ``show_outcome``), harvested
  from the source with ``ast`` so a new message is checked without anyone adding it to a list.

Element mapping: action buttons -> ``action``; the 1-Up/2-Up/3-Up switcher -> ``chip``; counters
-> ``label``; messages -> ``error`` (the "error or feedback message" budget) for outcomes of
file operations and errors, ``snackbar`` for the info toasts. Blitzlens is English only, so only
the English limits apply. Tooltips and accessible names are spoken text and are not counted.

Adheres to:
- R-LINUX-UI-10: Tactile bottom action bar (labels: `[USER 2026-10-24]` two words, keys in tooltips)
"""

import ast
import json
import re
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import window as window_module

ROOT = Path(__file__).resolve().parents[4]
PRODUCT_SRC = Path(__file__).resolve().parents[2] / "src" / "photo_selector_linux"
BUDGET = json.loads((ROOT / "scripts" / "ui_text_budget.json").read_text(encoding="utf-8"))
UI_TEXT_DOC = (ROOT / "docs" / "shared" / "UI_TEXT.md").read_text(encoding="utf-8")

WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*", re.UNICODE)
SENTENCE_END = re.compile(r"[.!?:]\s*$")
SAMPLE_NUMBER = BUDGET["placeholder_sample"]["number"]
SAMPLE_TEXT = BUDGET["placeholder_sample"]["text"]

STUBBED = isinstance(window_module.Gtk, MagicMock) or not window_module.HAS_GI


def limits(element: str):
    entry = BUDGET["budgets"][element]
    return entry["words"], entry["en"]


def words(text: str) -> int:
    return len(WORD.findall(text))


def violations(element: str, text: str):
    max_words, max_chars = limits(element)
    found = []
    if element == "action" and text in ACTION_BUDGET_EXCEPTIONS:
        return found
    if words(text) > max_words:
        found.append(f"{words(text)} words (max {max_words})")
    if len(text) > max_chars:
        found.append(f"{len(text)} characters (max {max_chars})")
    if element in BUDGET["no_sentence_punctuation"] and SENTENCE_END.search(text):
        found.append("sentence punctuation")
    return found


#: [USER 2026-11-12] decision #27 wins over the two-word action budget for this one string
#: (docs/shared/UI_TEXT.md: an exception is written down in the product's design guide; here and
#: in R-LINUX-UI-10). Nothing else is exempt.
ACTION_BUDGET_EXCEPTIONS = {"Move to Selection"}


# ── the shared table is the one this test reads ──────────────────────────────


def test_the_budget_table_is_the_documented_one():
    """The JSON this test applies equals the table of UI_TEXT.md (the doc says the two must agree)."""
    for element, entry in BUDGET["budgets"].items():
        if entry["words"] is None:
            continue
        row = re.search(rf"\| `{re.escape(entry['prefix'])}` \| (\d+) \| (\d+) \| (\d+) \|", UI_TEXT_DOC)
        assert row, f"{element} is not in UI_TEXT.md"
        assert [int(g) for g in row.groups()] == [entry["words"], entry["en"], entry["de"]], element


# ── the messages ─────────────────────────────────────────────────────────────

MESSAGE_CALLS = {"_notify": "notify", "show_toast": "toast", "show_outcome": "outcome"}
SOURCES = ("viewmodels/culling_workspace.py", "ui/window.py", "ui/action_bar.py", "ui/tools_dialogs.py",
           "ui/preferences.py", "ui/canvas.py")


def _render(node: ast.AST, source: str) -> str:
    """The text of a string literal or f-string with each placeholder filled with a typical value."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        out = []
        for part in node.values:
            if isinstance(part, ast.Constant):
                out.append(str(part.value))
            else:
                expression = ast.get_source_segment(source, part.value) or ""
                if isinstance(part.value, ast.IfExp):  # a plural ending: "operation{'s' if n != 1 else ''}"
                    out.append(_render(part.value.body, source))
                    continue
                if "_name_with_companions" in expression:  # "<name> + <n>" when the bundle has companions
                    out.append(f"{SAMPLE_TEXT} + {SAMPLE_NUMBER}")
                    continue
                numeric = part.format_spec is not None or re.search(
                    r"count|total|pending|len\(|threshold|related|\bn\b", expression
                )
                out.append(SAMPLE_NUMBER if numeric else SAMPLE_TEXT)
        return "".join(out)
    if isinstance(node, ast.IfExp):
        return _render(node.body, source)
    return ""


def _severity(call: ast.Call, kind: str) -> str:
    if kind == "toast":
        return "toast"
    for keyword in call.keywords:
        if keyword.arg == "severity" and isinstance(keyword.value, ast.Constant):
            return keyword.value.value
    if len(call.args) > 1 and isinstance(call.args[1], ast.Constant):
        return call.args[1].value
    return "info"


def harvest():
    """(where, severity, text) of every message literal the product can show."""
    found = []
    for relative in SOURCES:
        path = PRODUCT_SRC / relative
        source = path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not node.args:
                continue
            name = node.func.attr if isinstance(node.func, ast.Attribute) else getattr(node.func, "id", "")
            if name not in MESSAGE_CALLS:
                continue
            text = _render(node.args[0], source)
            if text:
                found.append((f"{relative}:{node.lineno}", _severity(node, MESSAGE_CALLS[name]), text))
        # Messages built in helpers and handed to _notify() as a call result.
        if relative.endswith("culling_workspace.py"):
            for node in ast.walk(tree):
                if isinstance(node, ast.FunctionDef) and node.name == "_transfer_message":
                    for sub in ast.walk(node):
                        if isinstance(sub, ast.Return):
                            text = _render(sub.value, source)
                            if text:
                                found.append((f"{relative}:{sub.lineno}", "status", text))
    return found


def _element_of(severity: str) -> str:
    return "snackbar" if severity in ("info", "toast") else "error"


MESSAGES = harvest()


def test_the_harvest_finds_the_messages_the_product_shows():
    """The check is only worth something if it sees the outcome and error messages."""
    texts = [t for _w, _s, t in MESSAGES]
    assert len(MESSAGES) >= 20, texts
    assert any(t.endswith(" to Trash") for t in texts)
    assert any(t.endswith(" to Selection1") for t in texts)  # "... to {place}", place ends in "/"
    assert any(s == "error" for _w, s, _t in MESSAGES)
    assert any(s == "stranded" for _w, s, _t in MESSAGES)
    assert any(s == "toast" for _w, s, _t in MESSAGES)


def _known_over_budget(where, severity, text):
    """A message that exceeds its budget on unmodified main: strict, so fixing it turns this red."""
    return violations(_element_of(severity), text)


@pytest.mark.parametrize("where,severity,text", MESSAGES, ids=[f"{w}" for w, _s, _t in MESSAGES])
def test_every_message_is_within_its_budget(where, severity, text, request):
    over = violations(_element_of(severity), text)
    if over:
        request.applymarker(
            pytest.mark.xfail(
                strict=True,
                reason=f"F-BL-053: {_element_of(severity)} budget of UI_TEXT.md exceeded by "
                f"{text!r} ({', '.join(over)}); {where}",
            )
        )
    assert not over, f"{text!r}: {', '.join(over)}"


# ── the visible labels of the main window (real widgets) ─────────────────────


@pytest.fixture
def main_window(tmp_path):
    if STUBBED:
        pytest.skip("needs real Gtk/Adw under a display server")
    import gtk_layout_probe as probe
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui.window import MainWindow

    win = MainWindow(settings=SettingsStore(path=tmp_path / "settings.json", auto_load=False))
    probe.show_at(win, (1366, 768))
    yield win, probe
    win.destroy()
    probe.pump(0.1)


def test_action_buttons_the_view_switcher_and_counters_are_within_budget(main_window):
    from gi.repository import Adw, Gtk

    win, probe = main_window
    checked = []
    for widget in probe.walk(win):
        if not widget.get_mapped():
            continue
        if isinstance(widget, Gtk.ToggleButton) and widget.get_name().startswith("btn_mode_"):
            element = "chip"
        elif isinstance(widget, Gtk.Button) and widget.get_name().startswith("btn_"):
            element = "action"
        elif isinstance(widget, Gtk.Label) and (
            widget.has_css_class("status-counter-badge")
            or widget.has_css_class("status-counter-selected")
            or widget.has_css_class("status-counter-trashed")
            or widget.has_css_class("status-counter-remaining")
            or widget.has_css_class("photo-counter-pill")
        ):
            element = "label"
        else:
            continue
        text = widget.get_label()
        if text is None and isinstance(widget.get_child(), Adw.ButtonContent):
            text = widget.get_child().get_label()  # the icon button of decision #27
        checked.append((element, text or ""))
    problems = [f"{element}: {text!r} {', '.join(violations(element, text))}"
                for element, text in checked if violations(element, text)]
    assert not problems, "\n".join(problems)
    labels = {text for _e, text in checked}
    assert {"Move to Selection", "Copy", "Trash", "Undo", "1-Up", "2-Up", "3-Up", "0 Selected", "Photo 0 of 0"} <= labels, checked


def test_the_budget_check_goes_red_on_a_planted_long_label():
    """The same rule on a label no one should write: it must report words and characters."""
    assert violations("action", "Move photo and companions to Selection") != []
    assert violations("chip", "Single photo view") != []
    assert violations("action", "Keep.") == ["sentence punctuation"]
    assert violations("action", "Keep") == []
    assert violations("action", "Move to Selection") == []  # the one exception, decision #27
    assert violations("action", "Move to Selection folder") != []
