"""Unit tests for the usability panel: metrics, scenario driver, panel packets and report."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.paths import repo_root  # noqa: E402
from lab.usability import driver as drv  # noqa: E402
from lab.usability.capture import load_scenarios  # noqa: E402
from lab.usability.metrics import screen_metrics  # noqa: E402
from lab.usability.panel import (  # noqa: E402
    build_panel,
    expected_screen_keys,
    extract_json,
    render_walkthrough_md,
    validate_persona,
)
from lab.usability.report import coverage_problems, screen_matrix, write_report  # noqa: E402


def _scratch(name: str) -> Path:
    """A private folder per run under the lab root; concurrent worktree runs must not collide."""
    Path("/tmp/pstlab").mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix=f"{name}_", dir="/tmp/pstlab"))


def _xml(*nodes: str, size: str = "[0,0][1080,1920]") -> str:
    inner = "".join(nodes)
    return (
        "<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\">"
        f'<node resource-id="" text="" content-desc="" clickable="false" bounds="{size}">{inner}</node>'
        "</hierarchy>"
    )


def _node(text: str = "", rid: str = "", desc: str = "", bounds: str = "[0,0][100,100]", clickable: bool = False, children: str = "") -> str:
    return (
        f'<node resource-id="{rid}" text="{text}" content-desc="{desc}" clickable="{"true" if clickable else "false"}" '
        f'bounds="{bounds}">{children}</node>'
    )


PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde"
    b"\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\xc9\xfe\x92\xef\x00\x00\x00\x00IEND\xaeB`\x82"
)


class FakeAdb:
    """Scripted stand-in for AdbClient: a queue of UI dumps and a log of input commands."""

    serial = "FAKE"

    def __init__(self, screens: list[str], top: str = "com.phototok.lab", procs: str = "NAME\ninit\n") -> None:
        self.screens = list(screens)
        self.inputs: list[tuple[str, ...]] = []
        self.top = top
        self.procs = procs

    def _current(self) -> str:
        return self.screens[0] if len(self.screens) == 1 else self.screens.pop(0)

    def run_bytes(self, *args: str, timeout: int = 30) -> bytes:
        return self._current().encode("utf-8") + b"\nUI hierchary dumped to: /dev/tty"

    def run(self, *args: str, timeout: int = 30) -> str:
        if args[:2] == ("shell", "input"):
            self.inputs.append(args[2:])
            return ""
        if args[:3] == ("shell", "dumpsys", "activity"):
            return f"  topResumedActivity=ActivityRecord{{1 u0 {self.top}/.Main t1}}\n"
        if "resolve-activity" in args:
            return "priority=0\ncom.android.launcher3/.Launcher\n"
        if args[:2] == ("shell", "ps"):
            return self.procs
        return ""

    def screencap(self, dest: Path | str) -> Path:
        Path(dest).write_bytes(PNG_1PX)
        return Path(dest)

    def density(self) -> int:
        return 420

    def start(self, app_id: str) -> str:
        self.inputs.append(("start", app_id))
        return ""

    def key(self, code: str) -> str:
        self.inputs.append(("key", code))
        return ""

    def home(self) -> str:
        return ""


class TestMetrics(unittest.TestCase):
    def test_counts_words_targets_and_flags_heavy_text(self) -> None:
        long_text = " ".join(["word"] * 25)
        xml = _xml(
            _node(text="Delete Permanently", bounds="[0,0][500,80]"),
            _node(text=long_text, bounds="[0,100][900,400]"),
            _node(text="Delete Permanently", bounds="[0,0][500,80]"),  # same text, same place: counted once
            _node(rid="ok", clickable=True, bounds="[0,500][300,700]", children=_node(text="OK", bounds="[10,510][100,560]")),
            _node(rid="tiny", desc="Settings", clickable=True, bounds="[0,800][60,860]"),
            _node(rid="mystery", clickable=True, bounds="[200,800][500,1100]"),
        )
        m = screen_metrics(xml, density_dpi=420)
        self.assertEqual(m["words"], 2 + 25 + 1)
        self.assertEqual(m["text_blocks"], 3)
        self.assertEqual(m["longest_block_words"], 25)
        self.assertEqual(m["controls"], 3)
        self.assertEqual(m["small_targets"], 1)  # 60 px at 420 dpi is ~23 dp
        self.assertEqual(m["unlabeled_controls"], 1)
        self.assertTrue(m["text_heavy"])
        self.assertAlmostEqual(m["reading_seconds"], round(28 / 3.5, 1))

    def test_short_screen_is_not_heavy(self) -> None:
        m = screen_metrics(_xml(_node(text="Swipe up", bounds="[0,0][300,80]")))
        self.assertFalse(m["text_heavy"])
        self.assertEqual(m["words"], 2)


class TestDriver(unittest.TestCase):
    def setUp(self) -> None:
        self.base = _scratch("test_usability_driver")

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_find_and_describe(self) -> None:
        nodes = drv.parse_nodes(_xml(_node(text="Use this folder", rid="button1", bounds="[700,1600][1040,1740]", clickable=True)))
        hit = drv.find_node(nodes, {"text": "use this", "contains": True})
        self.assertIsNotNone(hit)
        self.assertEqual(hit.center, (870, 1670))
        self.assertIsNone(drv.find_node(nodes, {"text": "use this"}))
        self.assertEqual(drv.describe_action({"tap": {"id": "x", "label": "Folder or Cloud"}}), "Tapped 'Folder or Cloud'")
        self.assertEqual(drv.describe_action({"swipe": "left"}), "Swiped left")
        self.assertIsNone(drv.describe_action({"wait_for": {"id": "x"}}))

    def test_run_taps_center_logs_actions_and_captures(self) -> None:
        screen = _xml(_node(text="GOT IT", rid="got_it", bounds="[0,1400][1080,1600]", clickable=True))
        adb = FakeAdb([screen])
        d = drv.ScenarioDriver(adb, "com.phototok.lab", self.base, settle_ms=0)  # type: ignore[arg-type]
        result = d.run(
            {
                "id": "U-1",
                "steps": [
                    {"tap": {"id": "got_it", "label": "GOT IT"}, "settle_ms": 0},
                    {"swipe": "up", "settle_ms": 0},
                    {"screen": "feed", "intent": "look", "expected": "swipe up"},
                    {"screen": "dialog", "when": {"text": "Never shown", "timeout_ms": 0}},
                ],
            }
        )
        self.assertEqual(result["status"], "pass")
        self.assertIn(("tap", "540", "1500"), adb.inputs)
        self.assertEqual([s["name"] for s in result["screens"]], ["feed"])  # `when` absent: skipped
        shot = result["screens"][0]
        self.assertEqual(shot["actions_before"], ["Tapped 'GOT IT'", "Swiped up"])
        self.assertEqual(shot["metrics"]["words"], 2)
        self.assertTrue((self.base / "01_feed.png").is_file())
        self.assertTrue((self.base / "scenario.json").is_file())

    def test_missing_element_marks_scenario_broken(self) -> None:
        adb = FakeAdb([_xml()])
        d = drv.ScenarioDriver(adb, "com.phototok.lab", self.base, settle_ms=0)  # type: ignore[arg-type]
        result = d.run({"id": "U-2", "steps": [{"tap": {"id": "nope", "timeout_ms": 0}}]})
        self.assertEqual(result["status"], "broken")
        self.assertEqual(result["screens"][-1]["name"], "broken")

    def test_foreign_app_in_front_stops_the_walkthrough(self) -> None:
        adb = FakeAdb([_xml()], top="com.photoselectortoolbox")
        d = drv.ScenarioDriver(adb, "com.phototok.lab", self.base, settle_ms=0)  # type: ignore[arg-type]
        result = d.run({"id": "U-3", "steps": [{"screen": "feed"}]})
        self.assertEqual(result["status"], "broken")
        self.assertIn("taken over", result["error"])

    def test_device_free_check(self) -> None:
        with self.assertRaises(drv.DeviceBusy):
            drv.check_device_free(FakeAdb([_xml()], procs="NAME\ncom.photoselectortoolbox.test\n"), "com.phototok.lab")  # type: ignore[arg-type]
        with self.assertRaises(drv.DeviceBusy):
            drv.check_device_free(FakeAdb([_xml()], top="com.whatsapp"), "com.phototok.lab")  # type: ignore[arg-type]
        drv.check_device_free(FakeAdb([_xml()], top="com.android.launcher3"), "com.phototok.lab")  # type: ignore[arg-type]

    def test_device_lock_is_exclusive_and_names_its_holder(self) -> None:
        with drv.device_lock("SER1", "walk A", lock_dir=self.base) as path:
            self.assertIn("walk A", path.read_text(encoding="utf-8"))
            with self.assertRaises(drv.DeviceBusy) as ctx:
                with drv.device_lock("SER1", "walk B", lock_dir=self.base):
                    pass
            self.assertIn("walk A", str(ctx.exception))
            with drv.device_lock("SER2", "other phone", lock_dir=self.base):
                pass
        with drv.device_lock("SER1", "walk B", lock_dir=self.base):  # released on exit
            pass

    def test_push_fixture_refuses_paths_outside_the_lab(self) -> None:
        with self.assertRaises(ValueError):
            drv.push_fixture(FakeAdb([_xml()]), self.base, "/sdcard/DCIM/Camera")  # type: ignore[arg-type]


def _walkthrough() -> dict[str, Any]:
    def screen(idx: int, name: str, words: int) -> dict[str, Any]:
        return {
            "index": idx,
            "name": name,
            "image": f"{idx:02d}_{name}.png",
            "intent": f"want {name}",
            "expected": f"SECRET-EXPECTED-{name}",
            "system_screen": name == "picker",
            "actions_before": ["Swiped up"] if idx > 1 else [],
            "metrics": {"texts": [f"SECRET-TEXT-{name}"], "words": words, "text_blocks": 1, "longest_block_words": words,
                        "reading_seconds": round(words / 3.5, 1), "controls": 1, "small_targets": 0, "unlabeled_controls": 0,
                        "text_heavy": words > 40},
        }

    return {
        "product": "phototok",
        "captured_at": "2026-10-18T00:00:00+00:00",
        "duration_s": 1.0,
        "device": {"model": "H3113", "android": "17", "size": "1080x1920", "density": 420},
        "scenarios": [
            {"scenario": "U-1", "title": "First", "goal": "Open photos", "status": "pass", "error": None,
             "screens": [screen(1, "landing", 13), screen(2, "picker", 49), screen(3, "guide", 81)]},
        ],
    }


def _answer(persona: str, verdicts: list[str]) -> dict[str, Any]:
    names = ["landing", "picker", "guide"]
    return {
        "persona": persona,
        "screens": [
            {"screen": f"U-1/{n}", "first_impression": "a screen", "would_do": "tap", "read_text": "some", "verdict": v,
             "issues": [{"what": "too long", "severity": "major", "kind": "too-much-text"}] if v == "bad" else [], "liked": []}
            for n, v in zip(names, verdicts)
        ],
        "overall": {"would_keep_using": "maybe", "why": "ok", "top_problems": [], "top_strengths": [], "one_change": "less text"},
    }


class TestPanelAndReport(unittest.TestCase):
    def setUp(self) -> None:
        self.base = _scratch("test_usability_panel")
        self.run_dir = self.base / "run"
        scen = self.run_dir / "U-1"
        scen.mkdir(parents=True)
        for name in ("01_landing.png", "02_picker.png", "03_guide.png"):
            (scen / name).write_bytes(PNG_1PX)
        (self.run_dir / "walkthrough.json").write_text(json.dumps(_walkthrough()), encoding="utf-8")
        self.priv = self.base / "priv"
        (self.priv / "personas" / "phototok").mkdir(parents=True)
        (self.priv / "personas" / "phototok" / "commuter.md").write_text("# Mia\n", encoding="utf-8")
        (self.priv / "personas" / "phototok" / "newcomer.md").write_text("# Renate\n", encoding="utf-8")
        (self.priv / "panel.md").write_text("panel\n", encoding="utf-8")

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_persona_packet_hides_expected_action_and_extracted_text(self) -> None:
        out = build_panel(self.run_dir, priv=self.priv)
        md = (out / "walkthrough.md").read_text(encoding="utf-8")
        self.assertNotIn("SECRET-EXPECTED", md)
        self.assertNotIn("SECRET-TEXT", md)
        self.assertIn("images/U-1_03_guide.png", md)
        self.assertIn("Android system screen", md)
        self.assertTrue((out / "images" / "U-1_02_picker.png").is_file())
        self.assertEqual(sorted(p.name for p in (out / "personas").iterdir()), ["commuter.md", "newcomer.md"])
        mod = json.loads((out / "moderator-input.json").read_text(encoding="utf-8"))
        self.assertEqual(mod[2]["expected"], "SECRET-EXPECTED-guide")
        self.assertEqual(mod[2]["words"], 81)
        self.assertTrue((out / "moderator.schema.json").is_file())

    def test_validate_and_extract(self) -> None:
        keys = expected_screen_keys(_walkthrough())
        self.assertEqual(validate_persona(_answer("commuter", ["good", "ok", "bad"]), keys), [])
        bad = _answer("commuter", ["good", "great"])
        problems = validate_persona(bad, keys)
        self.assertTrue(any("verdict" in p for p in problems))
        self.assertTrue(any("not reviewed" in p for p in problems))
        reply = "Here you go:\n```json\n" + json.dumps({"persona": "x"}) + "\n```\n"
        self.assertEqual(extract_json(reply), {"persona": "x"})
        self.assertEqual(extract_json('noise {"a": 1} noise'), {"a": 1})
        with self.assertRaises(ValueError):
            extract_json("no json here")

    def test_report_shows_split_verdicts_tradeoffs_and_gaps(self) -> None:
        build_panel(self.run_dir, priv=self.priv)
        answers = self.run_dir / "panel" / "answers"
        (answers / "commuter.json").write_text(json.dumps(_answer("commuter", ["good", "ok", "bad"])), encoding="utf-8")
        (answers / "newcomer.json").write_text(json.dumps(_answer("newcomer", ["good", "ok"])), encoding="utf-8")
        decisions = {
            "summary": "Mixed.",
            "consensus": [{"title": "Guide is a wall of text", "screens": ["U-1/guide"], "personas": ["commuter"],
                           "evidence": "81 words", "suggestion": "One gesture per hint", "severity": "major"}],
            "discoverability": [],
            "tradeoffs": [{"title": "Confirm every delete?", "screens": ["U-1/guide"], "tension": "safety vs speed",
                           "options": [{"option": "Keep dialog", "helps": ["newcomer"], "hurts": ["commuter"], "cost": "a tap per delete"},
                                       {"option": "Undo only", "helps": "Renate and Leo", "hurts": None, "cost": "engineering"}],
                           "recommendation": "Undo instead", "decide_if": "who is the primary user"}],
            "keep": [{"title": "Instant swipe", "screens": ["U-1/landing"], "personas": ["commuter"]}],
            "persona_fit": [{"persona": "Mia (commuter)", "fit": "mixed", "note": "likes swiping"}],
        }
        (self.run_dir / "panel" / "decisions.json").write_text(json.dumps(decisions), encoding="utf-8")

        rows = screen_matrix(_walkthrough(), {"commuter": _answer("commuter", ["good", "bad", "bad"]),
                                              "newcomer": _answer("newcomer", ["bad", "bad", "good"])})
        self.assertTrue(rows[0]["split"])
        self.assertEqual(rows[1]["score"], 0.0)

        html_path, md_path = write_report(self.run_dir)
        page = html_path.read_text(encoding="utf-8")
        self.assertIn("Confirm every delete?", page)
        self.assertIn("helps: Renate and Leo", page)  # a string field is not split into letters
        self.assertIn("fit: mixed", page)
        self.assertIn("81 words", page)
        self.assertIn("commuter", page)
        self.assertIn("Guide is a wall of text", md_path.read_text(encoding="utf-8"))
        self.assertEqual(coverage_problems(self.run_dir), ["newcomer: 1 screens not reviewed"])

    def test_walkthrough_marks_a_broken_run(self) -> None:
        w = _walkthrough()
        w["scenarios"][0]["status"] = "broken"
        self.assertIn("could not continue", render_walkthrough_md(w))


class TestScenarioFile(unittest.TestCase):
    STEP_KEYS = {"screen", "launch", "tap", "long_press", "optional_tap", "double_tap", "swipe", "scroll_to",
                 "wait_for", "back", "background", "wait_ms"}

    def test_phototok_scenarios_are_complete(self) -> None:
        spec = load_scenarios("phototok", repo_root())
        self.assertEqual(spec["app_id"], "com.phototok.lab")
        self.assertTrue(str(spec["fixture"]["remote"]).startswith("/sdcard/PSTLab/"))
        names: set[str] = set()
        for scenario in spec["scenarios"]:
            self.assertTrue(scenario.get("goal"), scenario["id"])
            for step in scenario["steps"]:
                kind = next(k for k in step if k in self.STEP_KEYS)
                if kind == "screen":
                    self.assertTrue(step.get("intent") and step.get("expected"), step)
                    key = f"{scenario['id']}/{step['screen']}"
                    self.assertNotIn(key, names)
                    names.add(key)
        self.assertGreaterEqual(len(names), 20)


class TestScenes(unittest.TestCase):
    def test_roll_is_dated_photo_like_jpegs(self) -> None:
        try:
            from PIL import Image, ImageEnhance, ImageFilter  # noqa: F401
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")
        from lab.usability.scenes import ROLL, generate_roll

        out = _scratch("test_usability_scenes")
        try:
            files = generate_roll(out)
            self.assertEqual(len(files), len(ROLL))
            with Image.open(files[0]) as img:
                self.assertEqual(img.size, (2000, 1500))
                self.assertTrue(str(img.getexif().get(0x0132)).startswith("2026:08:14 0"))
            self.assertTrue(all(f.name.startswith("IMG_2026") for f in files))
        finally:
            shutil.rmtree(out, ignore_errors=True)


# ── text load: words per control (docs/shared/UI_TEXT.md) ─────────────────────────────────

def _bar_item(label: str, x: int) -> str:
    """A bottom-bar control as Compose exports it: clickable, an icon node and a label node."""
    icon = _node(desc=label, bounds=f"[{x + 60},1674][{x + 113},1727]")
    text = _node(text=label, bounds=f"[{x + 40},1727][{x + 140},1757]")
    return _node(rid=f"viewer_{label.lower()}_button", clickable=True, bounds=f"[{x},1652][{x + 189},1778]", children=icon + text)


class TestTextLoad(unittest.TestCase):
    def test_labelled_bar_icons_are_flagged_even_on_a_five_word_screen(self) -> None:
        from lab.usability.textload import analyse

        xml = _xml(_node(text="1 / 16", bounds="[919,1478][1001,1515]"),
                   _bar_item("Sources", 128), _bar_item("Selection", 446), _bar_item("Revert", 764))
        m = screen_metrics(xml)
        self.assertFalse(m["text_heavy"])  # the old per-screen rule could never see this
        tl = analyse(xml)
        self.assertEqual(tl["labelled_icons"], 3)
        self.assertEqual(tl["verdict"], "over")
        self.assertEqual(m["text_load"]["labelled_icons"], 3)

    def test_icon_only_controls_and_short_buttons_pass(self) -> None:
        from lab.usability.textload import analyse

        icon_only = _node(rid="reload_folder_button", clickable=True, bounds="[609,86][714,212]",
                          children=_node(desc="Reload folder", bounds="[640,117][703,180]"))
        delete = _node(text="Delete", clickable=True, bounds="[700,1100][900,1200]")
        tl = analyse(_xml(icon_only, delete))
        self.assertEqual((tl["labelled_icons"], tl["wordy_controls"], tl["verdict"]), (0, 0, "ok"))

    def test_wordy_button_and_settings_row_budgets(self) -> None:
        from lab.usability.textload import analyse

        button = _node(text="Continue Where I Left Off", clickable=True, bounds="[100,1100][900,1200]")
        row = _node(clickable=True, bounds="[0,300][1080,460]", children=(
            _node(text="Reset All Seen History", bounds="[40,310][900,360]")
            + _node(text="Reset reviewed photo progress across all folders and cards", bounds="[40,370][1000,420]")))
        short_row = _node(clickable=True, bounds="[0,500][1080,640]", children=(
            _node(text="Selection folder", bounds="[40,510][900,560]") + _node(text="Keepers", bounds="[40,570][600,620]")))
        tl = analyse(_xml(button, row, short_row))
        flags = {tuple(c["texts"])[0]: c["flags"] for c in tl["controls"]}
        self.assertEqual(flags["Continue Where I Left Off"], ["wordy"])
        self.assertEqual(flags["Reset All Seen History"], ["wordy"])
        self.assertEqual(flags["Selection folder"], [])

    def test_german_uses_its_own_character_budget(self) -> None:
        from lab.usability.textload import analyse

        # 20 characters: over the English button limit (16), within the German one (22).
        b = _node(text="Aussortierte löschen", clickable=True, bounds="[100,1100][900,1200]")
        self.assertEqual(analyse(_xml(b), locale="en")["wordy_controls"], 1)
        self.assertEqual(analyse(_xml(b), locale="de-DE")["wordy_controls"], 0)


class TestLocaleAndFontScale(unittest.TestCase):
    def test_find_node_accepts_alternatives(self) -> None:
        nodes = drv.parse_nodes(_xml(_node(text="Löschen", clickable=True, bounds="[0,0][10,10]")))
        self.assertIsNotNone(drv.find_node(nodes, {"text": ["Delete", "Löschen"]}))
        self.assertEqual(drv.describe_target({"text": ["Delete", "Löschen"]}), "'Delete'")

    def test_clear_launch_reapplies_the_app_locale(self) -> None:
        class LocAdb(FakeAdb):
            def __init__(self) -> None:
                super().__init__([_xml()])
                self.cmds: list[tuple[str, ...]] = []

            def run(self, *args: str, timeout: int = 30) -> str:
                self.cmds.append(args)
                return super().run(*args, timeout=timeout)

        adb = LocAdb()
        out = _scratch("test_usability_locale")
        try:
            d = drv.ScenarioDriver(adb, "com.phototok.lab", out, settle_ms=0, app_locale="de-DE")
            d.run({"id": "U", "steps": [{"launch": {"clear": True}}]})
            clear = adb.cmds.index(("shell", "pm", "clear", "com.phototok.lab"))
            self.assertEqual(adb.cmds[clear + 1],
                             ("shell", "cmd", "locale", "set-app-locales", "com.phototok.lab", "--locales", "de-DE"))
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_capture_restores_font_scale_and_locale(self) -> None:
        from lab.usability import capture as cap

        class CapAdb(FakeAdb):
            def __init__(self) -> None:
                super().__init__([_xml()])
                self.cmds: list[tuple[str, ...]] = []

            def run(self, *args: str, timeout: int = 30) -> str:
                self.cmds.append(args)
                if args[-2:] == ("system", "font_scale") and args[2] == "get":
                    return "1.0\n"
                return super().run(*args, timeout=timeout)

        adb = CapAdb()
        out = _scratch("test_usability_capture")
        spec = {"app_id": "com.phototok.lab", "scenarios": [{"id": "U", "steps": []}]}
        orig = cap.load_scenarios
        cap.load_scenarios = lambda product, root=None: spec  # type: ignore[assignment]
        try:
            w = cap.run_capture("phototok", "FAKE", out, adb=adb, locale="de-DE", font_scale=2.0)  # type: ignore[arg-type]
        finally:
            cap.load_scenarios = orig  # type: ignore[assignment]
            shutil.rmtree(out, ignore_errors=True)
        self.assertEqual(w["variant"], "de-DE@2.0")
        self.assertFalse(cap._restore_file("FAKE").exists())
        puts = [c for c in adb.cmds if c[:4] == ("shell", "settings", "put", "system") and c[4] == "font_scale"]
        self.assertEqual([c[5] for c in puts], ["2", "1.0"])
        self.assertEqual(adb.cmds[-1], ("shell", "cmd", "locale", "set-app-locales", "com.phototok.lab", "--locales", ""))


class TestRestoreAfterACrashedWalk(unittest.TestCase):
    def test_a_walk_that_died_leaves_the_original_for_the_next_one(self) -> None:
        from lab.usability import capture as cap

        path = cap._restore_file("FAKE-CRASH")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"font_scale": "1.0"}))
        try:
            class Dev(FakeAdb):
                def run(self, *args: str, timeout: int = 30) -> str:
                    if args[:4] == ("shell", "settings", "get", "system"):
                        return "2.0\n"  # what the dead walk left behind
                    return super().run(*args, timeout=timeout)

            self.assertEqual(cap._original_font_scale(Dev([_xml()]), "FAKE-CRASH"), "1.0")
        finally:
            path.unlink(missing_ok=True)


class TestPanelVariantsAndChecklist(unittest.TestCase):
    def setUp(self) -> None:
        self.base = _scratch("test_usability_variants")
        self.runs = {}
        for label, words in (("en@1.0", 5), ("de-DE@2.0", 7)):
            run = self.base / label
            (run / "U-1").mkdir(parents=True)
            w = _walkthrough()
            w["variant"] = label
            bar = _xml(_bar_item("Sources", 128))
            for s in w["scenarios"][0]["screens"]:
                (run / "U-1" / s["image"]).write_bytes(PNG_1PX)
                s["metrics"]["text_load"] = __import__("lab.usability.textload", fromlist=["analyse"]).analyse(bar, locale=label.split("@")[0])
                s["metrics"]["words"] = words
            (run / "walkthrough.json").write_text(json.dumps(w), encoding="utf-8")
            self.runs[label] = run
        self.priv = self.base / "priv"
        (self.priv / "personas" / "phototok").mkdir(parents=True)
        (self.priv / "personas" / "phototok" / "commuter.md").write_text("# Mia\n", encoding="utf-8")
        (self.priv / "personas" / "phototok" / "newcomer.md").write_text("<!-- setup: de-DE@2.0 -->\n# Renate\n", encoding="utf-8")

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_each_persona_reviews_its_setup_and_moderator_sees_all_variants(self) -> None:
        from lab.usability.panel import text_load_table

        out = build_panel(self.runs["en@1.0"], priv=self.priv, variants=[self.runs["de-DE@2.0"]])
        self.assertEqual(json.loads((out / "assignments.json").read_text()), {"commuter": "en@1.0", "newcomer": "de-DE@2.0"})
        self.assertIn("images/de-DE@2.0/U-1_03_guide.png", (out / "walkthrough-newcomer.md").read_text())
        self.assertIn("images/U-1_03_guide.png", (out / "walkthrough-commuter.md").read_text())
        self.assertTrue((out / "images" / "de-DE@2.0" / "U-1_01_landing.png").is_file())
        mod = json.loads((out / "moderator-input.json").read_text())
        self.assertEqual(set(mod[0]["text_load_by_variant"]), {"en@1.0", "de-DE@2.0"})
        self.assertEqual(mod[0]["labelled_icons"], 1)
        self.assertIsInstance(json.loads((out / "requirements.json").read_text()), list)
        table = (out / "text-load.md").read_text()
        self.assertIn("✘", table)
        self.assertIn("Sources [labelled-icon]", table)
        from lab.usability.panel import load_variants

        _, over = text_load_table(load_variants(self.runs["en@1.0"], [self.runs["de-DE@2.0"]]))
        self.assertTrue(over)

    def test_report_lists_requirement_checks_and_measured_text_load(self) -> None:
        out = build_panel(self.runs["en@1.0"], priv=self.priv, variants=[self.runs["de-DE@2.0"]])
        (out / "requirements.json").write_text(json.dumps([{"id": "REQ-TOK-UX.02", "title": "Icon-First Controls"}]))
        (out / "answers" / "commuter.json").write_text(json.dumps(_answer("commuter", ["good", "ok", "bad"])))
        (out / "decisions.json").write_text(json.dumps({
            "summary": "s", "consensus": [], "discoverability": [], "tradeoffs": [], "keep": [], "persona_fit": [],
            "requirement_checks": [{"id": "REQ-TOK-UX.02", "verdict": "fail", "screens": ["U-1/landing"],
                                    "evidence": "Sources, Selection, Revert carry labels"}],
            "text_load": {"verdict": "over", "worst_screens": ["U-1/landing"], "note": "labels on bar icons"}}))
        html_path, md_path = write_report(self.runs["en@1.0"])
        html_text, md = html_path.read_text(), md_path.read_text()
        self.assertIn("REQ-TOK-UX.02", html_text)
        self.assertIn("Text load, measured", html_text)
        self.assertIn("| REQ-TOK-UX.02 | fail |", md)
        self.assertIn("Text load: over", md)


if __name__ == "__main__":
    unittest.main()
