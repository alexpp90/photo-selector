"""Every app selector in the Android lab flows names something the product's source defines.

The flows select app controls by test tag and dialog buttons by their text (dialog windows do
not export test tags), found in the Kotlin sources or the string resources. Both are owned by the product, which can change them without
running the lab: on 2026-10-18 a copy pass renamed PhotoTok's "Start with Newest" and "Link
actions across both" buttons, and every journey's `_ensure_folder.yaml` would have broken on the
next device run. This check fails in the CI mirror instead.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.paths import repo_root  # noqa: E402

ROOT = repo_root()
PRODUCTS = ("android-desktop", "phototok")
SELECTOR_RE = re.compile(r'^\s*-?\s*(id|text):\s*"([^"]+)"', re.M)
# Strings the lab owns: fixture folder names, and the ISO every fixture writes into its EXIF
# (`lab/fixtures.py`). System UI is selected by its resource id and skipped.
LAB_OWNED_TEXT = {"PSTLab", "work", "ISO 400"}
# Text a flow reads from system UI that happens to equal one of the app's English labels; the
# system shows it untranslated, so the per-locale check does not apply.
SYSTEM_TEXT = {
    "Camera": "J-TOK-06: DocumentsUI's breadcrumb shows the folder name DCIM/Camera",
}
# Tags a flow already uses, guarded by `when: visible`, that an open product PR adds. Each entry
# fails the test as soon as the tag is in the source, so the exception ends with its reason.
AWAITED_TAGS: dict[str, str] = {}


# Calls that open a window of their own. Compose does not export test tags from such a window as
# resource ids (`testTagsAsResourceId` is set on the activity's window only), so a flow cannot
# select a control inside one by `id:` (playbook-run-device-lab, "Dialogs and popups are separate
# windows"; 2026-11-05: Frameduel's `dialog_keep_uncompleted_deletions` never matched and six
# journeys broke behind it). `ModalBottomSheet` is left out: no capture shows whether it exports.
WINDOW_CALLS = frozenset({
    "AlertDialog", "BasicAlertDialog", "Dialog", "DatePickerDialog", "TimePickerDialog",
    "DropdownMenu", "ExposedDropdownMenu", "Popup",
})


def string_literals_with_window(source: str) -> list[tuple[str, bool]]:
    """Every plain string literal of Kotlin `source`, and whether it sits inside a window call.

    A small scanner, not a parser: comments, strings and char literals are skipped, `(` and `{`
    open a scope named after the call before them (`Foo(...) { ... }` names its trailing lambda
    `Foo`), and a literal is "in a window" when any open scope is one of `WINDOW_CALLS`. Lexical,
    so a control passed to a dialog from another file as a lambda parameter is not seen: the
    guard is for the tag written at the use site, which is where every case so far was.
    """
    out: list[tuple[str, bool]] = []
    stack: list[str] = []
    last_closed = ""  # the call a `)` just closed, for `) {`
    i, n = 0, len(source)
    trailing_name = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*$")

    def name_before(pos: int) -> str:
        m = trailing_name.search(source[max(0, pos - 80):pos])
        return m.group(1) if m else ""

    while i < n:
        c = source[i]
        if source.startswith("//", i):
            i = source.find("\n", i) if "\n" in source[i:] else n
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif c == '"':
            j = i + 1
            while j < n and source[j] != '"':
                j += 2 if source[j] == "\\" else 1
            out.append((source[i + 1:j], any(f in WINDOW_CALLS for f in stack)))
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and source[j] != "'":
                j += 2 if source[j] == "\\" else 1
            i = j + 1
        else:
            if c == "(":
                stack.append(name_before(i))
            elif c == "{":
                stack.append(last_closed if source[:i].rstrip().endswith(")") else name_before(i))
            elif c in ")}" and stack:
                closed = stack.pop()
                last_closed = closed if c == ")" else ""
            i += 1
    return out


def window_only_literals(product: str) -> set[str]:
    """String literals a product's Kotlin sources contain only inside windows of their own."""
    seen: dict[str, bool] = {}
    for path in (ROOT / "products" / "android" / product / "src").rglob("*.kt"):
        for text, in_window in string_literals_with_window(path.read_text(encoding="utf-8", errors="replace")):
            seen[text] = seen.get(text, True) and in_window
    return {text for text, only in seen.items() if only}


