"""Tests for scripts/check_ui_text_budget.py (docs/shared/UI_TEXT.md)."""

from __future__ import annotations

import io
import re
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_ui_text_budget as chk  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


def _cfg(mode: str = "warn") -> dict:
    cfg = chk.load_config()
    cfg["products"] = {"demo": {"mode": mode, "res": "res", "src": "src"}}
    return cfg


class Workspace:
    def __init__(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "src").mkdir()

    def strings(self, folder: str, body: str) -> None:
        d = self.root / "res" / folder
        d.mkdir(parents=True, exist_ok=True)
        (d / "strings.xml").write_text(f"<resources>{body}</resources>", encoding="utf-8")

    def kotlin(self, name: str, code: str) -> None:
        (self.root / "src" / name).write_text(textwrap.dedent(code), encoding="utf-8")

    def run(self, mode: str = "warn") -> chk.Result:
        return chk.run(_cfg(mode), self.root)["demo"]

    def close(self) -> None:
        self.tmp.cleanup()


def kinds(result: chk.Result) -> list[tuple[str, str]]:
    return sorted((f.kind, f.text) for f in result.findings)


class WordsAndPlaceholders(unittest.TestCase):
    def test_numbers_symbols_and_placeholders_are_not_words(self) -> None:
        self.assertEqual(chk.count_words("Delete all (%d)"), 2)
        self.assertEqual(chk.count_words("RAW + JPEG"), 2)
        self.assertEqual(chk.count_words("%1$d / %2$d"), 0)
        self.assertEqual(chk.count_words("Swipe right copies to ${folderName}"), 4)
        self.assertEqual(chk.count_words("Don't change settings"), 3)

    def test_characters_use_sample_values(self) -> None:
        sample = {"number": "12", "text": "Selection1"}
        self.assertEqual(chk.fill_placeholders("%d new", sample), "12 new")
        self.assertEqual(chk.fill_placeholders("To %1$s", sample), "To Selection1")
        self.assertEqual(chk.fill_placeholders("$count left", sample), "12 left")

    def test_longest_prefix_wins(self) -> None:
        budgets = chk.load_config()["budgets"]
        self.assertEqual(chk.element_for_key("snackbar_action_undo", budgets), "snackbar_action")
        self.assertEqual(chk.element_for_key("snackbar_no_new", budgets), "snackbar")
        self.assertEqual(chk.element_for_key("dialog_title_delete", budgets), "dialog_title")
        self.assertIsNone(chk.element_for_key("delete", budgets))


class Resources(unittest.TestCase):
    def setUp(self) -> None:
        self.ws = Workspace()

    def tearDown(self) -> None:
        self.ws.close()

    def test_within_budget_is_clean(self) -> None:
        self.ws.strings("values", '<string name="action_delete">Delete</string>'
                                  '<string name="cd_revert">Undo the last delete</string>')
        self.ws.strings("values-de", '<string name="action_delete">Löschen</string>')
        self.assertEqual(kinds(self.ws.run()), [])

    def test_over_words_over_chars_and_punctuation(self) -> None:
        self.ws.strings("values", '<string name="action_long">Tap here to delete this photo.</string>')
        self.ws.strings("values-de", '<string name="action_long">Hier tippen, um dieses Foto zu löschen.</string>')
        got = {f.kind for f in self.ws.run().findings}
        self.assertEqual(got, {"over-words", "over-chars", "sentence-punctuation"})

    def test_german_has_its_own_character_limit(self) -> None:
        # 17 characters: over the EN limit of 16, within the DE limit of 22.
        self.ws.strings("values", '<string name="action_x">Delete rejectsxyz</string>')
        self.ws.strings("values-de", '<string name="action_x">Aussortierte löschen</string>')
        r = self.ws.run()
        self.assertEqual([(f.kind, f.detail.split(":")[0]) for f in r.findings], [("over-chars", "en")])

    def test_plurals_are_checked_per_quantity(self) -> None:
        self.ws.strings("values", '<plurals name="dialog_title_new"><item quantity="one">%d new photo</item>'
                                  '<item quantity="other">%d new photos were found in this folder</item></plurals>')
        self.ws.strings("values-de", '<plurals name="dialog_title_new"><item quantity="one">%d neues Foto</item>'
                                     '<item quantity="other">%d neue Fotos</item></plurals>')
        r = self.ws.run()
        self.assertEqual({f.where.split(":")[-1] for f in r.findings}, {"dialog_title_new#other"})

    def test_missing_german_and_unclassified_key(self) -> None:
        self.ws.strings("values", '<string name="action_ok">Done</string><string name="random">Hello</string>'
                                  '<string name="app_name">Photo-Tok</string>')
        self.assertEqual({f.kind for f in self.ws.run().findings}, {"missing-de", "unclassified-key"})


class KotlinSources(unittest.TestCase):
    def setUp(self) -> None:
        self.ws = Workspace()

    def tearDown(self) -> None:
        self.ws.close()

    def test_hardcoded_visible_text_is_found(self) -> None:
        self.ws.kotlin("A.kt", '''
            @Composable fun A() {
                Text("Delete this photo")
                Text(text = "Stay")
                SettingsItem(title = "Reset All Seen History", description = "Across all folders")
                Icon(Icons.Default.Sync, contentDescription = "Reload folder")
            }
        ''')
        r = self.ws.run()
        self.assertEqual(kinds(r), [("hardcoded", t) for t in sorted(
            ["Across all folders", "Delete this photo", "Reload folder", "Reset All Seen History", "Stay"])])
        spoken = [e.en for e in r.entries if e.element == "spoken"]
        self.assertEqual(spoken, ["Reload folder"])

    def test_non_ui_literals_are_ignored(self) -> None:
        self.ws.kotlin("B.kt", '''
            const val PREF_KEY = "seen first run hints"
            private const val SEP = "\\u001E"
            val f = "PhotoTok_LeftSwipe"
            fun b(x: String) {
                Log.d("Tag", "Loaded the folder now")
                Modifier.testTag("viewer bottom bar")
                require(x.isNotEmpty()) { "Name must not be empty" }
                if (x.endsWith(".JPG")) throw IllegalStateException("Cross-source move is not supported")
                val mime = "image/*" // a MIME type
                val label = "Machine Readable Name" // ui-text: ignore — written into an index file
            }
        ''')
        self.assertEqual(kinds(self.ws.run()), [])

    def test_template_first_sentence_is_found_and_animation_labels_are_not(self) -> None:
        self.ws.kotlin("T.kt", '''
            fun t(name: String) {
                showFeedback("${name} is not connected")
                val pulse = rememberInfiniteTransition(label = "left swipe pulse")
                val a by animateFloatAsState(1f, label = "Bottom HUD alpha")
                val path = "$root/Selection"
            }
        ''')
        self.assertEqual(kinds(self.ws.run()), [("hardcoded", "${name} is not connected")])

    def test_icon_over_label_bar_item_is_flagged(self) -> None:
        self.ws.kotlin("Bar.kt", '''
            @Composable fun Item(label: String) {
                Box(Modifier.clickable { }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = label)
                        Text(text = label, fontSize = 10.sp)
                    }
                }
            }
        ''')
        r = self.ws.run()
        self.assertEqual([(f.kind, f.element) for f in r.findings], [("icon-with-label", "Column")])

    def test_labelled_button_is_flagged_and_icon_button_is_not(self) -> None:
        self.ws.kotlin("Btn.kt", '''
            @Composable fun C(onClick: () -> Unit) {
                IconButton(onClick = onClick) { Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.cd_reload)) }
                OutlinedButton(onClick = onClick) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text(stringResource(R.string.action_link))
                }
            }
        ''')
        r = self.ws.run()
        self.assertEqual([(f.kind, f.element) for f in r.findings], [("icon-with-label", "OutlinedButton")])


class ModesAndExitCodes(unittest.TestCase):
    def test_fail_mode_fails_and_warn_mode_returns_warn_exit_code(self) -> None:
        ws = Workspace()
        try:
            ws.strings("values", '<string name="action_x">Tap here to delete.</string>')
            cfg_path = ws.root / "cfg.json"
            import json
            for mode, extra, expected in (("fail", [], 1), ("warn", [], 0), ("warn", ["--warn-exit-code", "3"], 3)):
                cfg_path.write_text(json.dumps(_cfg(mode)), encoding="utf-8")
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    rc = chk.main(["--root", str(ws.root), "--config", str(cfg_path), *extra])
                self.assertEqual(rc, expected, mode)
        finally:
            ws.close()


class DocumentAndConfigAgree(unittest.TestCase):
    def test_budget_table_in_ui_text_md_matches_json(self) -> None:
        doc = (REPO / "docs" / "shared" / "UI_TEXT.md").read_text(encoding="utf-8")
        rows = {}
        for line in doc.splitlines():
            m = re.match(r"\|[^|]+\|\s*`([a-z_]+)`\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|", line)
            if m:
                rows[m.group(1)] = tuple(None if v.strip() in ("—", "not counted") else int(v)
                                         for v in m.group(2, 3, 4))
        budgets = chk.load_config()["budgets"]
        expected = {b["prefix"]: (b["words"], b["en"], b["de"]) for b in budgets.values()}
        self.assertEqual(rows, expected)

    def test_every_configured_product_path_exists(self) -> None:
        for name, p in chk.load_config()["products"].items():
            self.assertTrue((REPO / p["src"]).is_dir(), name)


if __name__ == "__main__":
    unittest.main()