def alternatives(selector: str) -> list[str]:
    """Names a selector accepts; Maestro selectors are regexes.

    `delete_button_(expanded|compact)` -> both names; `Show new|Start with Newest` -> both texts
    (a flow that accepts an old and a new label while branches catch up).
    """
    m = re.fullmatch(r"(.*)\(([^()]+)\)(.*)", selector)
    if m:
        return [m.group(1) + alt + m.group(3) for alt in m.group(2).split("|")]
    return selector.split("|")


def product_strings(product: str) -> str:
    """Kotlin sources plus every string resource (EN and translations), each as a `"…"` literal.

    PhotoTok's visible text lives in `res/values*/strings.xml` (docs/shared/UI_TEXT.md), so a
    dialog button's label is a resource, not a Kotlin literal.
    """
    import xml.etree.ElementTree as ET

    base = ROOT / "products" / "android" / product
    parts = [p.read_text(encoding="utf-8", errors="replace") for p in (base / "src").rglob("*.kt")]
    for xml in sorted((base / "res").glob("values*/strings.xml")):
        for el in ET.parse(xml).getroot().iter():
            if el.tag in ("string", "item") and el.text:
                parts.append(f'"{el.text.strip()}"'.replace("\\'", "'"))
    return "\n".join(parts)


def string_translations(product: str) -> dict[str, list[dict[str, str]]]:
    """English text -> one {locale: text} map per string resource that has that English text.

    The default `values/` is "en"; `values-de/` is "de". Only resources present in `values/` count.
    """
    import xml.etree.ElementTree as ET

    by_locale: dict[str, dict[str, str]] = {}
    for xml in sorted((ROOT / "products" / "android" / product / "res").glob("values*/strings.xml")):
        locale = xml.parent.name.partition("-")[2] or "en"
        for el in ET.parse(xml).getroot().iter("string"):
            if el.text and el.get("name"):
                by_locale.setdefault(locale, {})[el.get("name")] = el.text.strip().replace("\\'", "'")
    english = by_locale.get("en", {})
    result: dict[str, list[dict[str, str]]] = {}
    for name, text in english.items():
        result.setdefault(text, []).append({loc: texts[name] for loc, texts in by_locale.items() if name in texts})
    return result


class TestFlowSelectors(unittest.TestCase):
    def test_translated_text_selectors_cover_every_locale(self) -> None:
        """A Jackpix text selector matches the label in every language the app ships.

        The lab phones run German as well as English, and Jackpix follows the system language.
        On 2026-11-05 J-TOK-03 and J-TOK-04 broke on the German Pixel 4a: the confirm steps
        tapped `text: "Delete"`, optionally, so the "Löschen" dialog stayed open and the next
        step failed on the revert button.
        """
        missing: list[str] = []
        translations = string_translations("phototok")
        for flow in sorted((ROOT / "products" / "android" / "phototok" / "tests" / "e2e").rglob("*.yaml")):
            for kind, value in SELECTOR_RE.findall(flow.read_text(encoding="utf-8")):
                if kind != "text" or value in LAB_OWNED_TEXT or value in SYSTEM_TEXT:
                    continue
                accepted = set(alternatives(value))
                for alt in accepted:
                    resources = translations.get(alt)
                    if resources and not any(set(r.values()) <= accepted for r in resources):
                        wanted = sorted(set().union(*(r.values() for r in resources)) - accepted)
                        missing.append(f"{flow.relative_to(ROOT)}: text {value!r} lacks {wanted}")
        self.assertEqual(missing, [], "text selectors that match only some of the app's languages")

    def test_no_flow_selects_by_id_a_tag_only_a_window_of_its_own_sets(self) -> None:
        """`id:` cannot match a control inside a Dialog, DropdownMenu or Popup: use its text (Frameduel is English-only)."""
        wrong: list[str] = []
        for product in PRODUCTS:
            in_windows = window_only_literals(product)
            for flow in sorted((ROOT / "products" / "android" / product / "tests" / "e2e").rglob("*.yaml")):
                for kind, value in SELECTOR_RE.findall(flow.read_text(encoding="utf-8")):
                    if kind == "id" and ":id/" not in value:
                        wrong += [f"{flow.relative_to(ROOT)}: id {alt!r}" for alt in alternatives(value) if alt in in_windows]
        self.assertEqual(wrong, [], "ids that only a dialog/menu/popup window defines are not exported")

    def test_the_window_scan_finds_the_uncompleted_deletions_tags_in_the_real_source(self) -> None:
        # The case that broke six journeys: both buttons sit in the AlertDialog of SelectorScreen.kt.
        in_windows = window_only_literals("android-desktop")
        self.assertIn("dialog_keep_uncompleted_deletions", in_windows)
        self.assertIn("dialog_confirm_uncompleted_deletions", in_windows)
        self.assertNotIn("image_region", in_windows)  # an ordinary tag of the main window

    def test_the_window_scan_on_a_snippet(self) -> None:
        kotlin = '''
            // Dialog( "in_a_comment"
            fun Screen() {
                Box(Modifier.testTag("in_main")) { Text("hello") }
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = { TextButton(modifier = Modifier.testTag("in_dialog")) { Text("Keep") } },
                ) { Text("body_in_dialog") }
                Popup { Box(Modifier.testTag("in_popup")) }
                Row { Text("after_the_windows") }
                val raw = """Dialog("x")"""
            }
        '''
        found = dict(string_literals_with_window(kotlin))
        self.assertEqual(
            {k: v for k, v in found.items()},
            {"in_main": False, "hello": False, "in_dialog": True, "Keep": True, "body_in_dialog": True,
             "in_popup": True, "after_the_windows": False},
        )

    def test_translations_read_every_locale(self) -> None:
        delete = string_translations("phototok")["Delete"]
        self.assertIn({"en": "Delete", "de": "Löschen"}, delete)

    def test_every_app_selector_exists_in_the_product_source(self) -> None:
        missing: list[str] = []
        for product in PRODUCTS:
            source = product_strings(product)
            for flow in sorted((ROOT / "products" / "android" / product / "tests" / "e2e").rglob("*.yaml")):
                for kind, value in SELECTOR_RE.findall(flow.read_text(encoding="utf-8")):
                    if ":id/" in value or (kind == "text" and value in LAB_OWNED_TEXT):
                        continue
                    if kind == "id" and value in AWAITED_TAGS:
                        continue
                    if not any(f'"{alt}"' in source for alt in alternatives(value)):
                        missing.append(f"{flow.relative_to(ROOT)}: {kind} {value!r}")
        self.assertEqual(missing, [], "flow selectors the product no longer defines")

    def test_awaited_tags_are_still_missing(self) -> None:
        source = "\n".join(product_strings(p) for p in PRODUCTS)
        landed = [f"{tag} ({pr})" for tag, pr in AWAITED_TAGS.items() if f'"{tag}"' in source]
        self.assertEqual(landed, [], "these tags landed: drop them from AWAITED_TAGS")

    def test_alternatives_expand_one_group(self) -> None:
        self.assertEqual(alternatives("delete_button_(expanded|compact)"), ["delete_button_expanded", "delete_button_compact"])
        self.assertEqual(alternatives("viewer.photo"), ["viewer.photo"])
        self.assertEqual(alternatives("Show new|Start with Newest"), ["Show new", "Start with Newest"])


if __name__ == "__main__":
    unittest.main()
