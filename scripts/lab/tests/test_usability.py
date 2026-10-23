"""Unit tests for the usability panel: metrics, scenario driver, panel packets and report."""

from __future__ import annotations

import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.cli import build_parser, main  # noqa: E402
from lab.model import Node, Tree  # noqa: E402
from lab.paths import repo_root  # noqa: E402
from lab.usability import driver as drv  # noqa: E402
from lab.usability.capture import SUPPORTED_PRODUCTS, load_scenarios, run_capture  # noqa: E402
from lab.usability.driver import (  # noqa: E402
    AndroidDriver,
    BaseDriver,
    FakeDriver,
    LinuxDriver,
    MacOsDriver,
    MockDriver,
    ScenarioDriver,
    StepFailed,
    get_driver,
)
from lab.usability.metrics import screen_metrics, tree_metrics  # noqa: E402
from lab.usability.panel import (  # noqa: E402
    OVERALL_ANSWER_SCHEMA,
    SCREEN_ANSWER_SCHEMA,
    assemble_persona_answer,
    build_blind_packets,
    build_panel,
    expected_screen_keys,
    extract_json,
    render_screen_packet_md,
    render_walkthrough_md,
    validate_persona,
    validate_screen_answer,
)
from lab.usability.explore import (  # noqa: E402
    CycleDetector,
    ExplorationCharter,
    InteractiveElement,
    MockExplorationAgent,
    ScreenObservation,
    compute_screen_signature,
    load_charter,
    run_exploration,
)
from lab.usability.report import (  # noqa: E402
    compute_use_case_fit,
    coverage_problems,
    extract_discoverability_gaps,
    extract_discoverability_misses,
    parse_use_cases,
    render_html,
    render_markdown,
    screen_matrix,
    synthesize_tradeoffs,
    write_report,
)


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


def _tree_dict(
    platform: str,
    nodes: list[dict[str, Any]],
    unit: str = "dp",
    w: float = 1080,
    h: float = 1920,
) -> dict[str, Any]:
    """Helper to construct normalized Tree dictionaries for tests."""
    return {
        "platform": platform,
        "window": {"w": w, "h": h, "unit": unit, "scale": 1.0},
        "nodes": nodes,
    }


def _tree_node(
    node_id: str,
    role: str = "button",
    name: str = "",
    text: str = "",
    bounds: list[float] | None = None,
    interactive: bool = True,
    visible: bool = True,
    parent: str | None = None,
) -> dict[str, Any]:
    """Helper to construct normalized Node dictionaries for tests."""
    return {
        "id": node_id,
        "parent": parent,
        "role": role,
        "name": name,
        "bounds": bounds or [0, 0, 50, 50],
        "interactive": interactive,
        "visible": visible,
        "overlay": False,
        "text": text,
        "clipped": False,
    }


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

    def test_tree_metrics_android(self) -> None:
        """Verify tree_metrics with Android Tree fixture (48dp touch target threshold)."""
        nodes = [
            # Labeled compliant button: 120x56 dp >= 48 dp
            _tree_node("btn_keep", role="button", name="Keep", text="Keep", bounds=[20, 20, 120, 56]),
            # Labeled undersized button: 32x32 dp < 48 dp -> small_targets + 1
            _tree_node("btn_tiny", role="button", name="Info", text="Info", bounds=[160, 20, 32, 32]),
            # Unlabeled compliant button: 60x60 dp >= 48 dp, no name/text -> unlabeled_controls + 1
            _tree_node("btn_mystery", role="button", name="", text="", bounds=[20, 100, 60, 60]),
            # Unlabeled and undersized button: 24x24 dp < 48 dp -> small_targets + 1, unlabeled_controls + 1
            _tree_node("btn_tiny_mystery", role="button", name="", text="", bounds=[100, 100, 24, 24]),
            # Non-interactive text label: 200x40 dp
            _tree_node("lbl_help", role="text", name="", text="Select all photos to review", bounds=[20, 200, 300, 40], interactive=False),
            # Invisible button: must be ignored by metrics
            _tree_node("btn_hidden", role="button", name="Hidden", text="Hidden", bounds=[0, 0, 20, 20], visible=False),
        ]
        raw_tree = _tree_dict("android", nodes, unit="dp")
        m = tree_metrics(raw_tree)

        self.assertEqual(m["controls"], 4)
        self.assertEqual(m["small_targets"], 2)  # btn_tiny and btn_tiny_mystery (< 48 dp)
        self.assertEqual(m["unlabeled_controls"], 2)  # btn_mystery and btn_tiny_mystery
        self.assertEqual(m["words"], 1 + 1 + 5)  # Keep (1) + Info (1) + Select all photos to review (5)
        self.assertEqual(m["longest_block_words"], 5)
        self.assertAlmostEqual(m["reading_seconds"], round(7 / 3.5, 1))
        self.assertFalse(m["text_heavy"])

        # Override min_target to 30.0 dp: btn_tiny (32x32) is no longer small
        m_custom = tree_metrics(raw_tree, min_target=30.0)
        self.assertEqual(m_custom["small_targets"], 1)  # only btn_tiny_mystery (24x24)

    def test_tree_metrics_macos(self) -> None:
        """Verify tree_metrics with macOS Tree fixture (24pt desktop threshold, labels in name)."""
        nodes = [
            # Labeled compliant button with title in `name` (text is ""): 100x28 pt >= 24 pt
            _tree_node("btn_copy", role="button", name="Copy to Selection", text="", bounds=[20, 20, 100, 28]),
            # Compact 24x24 pt toolbar button: compliant at 24pt desktop threshold
            _tree_node("btn_zoom", role="button", name="Zoom", text="", bounds=[130, 20, 24, 24]),
            # Undersized button: 18x18 pt < 24 pt -> small_targets + 1
            _tree_node("btn_mini", role="button", name="Close", text="", bounds=[160, 20, 18, 18]),
            # Unlabeled button: 30x30 pt >= 24 pt, empty name and text -> unlabeled_controls + 1
            _tree_node("btn_icon_only", role="button", name="", text="", bounds=[190, 20, 30, 30]),
            # Window header label
            _tree_node("header", role="text", name="Lensjet Photo Culling", text="", bounds=[20, 60, 250, 30], interactive=False),
        ]
        raw_tree = _tree_dict("macos", nodes, unit="pt", w=1440, h=900)
        m = tree_metrics(raw_tree)

        self.assertEqual(m["controls"], 4)
        # Desktop 24pt threshold: btn_copy (28) and btn_zoom (24) are compliant; only btn_mini (18) is small
        self.assertEqual(m["small_targets"], 1)
        # Button names are recognized as labels: only btn_icon_only is unlabeled
        self.assertEqual(m["unlabeled_controls"], 1)
        self.assertIn("Copy to Selection", m["texts"])
        self.assertEqual(m["words"], 3 + 1 + 1 + 3)  # Copy to Selection (3) + Zoom (1) + Close (1) + Lensjet Photo Culling (3)

    def test_tree_metrics_linux(self) -> None:
        """Verify tree_metrics with Linux Tree fixture (24px desktop target threshold)."""
        nodes = [
            # Standard GTK button: 120x32 px >= 24 px
            _tree_node("btn_move", role="button", name="Move to Selection", text="Move to Selection", bounds=[10, 10, 120, 32]),
            # GTK icon button with tooltip in name: 26x26 px >= 24 px
            _tree_node("btn_trash", role="button", name="Move to Trash", text="", bounds=[140, 10, 26, 26]),
            # Undersized button: 20x20 px < 24 px -> small_targets + 1
            _tree_node("btn_collapse", role="button", name="Collapse", text="", bounds=[170, 10, 20, 20]),
            # Unlabeled button: 28x28 px >= 24 px -> unlabeled_controls + 1
            _tree_node("btn_blank", role="button", name="", text="", bounds=[200, 10, 28, 28]),
        ]
        raw_tree = _tree_dict("linux", nodes, unit="px", w=1280, h=800)
        m = tree_metrics(raw_tree)

        self.assertEqual(m["controls"], 4)
        self.assertEqual(m["small_targets"], 1)  # btn_collapse
        self.assertEqual(m["unlabeled_controls"], 1)  # btn_blank
        self.assertEqual(m["words"], 3 + 3 + 1)  # Move to Selection (3) + Move to Trash (3) + Collapse (1)

    def test_screen_metrics_polymorphic(self) -> None:
        """Verify screen_metrics dispatches seamlessly across XML string, Tree object, and JSON dict."""
        # 1. Android XML string dispatch
        xml_str = _xml(
            _node(text="Delete", bounds="[0,0][200,80]", clickable=True),
            _node(text="Help info", bounds="[0,100][400,160]"),
        )
        m_xml = screen_metrics(xml_str, density_dpi=420)
        self.assertIn("Delete", m_xml["texts"])
        self.assertEqual(m_xml["controls"], 1)
        self.assertEqual(m_xml["words"], 3)

        # 2. Tree dataclass instance dispatch
        tree_obj = Tree(
            platform="macos",
            window={"w": 1440, "h": 900, "unit": "pt", "scale": 2.0},
            nodes=[
                Node.from_dict(_tree_node("btn_1", role="button", name="Open", text="", bounds=[10, 10, 100, 30])),
                Node.from_dict(_tree_node("txt_1", role="text", name="Ready", text="", bounds=[10, 50, 100, 20], interactive=False)),
            ],
        )
        m_tree = screen_metrics(tree_obj)
        self.assertEqual(m_tree["controls"], 1)
        self.assertEqual(m_tree["small_targets"], 0)
        self.assertIn("Open", m_tree["texts"])
        self.assertEqual(m_tree["words"], 2)

        # 3. JSON dictionary dispatch
        dict_tree = tree_obj.to_dict()
        m_dict = screen_metrics(dict_tree)
        self.assertEqual(m_dict["controls"], 1)
        self.assertEqual(m_dict["words"], 2)

        # 4. JSON string dispatch
        json_str = json.dumps(dict_tree)
        m_json = screen_metrics(json_str)
        self.assertEqual(m_json["controls"], 1)
        self.assertEqual(m_json["words"], 2)


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
        self.assertEqual(drv.describe_action({"key": "M"}), "Pressed 'M'")
        self.assertEqual(drv.describe_action({"key": {"code": "z", "modifiers": ["cmd"]}}), "Pressed 'z+cmd'")
        self.assertIsNone(drv.describe_action({"wait_for": {"id": "x"}}))

    def test_describe_target_and_action_with_strings(self) -> None:
        """Verify describe_target handles string and dict targets, and describe_action works with string targets."""
        self.assertEqual(drv.describe_target("btn_id"), "'btn_id'")
        self.assertEqual(drv.describe_target({"id": "btn_id"}), "the control 'btn_id'")
        self.assertEqual(drv.describe_target({"label": "Accept"}), "'Accept'")
        self.assertEqual(drv.describe_target({"text": "Hello"}), "'Hello'")
        self.assertEqual(drv.describe_target({"desc": "Icon"}), "'Icon'")
        self.assertEqual(drv.describe_target(123), "'123'")

        self.assertEqual(drv.describe_action({"tap": "btn_id"}), "Tapped 'btn_id'")
        self.assertEqual(drv.describe_action({"long_press": "btn_id"}), "Long-pressed 'btn_id'")
        self.assertEqual(drv.describe_action({"scroll_to": "target_item"}), "Scrolled to 'target_item'")

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
                    {"key": "Right", "settle_ms": 0},
                    {"screen": "feed", "intent": "look", "expected": "swipe up"},
                    {"screen": "dialog", "when": {"text": "Never shown", "timeout_ms": 0}},
                ],
            }
        )
        self.assertEqual(result["status"], "pass")
        self.assertIn(("tap", "540", "1500"), adb.inputs)
        self.assertIn(("key", "KEYCODE_DPAD_RIGHT"), adb.inputs)
        self.assertEqual([s["name"] for s in result["screens"]], ["feed"])  # `when` absent: skipped
        shot = result["screens"][0]
        self.assertEqual(shot["actions_before"], ["Tapped 'GOT IT'", "Swiped up", "Pressed 'Right'"])
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
    STEP_KEYS = {
        "screen", "launch", "tap", "long_press", "optional_tap", "double_tap", "swipe", "scroll_to",
        "wait_for", "back", "background", "wait_ms", "key",
    }

    PRODUCT_SPECS = {
        "phototok": {
            "app_id": "com.phototok.lab",
            "fixture_prefix": "/sdcard/PSTLab/",
            "min_screens": 20,
        },
        "android-desktop": {
            "app_id": "com.photoselectortoolbox.lab",
            "fixture_prefix": "/sdcard/PSTLab/",
            "min_screens": 20,
        },
        "macos": {
            "app_id": "app.alpatix.lensjet",
            "fixture_prefix": "/tmp/pstlab/",
            "min_screens": 20,
        },
        "linux": {
            "app_id": "app.alpatix.Blitzlens",
            "fixture_prefix": "/tmp/pstlab/",
            "min_screens": 20,
        },
    }

    def _assert_scenario_spec(self, product: str) -> None:
        cfg = self.PRODUCT_SPECS[product]
        spec = load_scenarios(product, repo_root())
        self.assertEqual(spec["app_id"], cfg["app_id"])
        self.assertTrue(
            str(spec["fixture"]["remote"]).startswith(cfg["fixture_prefix"]),
            f"{product}: fixture remote {spec['fixture']['remote']!r} does not start with {cfg['fixture_prefix']!r}",
        )
        names: set[str] = set()
        for scenario in spec["scenarios"]:
            self.assertTrue(scenario.get("goal"), f"{product}: scenario {scenario.get('id')} missing goal")
            self.assertTrue(scenario.get("use_case"), f"{product}: scenario {scenario.get('id')} missing use_case")
            for step in scenario["steps"]:
                matching = [k for k in step if k in self.STEP_KEYS]
                self.assertTrue(matching, f"{product}: step {step} has no recognised key in {self.STEP_KEYS}")
                kind = matching[0]
                if kind == "screen":
                    self.assertTrue(step.get("intent") and step.get("expected"), f"{product}: screen step missing intent/expected: {step}")
                    key = f"{scenario['id']}/{step['screen']}"
                    self.assertNotIn(key, names, f"{product}: duplicate screen key {key}")
                    names.add(key)
        self.assertGreaterEqual(len(names), cfg["min_screens"], f"{product}: expected >= {cfg['min_screens']} screens, found {len(names)}")

    def test_phototok_scenarios_are_complete(self) -> None:
        self._assert_scenario_spec("phototok")

    def test_android_desktop_scenarios_are_complete(self) -> None:
        self._assert_scenario_spec("android-desktop")

    def test_macos_scenarios_are_complete(self) -> None:
        self._assert_scenario_spec("macos")

    def test_linux_scenarios_are_complete(self) -> None:
        self._assert_scenario_spec("linux")


class TestPersonaInvariants(unittest.TestCase):
    SETUP_PATTERN = re.compile(r"^<!--\s*setup:\s*([a-zA-Z0-9_-]+)@([0-9.]+)\s*-->")
    FIRST_PERSON_WORDS = {"i", "my", "me", "mine", "myself"}
    EXPECTED_PRODUCTS = {"phototok", "android-desktop", "macos", "linux"}

    def test_persona_files_conform_to_invariants(self) -> None:
        personas_root = repo_root() / "private" / "lab" / "usability" / "personas"
        if not personas_root.is_dir():
            self.skipTest("private/lab/usability/personas directory not present (public checkout)")

        found_products = {p.name for p in personas_root.iterdir() if p.is_dir() and not p.name.startswith(".")}
        self.assertTrue(
            self.EXPECTED_PRODUCTS.issubset(found_products),
            f"Missing product persona directories: {self.EXPECTED_PRODUCTS - found_products}",
        )

        total_files = 0
        for product in sorted(self.EXPECTED_PRODUCTS):
            product_dir = personas_root / product
            files = sorted(product_dir.glob("*.md"))
            self.assertGreaterEqual(
                len(files), 4,
                f"Product '{product}' must have at least 4 persona briefs, found {len(files)}",
            )

            for f in files:
                total_files += 1
                text = f.read_text(encoding="utf-8").strip()
                lines = text.splitlines()
                self.assertGreaterEqual(len(lines), 3, f"{f.name}: persona file is too short")

                # Invariant 1: Setup header comment
                match = self.SETUP_PATTERN.match(lines[0].strip())
                self.assertIsNotNone(
                    match,
                    f"{f.relative_to(personas_root)}: line 1 must match `<!-- setup: <locale>@<font_scale> -->`, got {lines[0]!r}",
                )
                locale, font_scale = match.group(1), float(match.group(2))
                self.assertTrue(locale, f"{f.name}: locale must be non-empty")
                self.assertGreater(font_scale, 0.0, f"{f.name}: font_scale must be > 0")

                # Invariant 2: Word count strictly < 100 words (both total and body)
                words = text.split()
                body_lines = [line for line in lines if not line.startswith("<!--") and not line.startswith("#")]
                body_words = " ".join(body_lines).split()

                self.assertLess(
                    len(words), 100,
                    f"{f.relative_to(personas_root)}: total word count {len(words)} must be strictly < 100 words",
                )
                self.assertLess(
                    len(body_words), 100,
                    f"{f.relative_to(personas_root)}: body word count {len(body_words)} must be strictly < 100 words",
                )

                # Invariant 3: First-person perspective
                tokens = {re.sub(r"[^a-zA-Z]", "", w).lower() for w in body_words}
                self.assertTrue(
                    bool(tokens & self.FIRST_PERSON_WORDS),
                    f"{f.relative_to(personas_root)}: must be written from first-person perspective ('I', 'my', 'me')",
                )

        self.assertGreaterEqual(total_files, 16, f"Expected at least 16 persona briefs, found {total_files}")


class TestScenes(unittest.TestCase):
    def test_roll_is_dated_photo_like_jpegs(self) -> None:
        try:
            from PIL import Image, ImageEnhance, ImageFilter  # noqa: F401
            if ImageEnhance is None:
                raise ImportError("scene rendering needs the real Pillow")
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")
        from lab.usability.scenes import ROLL, generate_roll

        out = _scratch("test_usability_scenes")
        try:
            files = generate_roll(out, include_companions=False)
            self.assertEqual(len(files), len(ROLL))
            with Image.open(files[0]) as img:
                self.assertEqual(img.size, (2000, 1500))
                self.assertTrue(str(img.getexif().get(0x0132)).startswith("2026:08:14 0"))
            self.assertTrue(all(f.name.startswith("IMG_2026") for f in files))
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_scenes_burst_generation(self) -> None:
        """Verify multi-shot bursts with graded sharpness in generated camera rolls."""
        try:
            from PIL import Image, ImageEnhance, ImageFilter, ImageStat  # noqa: F401
            if ImageEnhance is None:
                raise ImportError("scene rendering needs the real Pillow")
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")

        from lab.usability.scenes import generate_roll

        out = _scratch("test_scenes_bursts")
        try:
            files = generate_roll(out, count=16, include_bursts=True)
            self.assertGreaterEqual(len(files), 16)

            burst_files = [p for p in files if p.suffix.lower() in ('.jpg', '.jpeg') and '-Edit' not in p.name][:3]
            self.assertGreaterEqual(len(burst_files), 3, "Expected at least 3 frames in burst sequence")

            def edge_energy(p: Path) -> float:
                with Image.open(p) as im:
                    edges = im.convert("L").filter(ImageFilter.FIND_EDGES)
                    return float(ImageStat.Stat(edges).var[0])

            energies = [edge_energy(p) for p in burst_files[:3]]
            self.assertGreater(max(energies), min(energies) * 1.25, "Burst frames must have distinct sharpness levels")
            self.assertNotEqual(energies[0], energies[1])
            self.assertNotEqual(energies[1], energies[2])
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_scenes_companion_files(self) -> None:
        """Verify companion RAW and .xmp sidecar generation alongside JPEGs."""
        try:
            from PIL import Image, ImageEnhance, ImageFilter  # noqa: F401
            if ImageEnhance is None:
                raise ImportError("scene rendering needs the real Pillow")
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")

        from lab.usability.scenes import generate_roll

        out = _scratch("test_scenes_companions")
        try:
            files = generate_roll(out, count=16, include_companions=True)
            self.assertTrue(len(files) >= 16)

            xmp_files = list(out.glob("*.xmp"))
            self.assertGreaterEqual(len(xmp_files), 1, "At least one .xmp companion sidecar should be generated")

            raw_files = list(out.glob("*.DNG")) + list(out.glob("*.CR3")) + list(out.glob("*.raw"))
            self.assertGreaterEqual(len(raw_files), 1, "At least one companion RAW file should be generated")

            xmp_text = xmp_files[0].read_text(encoding="utf-8")
            self.assertIn("xmpmeta", xmp_text)
            self.assertIn("xmp:Rating", xmp_text)

            base_stem = xmp_files[0].stem
            matching_jpg = out / f"{base_stem}.jpg"
            self.assertTrue(matching_jpg.is_file(), f"Expected matching JPEG {matching_jpg.name} for sidecar")
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_scenes_optical_exif_variance(self) -> None:
        """Verify varied aperture, shutter speed, ISO, and focal length across roll."""
        try:
            from PIL import Image, ImageEnhance, ImageFilter  # noqa: F401
            if ImageEnhance is None:
                raise ImportError("scene rendering needs the real Pillow")
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")

        from lab.usability.scenes import generate_roll

        out = _scratch("test_scenes_optical")
        try:
            files = generate_roll(out, count=16)
            isos: set[int] = set()
            shutters: set[Any] = set()
            apertures: set[Any] = set()
            focals: set[Any] = set()
            lenses: set[str] = set()

            for f in files:
                if f.suffix.lower() not in (".jpg", ".jpeg"):
                    continue
                with Image.open(f) as im:
                    exif = im.getexif()
                    sub = exif.get_ifd(0x8769) if hasattr(exif, "get_ifd") else {}
                    if 0x8827 in sub:
                        isos.add(int(sub[0x8827]))
                    if 0x829A in sub:
                        shutters.add(str(sub[0x829A]))
                    if 0x829D in sub:
                        apertures.add(str(sub[0x829D]))
                    if 0x920A in sub:
                        focals.add(str(sub[0x920A]))
                    if 0xA434 in sub:
                        lenses.add(str(sub[0xA434]))

            self.assertGreaterEqual(len(isos), 3, f"Expected >= 3 distinct ISOs, found {isos}")
            self.assertGreaterEqual(len(shutters), 3, f"Expected >= 3 distinct shutter speeds, found {shutters}")
            self.assertGreaterEqual(len(apertures), 3, f"Expected >= 3 distinct apertures, found {apertures}")
            self.assertGreaterEqual(len(focals), 3, f"Expected >= 3 distinct focal lengths, found {focals}")
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_scenes_zero_people(self) -> None:
        """Assert zero person-related labels, keywords, or descriptions in generated roll."""
        from lab.usability.scenes import ROLL, ROLL_SPECS

        forbidden = {"person", "people", "man", "woman", "child", "girl", "boy", "face", "human", "portrait"}

        for s in ROLL_SPECS:
            for word in forbidden:
                self.assertNotIn(word, s.name.lower(), f"Forbidden word '{word}' found in spec name '{s.name}'")
                self.assertNotIn(word, s.palette.lower(), f"Forbidden word '{word}' found in palette '{s.palette}'")
                if s.flaw:
                    self.assertNotIn(word, s.flaw.lower(), f"Forbidden word '{word}' found in flaw '{s.flaw}'")
                if s.lens_model:
                    self.assertNotIn(word, s.lens_model.lower(), f"Forbidden word '{word}' found in lens model '{s.lens_model}'")

        for name, palette, flaw in ROLL:
            for word in forbidden:
                self.assertNotIn(word, name.lower())
                self.assertNotIn(word, palette.lower())
                if flaw:
                    self.assertNotIn(word, flaw.lower())

        try:
            from PIL import Image, ImageEnhance, ImageFilter  # noqa: F401
            if ImageEnhance is None:
                raise ImportError("scene rendering needs the real Pillow")
        except ImportError:
            self.skipTest("scene rendering needs the real Pillow")

        from lab.usability.scenes import generate_roll

        out = _scratch("test_scenes_zero_people")
        try:
            files = generate_roll(out, count=16, include_companions=True)
            for f in files:
                for word in forbidden:
                    self.assertNotIn(word, f.name.lower())

                if f.suffix.lower() in (".jpg", ".jpeg"):
                    with Image.open(f) as im:
                        exif = im.getexif()
                        for tag_id, val in exif.items():
                            if isinstance(val, str):
                                for word in forbidden:
                                    self.assertNotIn(word, val.lower(), f"Forbidden word '{word}' found in EXIF tag {tag_id} of {f.name}")

                if f.suffix.lower() == ".xmp":
                    content = f.read_text(encoding="utf-8").lower()
                    for word in forbidden:
                        self.assertNotIn(word, content, f"Forbidden word '{word}' found in sidecar {f.name}")
        finally:
            shutil.rmtree(out, ignore_errors=True)


class TestBlindPackets(unittest.TestCase):
    """Unit tests for blind packet isolation and schema verification."""

    def setUp(self) -> None:
        self.base = _scratch("test_blind_packets")
        self.run_dir = self.base / "run"
        self.scen_dir = self.run_dir / "U-TEST"
        self.scen_dir.mkdir(parents=True)
        for name in ("01_landing.png", "02_picker.png", "03_guide.png", "04_confirm.png"):
            (self.scen_dir / name).write_bytes(PNG_1PX)

        self.walkthrough = {
            "product": "phototok",
            "captured_at": "2026-10-22T12:00:00Z",
            "duration_s": 2.5,
            "device": {"model": "Pixel 4a", "android": "14", "size": "1080x2340", "density": 440},
            "scenarios": [
                {
                    "scenario": "U-TEST",
                    "title": "Camera Roll Import",
                    "goal": "Import vacation photos safely",
                    "status": "pass",
                    "error": None,
                    "screens": [
                        {
                            "index": 1,
                            "name": "landing",
                            "image": "01_landing.png",
                            "intent": "Open local photos",
                            "expected": "SECRET_EXPECTED_ACTION_0",
                            "system_screen": False,
                            "actions_before": [],
                            "metrics": {
                                "texts": ["SECRET_OCR_LANDING"],
                                "words": 10,
                                "reading_seconds": 2.8,
                                "longest_block_words": 5,
                                "controls": 1,
                                "small_targets": 0,
                                "unlabeled_controls": 0,
                            },
                        },
                        {
                            "index": 2,
                            "name": "picker",
                            "image": "02_picker.png",
                            "intent": "Select SD card DCIM",
                            "expected": "SECRET_EXPECTED_ACTION_1",
                            "system_screen": True,
                            "actions_before": ["Tapped 'Open Photos'", "Swiped up drawer"],
                            "metrics": {
                                "texts": ["SECRET_OCR_PICKER"],
                                "words": 20,
                                "reading_seconds": 5.7,
                                "longest_block_words": 8,
                                "controls": 2,
                                "small_targets": 0,
                                "unlabeled_controls": 0,
                            },
                        },
                        {
                            "index": 3,
                            "name": "guide",
                            "image": "03_guide.png",
                            "intent": "Learn culling gestures",
                            "expected": "SECRET_EXPECTED_ACTION_2",
                            "system_screen": False,
                            "actions_before": ["Selected DCIM directory", "Granted storage access"],
                            "metrics": {
                                "texts": ["SECRET_OCR_GUIDE"],
                                "words": 45,
                                "reading_seconds": 12.8,
                                "longest_block_words": 15,
                                "controls": 2,
                                "small_targets": 1,
                                "unlabeled_controls": 0,
                            },
                        },
                        {
                            "index": 4,
                            "name": "confirm",
                            "image": "04_confirm.png",
                            "intent": "Confirm import without deletion",
                            "expected": "SECRET_EXPECTED_ACTION_3",
                            "system_screen": False,
                            "actions_before": ["Dismissed gesture guide"],
                            "metrics": {
                                "texts": ["SECRET_OCR_CONFIRM"],
                                "words": 15,
                                "reading_seconds": 4.2,
                                "longest_block_words": 6,
                                "controls": 2,
                                "small_targets": 0,
                                "unlabeled_controls": 0,
                            },
                        },
                    ],
                }
            ],
        }
        (self.run_dir / "walkthrough.json").write_text(json.dumps(self.walkthrough), encoding="utf-8")
        self.priv = self.base / "priv"
        (self.priv / "personas" / "phototok").mkdir(parents=True)
        (self.priv / "personas" / "phototok" / "commuter.md").write_text(
            "<!-- setup: en@2.0 -->\n# Mia — commuter\nI have seconds to spare. Keep it fast.\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_blind_packets_strict_isolation_invariants(self) -> None:
        """Verify: zero future actions, zero expected strings, and zero OCR/trees in packet i."""
        packets_dir = self.run_dir / "packets"
        packets = build_blind_packets(self.walkthrough, packets_dir)
        screens = self.walkthrough["scenarios"][0]["screens"]
        self.assertEqual(len(packets), len(screens))

        all_expected = [s["expected"] for s in screens]
        all_ocr = [text for s in screens for text in s["metrics"]["texts"]]

        for i, pkt in enumerate(packets):
            md_path = pkt["path"]
            self.assertTrue(md_path.is_file(), f"Packet markdown missing: {md_path}")
            text = md_path.read_text(encoding="utf-8")

            # Invariant 1: NO expected string from ANY screen
            for exp in all_expected:
                self.assertNotIn(exp, text, f"Packet {i} contains expected string {exp!r}")

            # Invariant 2: NO OCR/text dumps or XML tree markers
            for ocr in all_ocr:
                self.assertNotIn(ocr, text, f"Packet {i} contains OCR text {ocr!r}")
            self.assertNotIn("<hierarchy", text)
            self.assertNotIn("resource-id", text)

            # Invariant 3: NO actions_before from future screens j > i
            for j in range(i + 1, len(screens)):
                for future_action in screens[j]["actions_before"]:
                    self.assertNotIn(
                        future_action,
                        text,
                        f"Packet {i} ({screens[i]['name']}) leaked future action from screen {j} ({screens[j]['name']}): {future_action!r}",
                    )

            # Invariant 4: Transition context for i > 0
            if i > 0:
                self.assertIsNotNone(pkt["previous_screen"])
                self.assertIn(pkt["previous_screen"], text)
                for action in screens[i]["actions_before"]:
                    self.assertIn(action, text, f"Packet {i} missing transition action: {action!r}")
            else:
                self.assertIsNone(pkt["previous_screen"])

    def test_blind_packets_with_personas(self) -> None:
        """Verify persona-bound packets include setup tag, persona profile, and UX rubrics."""
        out = build_panel(self.run_dir, priv=self.priv)
        commuter_dir = out / "packets" / "commuter"
        self.assertTrue(commuter_dir.is_dir())

        pkt0 = commuter_dir / "00_U-TEST_landing.md"
        self.assertTrue(pkt0.is_file())
        text = pkt0.read_text(encoding="utf-8")
        self.assertIn("<!-- setup: en@2.0 -->", text)
        self.assertIn("Mia — commuter", text)
        self.assertIn("Visual Hierarchy", text)
        self.assertIn("Affordance Clarity", text)
        self.assertIn("File-Safety Trust", text)
        self.assertIn("Feedback Visibility", text)
        self.assertNotIn("SECRET_EXPECTED", text)
        self.assertNotIn("SECRET_OCR", text)

        overall = commuter_dir / "overall.md"
        self.assertTrue(overall.is_file())
        overall_text = overall.read_text(encoding="utf-8")
        self.assertIn("would_keep_using", overall_text)

    def test_single_screen_schema_and_answer_assembly(self) -> None:
        """Verify validation of individual screen answer and assembly into full persona answer."""
        valid_screen = {
            "screen": "U-TEST/landing",
            "first_impression": "Simple photo import prompt",
            "would_do": "Tap the big open button",
            "read_text": "some",
            "verdict": "good",
            "issues": [],
            "liked": ["Clean layout"],
        }
        self.assertEqual(validate_screen_answer(valid_screen, "U-TEST/landing"), [])

        invalid_screen = dict(valid_screen)
        invalid_screen["verdict"] = "superb"  # not in ('good', 'ok', 'bad')
        problems = validate_screen_answer(invalid_screen, "U-TEST/landing")
        self.assertTrue(any("verdict" in p for p in problems))

        # Assemble full document
        full_doc = assemble_persona_answer("commuter", [valid_screen])
        self.assertEqual(full_doc["persona"], "commuter")
        self.assertEqual(len(full_doc["screens"]), 1)
        self.assertIn("overall", full_doc)
        self.assertEqual(validate_persona(full_doc, ["U-TEST/landing"]), [])

    def test_multi_scenario_transition_isolation(self) -> None:
        """Verify transition context resets across scenarios and does not link across scenario boundaries."""
        multi_walkthrough = {
            "product": "phototok",
            "captured_at": "2026-10-22T12:00:00Z",
            "duration_s": 2.0,
            "device": {"model": "Pixel 4a", "android": "14", "size": "1080x2340", "density": 440},
            "scenarios": [
                {
                    "scenario": "SCEN-A",
                    "goal": "Goal A",
                    "status": "pass",
                    "screens": [
                        {"name": "A1", "image": "01_landing.png", "intent": "want A1", "actions_before": []},
                    ],
                },
                {
                    "scenario": "SCEN-B",
                    "goal": "Goal B",
                    "status": "pass",
                    "screens": [
                        {"name": "B1", "image": "02_picker.png", "intent": "want B1", "actions_before": ["Tapped B"]},
                    ],
                },
            ],
        }
        packets_dir = self.run_dir / "multi_packets"
        packets = build_blind_packets(multi_walkthrough, packets_dir)
        self.assertEqual(len(packets), 2)
        # SCEN-B Screen 0 must NOT link to SCEN-A Screen 0 as previous screen
        self.assertIsNone(packets[0]["previous_screen"])
        self.assertIsNone(packets[1]["previous_screen"])


class TestMultiProductDrivers(unittest.TestCase):
    """Unit tests for polymorphic drivers: MockDriver, MacOsDriver, LinuxDriver."""

    def setUp(self) -> None:
        self.base = _scratch("test_multi_drivers")

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_mock_driver_captures_all_steps_and_screens(self) -> None:
        """Verify MockDriver logs all step primitives and emits checkpoints."""
        out = self.base / "mock_run"
        driver = MockDriver("macos", app_id="test.mac", out_dir=out)
        scenario = {
            "id": "U-MOCK",
            "title": "Mock Journey",
            "goal": "Verify all primitives",
            "steps": [
                {"launch": {}},
                {"tap": {"id": "open_btn"}},
                {"long_press": {"id": "card_item"}},
                {"double_tap": {}},
                {"swipe": "left"},
                {"scroll_to": {"id": "footer"}},
                {"key": "Right"},
                {"wait_for": {"id": "main_view"}},
                {"wait_ms": 100},
                {"screen": "main_screen", "intent": "View main screen", "expected": "press Right"},
            ],
        }
        res = driver.run(scenario)
        self.assertEqual(res["status"], "pass")
        self.assertEqual(len(res["screens"]), 1)
        screen = res["screens"][0]
        self.assertEqual(screen["name"], "main_screen")
        self.assertEqual(screen["image"], "01_main_screen.png")
        self.assertTrue((out / "01_main_screen.png").is_file())
        self.assertTrue((out / "01_main_screen.tree.json").is_file())
        self.assertTrue((out / "01_main_screen.json").is_file())
        self.assertTrue((out / "scenario.json").is_file())
        # Verify actions before captured all prior steps
        self.assertGreater(len(screen["actions_before"]), 5)

    def test_mock_driver_step_failure_marks_broken(self) -> None:
        """Verify step failure records broken scenario and screen."""
        out = self.base / "mock_broken"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out, fail_on_step="broken_step")
        scenario = {
            "id": "U-FAIL",
            "title": "Failing Journey",
            "goal": "Test failure",
            "steps": [
                {"launch": {}},
                {"screen": "start"},
                {"tap": {"id": "broken_step"}},
                {"screen": "after"},
            ],
        }
        res = driver.run(scenario)
        self.assertEqual(res["status"], "broken")
        self.assertIn("broken_step", str(res["error"]))
        self.assertEqual(len(res["screens"]), 2)
        self.assertEqual(res["screens"][1]["name"], "broken")

    def test_macos_driver_translates_steps_and_collects_checkpoints(self) -> None:
        """Verify MacOsDriver translates steps to UIScenario JSON and handles checkpoints via exec_fn."""
        out = self.base / "mac_run"

        executed_cmds = []
        executed_envs = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)
            executed_envs.append(env)
            # Create synthetic checkpoint
            (out / "01_cull_view.png").write_bytes(PNG_1PX)
            tree_data = _tree_dict(
                "macos",
                [_tree_node("grid", role="table", text="Photos", bounds=[0, 0, 800, 600])],
                unit="pt",
                w=1280,
                h=840,
            )
            (out / "01_cull_view.tree.json").write_text(json.dumps(tree_data), encoding="utf-8")

        driver = MacOsDriver("com.example.PhotoSelectorApp", out, exec_fn=fake_exec)
        scenario = {
            "id": "U-MAC-1",
            "title": "macOS Culling",
            "goal": "Fast cull burst",
            "steps": [
                {"launch": {}},
                {"key": "Cmd+O"},
                {"wait_for": {"id": "folder_picker"}},
                {"tap": {"id": "select_btn"}},
                {"wait_ms": 200},
                {"key": "Right"},
                {"key": "Delete"},
                {"screen": "cull_view", "intent": "View culled burst", "expected": "tap Next"},
            ],
        }
        res = driver.run(scenario)
        self.assertEqual(res["status"], "pass")
        self.assertEqual(len(executed_cmds), 1)
        self.assertEqual(executed_envs[0].get("PST_SHOW_GUI"), "0")

        # Verify translated scenario file
        scen_json = json.loads((out / "ui-scenario.json").read_text(encoding="utf-8"))
        actions = [s["action"] for s in scen_json["steps"]]
        self.assertIn("waitLoaded", actions)
        self.assertIn("waitFor", actions)
        self.assertIn("click", actions)
        self.assertIn("wait", actions)
        self.assertIn("key", actions)
        self.assertIn("checkpoint", actions)

        # Verify checkpoints collected
        self.assertEqual(len(res["screens"]), 1)
        self.assertEqual(res["screens"][0]["name"], "cull_view")
        self.assertEqual(res["screens"][0]["metrics"]["words"], 1)

    def test_linux_driver_translates_steps_and_collects_checkpoints(self) -> None:
        """Verify LinuxDriver translates steps to session.script and handles checkpoints via exec_fn."""
        out = self.base / "linux_run"

        executed_cmds = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)
            # Create synthetic checkpoint
            (out / "01_overview.png").write_bytes(PNG_1PX)
            tree_data = _tree_dict(
                "linux",
                [_tree_node("btn", role="button", text="Import", bounds=[0, 0, 100, 40])],
                unit="px",
                w=1280,
                h=800,
            )
            (out / "01_overview.tree.json").write_text(json.dumps(tree_data), encoding="utf-8")

        driver = LinuxDriver("photo-selector-linux", out, exec_fn=fake_exec)
        scenario = {
            "id": "U-LINUX-1",
            "title": "Linux Culling",
            "goal": "Import and review",
            "steps": [
                {"launch": {}},
                {"key": "Right"},
                {"wait_ms": 500},
                {"screen": "overview", "intent": "View imported photos", "expected": "hit Space"},
            ],
        }
        res = driver.run(scenario)
        self.assertEqual(res["status"], "pass")
        self.assertEqual(len(executed_cmds), 1)

        # Verify script lines
        script_text = (out / "session.script").read_text(encoding="utf-8")
        self.assertIn("wait_loaded", script_text)
        self.assertIn("key Right", script_text)
        self.assertIn("sleep 0.50", script_text)
        self.assertIn("checkpoint overview", script_text)

        # Verify checkpoints collected
        self.assertEqual(len(res["screens"]), 1)
        self.assertEqual(res["screens"][0]["name"], "overview")
        self.assertEqual(res["screens"][0]["metrics"]["words"], 1)


class TestMultiProductCapture(unittest.TestCase):
    """Unit tests for generalized multi-product usability capture."""

    def setUp(self) -> None:
        self.base = _scratch("test_multi_capture")

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_run_capture_all_four_products_mock_mode(self) -> None:
        """Verify run_capture executes against all four supported products in mock mode."""
        for product in ("phototok", "android-desktop", "macos", "linux"):
            out = self.base / f"cap_{product}"
            w = run_capture(product, out_dir=out, driver_type="mock")
            self.assertEqual(w["product"], product)
            self.assertTrue(w["device"]["mock"])
            self.assertTrue((out / "walkthrough.json").is_file())
            self.assertGreaterEqual(len(w["scenarios"]), 1)
            self.assertEqual(w["scenarios"][0]["status"], "pass")
            self.assertGreaterEqual(len(w["scenarios"][0]["screens"]), 1)

    def test_run_capture_android_requires_serial_on_hardware(self) -> None:
        """Verify Android capture without mock mode raises ValueError when serial is missing."""
        out = self.base / "cap_no_serial"
        with self.assertRaises(ValueError) as ctx:
            run_capture("android-desktop", serial=None, out_dir=out, driver_type="auto")
        self.assertIn("serial is required", str(ctx.exception))

    def test_run_capture_desktop_without_serial(self) -> None:
        """Verify desktop capture does not require serial."""
        out = self.base / "cap_desktop_no_serial"
        driver = MockDriver("macos", app_id="test.mac", out_dir=out)
        w = run_capture("macos", serial=None, out_dir=out, driver=driver)
        self.assertEqual(w["product"], "macos")
        self.assertEqual(w["scenarios"][0]["status"], "pass")

    def test_run_capture_only_filter(self) -> None:
        """Verify only parameter limits execution to matching scenario IDs."""
        out = self.base / "cap_only"
        scens = load_scenarios("phototok")
        target_id = scens["scenarios"][0]["id"]
        w = run_capture("phototok", out_dir=out, only=[target_id], driver_type="mock")
        self.assertEqual(len(w["scenarios"]), 1)
        self.assertEqual(w["scenarios"][0]["scenario"], target_id)

    def test_run_capture_stops_on_broken_scenario(self) -> None:
        """Verify walkthrough stops immediately when a scenario breaks."""
        out = self.base / "cap_stop_broken"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out, fail_on_step=1)
        w = run_capture("phototok", out_dir=out, driver=driver)
        self.assertEqual(len(w["scenarios"]), 1)
        self.assertEqual(w["scenarios"][0]["status"], "broken")


class TestUsabilityCli(unittest.TestCase):
    """Unit tests for usability CLI commands and arguments."""

    def test_cli_parser_accepts_all_four_products(self) -> None:
        """Verify CLI parser parses --product for phototok, android-desktop, macos, and linux."""
        parser = build_parser()
        for prod in ("phototok", "android-desktop", "macos", "linux"):
            args = parser.parse_args(["usability-capture", "--product", prod])
            self.assertEqual(args.product, prod)
            self.assertIsNone(args.serial)
            self.assertEqual(args.driver, "auto")

    def test_cli_driver_mock_flag(self) -> None:
        """Verify CLI parser accepts --driver mock flag."""
        parser = build_parser()
        args = parser.parse_args(["usability-capture", "--driver", "mock"])
        self.assertEqual(args.driver, "mock")

    def test_cli_rejects_missing_serial_for_android_hardware(self) -> None:
        """Verify main() returns code 2 if Android capture is requested without serial or mock."""
        code = main(["usability-capture", "--product", "phototok"])
        self.assertEqual(code, 2)


class TestExplorationLoop(unittest.TestCase):
    """Unit tests for the autonomous live AI persona exploration loop."""

    def setUp(self) -> None:
        self.base = _scratch("test_exploration_loop")
        self.charter = {
            "id": "test_commuter",
            "title": "Commuter Exploration",
            "goal": "Reach the photo feed and keep one gem",
            "use_case": "UC-1",
            "budget": 25,
            "target_screen": "feed",
        }

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_charter_markdown_loading(self) -> None:
        """Validates parsing of charter markdown with goal, budget, app_id, and never constraints."""
        md_text = (
            "# Charter: Commuter — One-handed phone culling (UC-1)\n\n"
            "Device: phone (lab user), app `com.phototok.lab`, folder `PSTLab/cull-basic`.\n"
            "Goal: Cull a 20-photo roll one-handed on a phone using vertical swipes.\n"
            "Time box: 15 actions.\n"
            "Never: open other apps, change system settings, leave `PSTLab`, tap `landing_phone_camera_card`.\n"
        )
        ch_file = self.base / "commuter.md"
        ch_file.write_text(md_text, encoding="utf-8")

        c = load_charter(ch_file)
        self.assertEqual(c.id, "commuter")
        self.assertEqual(c.title, "Commuter — One-handed phone culling (UC-1)")
        self.assertEqual(c.use_case, "UC-1")
        self.assertEqual(c.product, "phototok")
        self.assertEqual(c.app_id, "com.phototok.lab")
        self.assertEqual(c.step_budget, 15)
        self.assertIn("open other apps", c.never)
        self.assertIn("change system settings", c.never)

    def test_screen_signature_deterministic(self) -> None:
        """Structural hash invariance against battery and clock changes."""
        el1 = [
            InteractiveElement(id="title", text="Photos", desc="", bounds=(0, 0, 100, 50), center=(50, 25), clickable=False),
            InteractiveElement(id="battery_level", text="98%", desc="", bounds=(900, 0, 1000, 50), center=(950, 25), clickable=False),
            InteractiveElement(id="clock", text="12:00", desc="", bounds=(1000, 0, 1080, 50), center=(1040, 25), clickable=False),
            InteractiveElement(id="keep_btn", text="Keep", desc="Keep photo", bounds=(10, 100, 200, 150), center=(105, 125), clickable=True),
        ]
        el2 = [
            InteractiveElement(id="title", text="Photos", desc="", bounds=(0, 0, 100, 50), center=(50, 25), clickable=False),
            InteractiveElement(id="battery_level", text="75%", desc="", bounds=(900, 0, 1000, 50), center=(950, 25), clickable=False),
            InteractiveElement(id="clock", text="14:35", desc="", bounds=(1000, 0, 1080, 50), center=(1040, 25), clickable=False),
            InteractiveElement(id="keep_btn", text="Keep", desc="Keep photo", bounds=(10, 100, 200, 150), center=(105, 125), clickable=True),
        ]
        el3 = [
            InteractiveElement(id="title", text="Album", desc="", bounds=(0, 0, 100, 50), center=(50, 25), clickable=False),
            InteractiveElement(id="keep_btn", text="Keep", desc="Keep photo", bounds=(10, 100, 200, 150), center=(105, 125), clickable=True),
        ]
        sig1 = compute_screen_signature(el1)
        sig2 = compute_screen_signature(el2)
        sig3 = compute_screen_signature(el3)
        self.assertEqual(sig1, sig2)
        self.assertNotEqual(sig1, sig3)

    def test_cycle_detector_stagnation(self) -> None:
        """Verifies period 1 stagnation after 3 consecutive identical screens."""
        cd = CycleDetector()
        self.assertIsNone(cd.record(1, "sig_a", "screen_a"))
        self.assertIsNone(cd.record(2, "sig_a", "screen_a"))
        event = cd.record(3, "sig_a", "screen_a")
        self.assertIsNotNone(event)
        self.assertEqual(event.cycle_type, "stagnation")
        self.assertEqual(event.period, 1)
        self.assertEqual(event.steps, [1, 2, 3])

    def test_cycle_detector_oscillation(self) -> None:
        """Verifies period 2 ping-pong cycle (A -> B -> A -> B)."""
        cd = CycleDetector()
        self.assertIsNone(cd.record(1, "sig_a", "screen_a"))
        self.assertIsNone(cd.record(2, "sig_b", "screen_b"))
        self.assertIsNone(cd.record(3, "sig_a", "screen_a"))
        event = cd.record(4, "sig_b", "screen_b")
        self.assertIsNotNone(event)
        self.assertEqual(event.cycle_type, "oscillation")
        self.assertEqual(event.period, 2)
        self.assertEqual(event.steps, [1, 2, 3, 4])

    def test_cycle_detector_loop(self) -> None:
        """Verifies period 3 loop (A -> B -> C -> A -> B -> C)."""
        cd = CycleDetector()
        for idx, (sig, name) in enumerate([
            ("sig_a", "a"), ("sig_b", "b"), ("sig_c", "c"),
            ("sig_a", "a"), ("sig_b", "b")
        ], start=1):
            self.assertIsNone(cd.record(idx, sig, name))
        event = cd.record(6, "sig_c", "c")
        self.assertIsNotNone(event)
        self.assertEqual(event.cycle_type, "loop")
        self.assertEqual(event.period, 3)
        self.assertEqual(event.steps, [1, 2, 3, 4, 5, 6])

    def test_exploration_goal_achievement(self) -> None:
        """Verify that reaching the charter goal terminates loop with pass status and trajectory log."""
        out = self.base / "goal_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "landing_card", "expected_screen": "landing-sources"},
                {"action": "tap", "target": "folder_card", "expected_screen": "feed"},
                {"action": "finish", "reason": "goal reached"},
            ]
        )
        res = run_exploration(driver=driver, charter=self.charter, step_budget=10, agent=agent, out_dir=out)
        self.assertIn(res["status"], ("pass", "goal_reached"))
        self.assertTrue(res["goal_achieved"])
        self.assertLessEqual(res["steps_taken"], 10)
        self.assertTrue((out / "exploration.json").is_file())
        saved = json.loads((out / "exploration.json").read_text(encoding="utf-8"))
        self.assertEqual(saved["charter_id"], "test_commuter")
        self.assertEqual(len(saved["trajectory"]), res["steps_taken"])

    def test_exploration_budget_exhaustion(self) -> None:
        """Verify that loop strictly halts when step budget is reached without completing goal."""
        out = self.base / "budget_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(scripted_actions=[{"action": "swipe", "direction": "up"}] * 20)
        res = run_exploration(driver=driver, charter=self.charter, step_budget=5, agent=agent, out_dir=out)
        self.assertEqual(res["status"], "budget_exhausted")
        self.assertEqual(res["steps_taken"], 5)
        self.assertFalse(res["goal_achieved"])
        self.assertEqual(len(res["trajectory"]), 5)

    def test_exploration_cycle_detection(self) -> None:
        """Verify that an oscillating screen cycle trips the cycle detector and halts early."""
        out = self.base / "cycle_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "next", "screen_name": "screen_A"},
                {"action": "tap", "target": "back", "screen_name": "screen_B"},
                {"action": "tap", "target": "next", "screen_name": "screen_A"},
                {"action": "tap", "target": "back", "screen_name": "screen_B"},
                {"action": "tap", "target": "next", "screen_name": "screen_A"},
            ]
        )
        res = run_exploration(driver=driver, charter=self.charter, step_budget=20, agent=agent, out_dir=out)
        self.assertIn(res["status"], ("dead_end", "cycle_detected"))
        self.assertGreaterEqual(res["dead_ends"], 1)
        self.assertLess(res["steps_taken"], 10)
        self.assertTrue(any(f.get("kind") in ("discoverability", "cycle") or f.get("rule") == "discoverability-dead-end" for f in res["findings"]))

    def test_exploration_dead_end_detection(self) -> None:
        """Verify that an agent stuck or hitting a dead end produces a discoverability finding."""
        out = self.base / "dead_end_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "stuck", "reason": "No visible control to proceed to photos"}
            ]
        )
        res = run_exploration(driver=driver, charter=self.charter, step_budget=15, agent=agent, out_dir=out)
        self.assertEqual(res["status"], "dead_end")
        self.assertGreaterEqual(len(res["findings"]), 1)
        self.assertEqual(res["findings"][0]["kind"], "discoverability")
        self.assertIn("No visible control", res["findings"][0]["detail"])

    def test_exploration_charter_never_constraint(self) -> None:
        """Verify that prohibited actions trigger safety findings and are rejected."""
        out = self.base / "never_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        charter = {
            "id": "safe_cull",
            "goal": "Cull safely",
            "never": ["delete_outside_app", "forbidden_card"],
            "step_budget": 5,
        }
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "forbidden_card"},
                {"action": "finish", "reason": "done"},
            ]
        )
        res = run_exploration(driver=driver, charter=charter, step_budget=5, agent=agent, out_dir=out)
        self.assertTrue(any(f.get("rule") == "charter-boundary-violation" for f in res["findings"]))
        self.assertEqual(res["trajectory"][0]["status"], "prohibited")

    def test_exploration_charter_from_markdown_never_cleaning(self) -> None:
        """Verify ExplorationCharter.from_markdown strips parentheticals, conjunctions, and extracts backticked tokens."""
        md = (
            "# Charter: Test Clean\n"
            "Device: phone\n"
            "Goal: Test Never parsing\n"
            "Never: open other apps, leave `PSTLab`, tap `landing_phone_camera_card` (the lab journey J-TOK-06 covers it without granting), or delete outside the app.\n"
        )
        c = ExplorationCharter.from_markdown(md, charter_id="test_clean")
        self.assertIn("open other apps", c.never)
        self.assertIn("leave PSTLab", c.never)
        self.assertNotIn("PSTLab", c.never)
        self.assertIn("tap landing_phone_camera_card", c.never)
        self.assertIn("landing_phone_camera_card", c.never)
        self.assertIn("delete outside the app", c.never)
        self.assertNotIn("or delete outside the app", c.never)
        self.assertFalse(any("(" in n or ")" in n for n in c.never))

    def test_exploration_charter_boundary_scope_generality(self) -> None:
        """Verify boundary scope parsing generality across arbitrary directory and container names (PSTLab, DCIM, etc.)."""
        for folder_name in ["PSTLab", "DCIM"]:
            md = (
                f"# Charter: Scope Test {folder_name}\n"
                f"Device: phone\n"
                f"Goal: Test boundary generality for {folder_name}\n"
                f"Never: open other apps, leave `{folder_name}`, tap `delete_btn`, tap `landing_phone_camera_card`.\n"
            )
            c = ExplorationCharter.from_markdown(md, charter_id=f"scope_{folder_name.lower()}")

            # 1. Neither "PSTLab" nor "DCIM" is extracted as a standalone prohibited UI target in c.never
            self.assertNotIn(folder_name, c.never)
            self.assertIn(f"leave {folder_name}", c.never)

            # 2. Prohibited UI element actions like tap `delete_btn` or tap `landing_phone_camera_card` are extracted
            self.assertIn("delete_btn", c.never)
            self.assertIn("tap delete_btn", c.never)
            self.assertIn("landing_phone_camera_card", c.never)
            self.assertIn("tap landing_phone_camera_card", c.never)

            out = self.base / f"scope_run_{folder_name.lower()}"
            driver = MockDriver("phototok", app_id="test.app", out_dir=out)

            # 3. Legitimate actions on screens containing those names are permitted and not blocked
            legitimate_actions = [
                {"action": "tap", "target": folder_name},
                {"action": "tap", "target": f"{folder_name}_folder_card"},
                {"action": "tap", "target": "keep_button"},
                {"action": "select", "target": f"photo_in_{folder_name}"},
            ]
            for act in legitimate_actions:
                agent_legit = MockExplorationAgent(
                    scripted_actions=[
                        act,
                        {"action": "finish", "reason": "done"},
                    ]
                )
                res_legit = run_exploration(driver=driver, charter=c, step_budget=5, agent=agent_legit, out_dir=out)
                self.assertEqual(res_legit["trajectory"][0]["status"], "ok", f"Action {act} was unexpectedly blocked")
                violations = [f for f in res_legit["findings"] if f.get("rule") == "charter-boundary-violation"]
                self.assertEqual(len(violations), 0, f"Action {act} generated boundary violations: {violations}")

            # 4. An action that explicitly says "leave <folder_name>" is blocked because full phrase is preserved
            agent_boundary = MockExplorationAgent(
                scripted_actions=[
                    {"action": f"leave {folder_name}"},
                    {"action": "finish", "reason": "done"},
                ]
            )
            res_boundary = run_exploration(driver=driver, charter=c, step_budget=5, agent=agent_boundary, out_dir=out)
            self.assertEqual(res_boundary["trajectory"][0]["status"], "prohibited")
            self.assertTrue(any(f.get("rule") == "charter-boundary-violation" for f in res_boundary["findings"]))

            # 5. Prohibited UI element actions are blocked
            for prohibited_target in ["delete_btn", "landing_phone_camera_card"]:
                agent_prohibited = MockExplorationAgent(
                    scripted_actions=[
                        {"action": "tap", "target": prohibited_target},
                        {"action": "finish", "reason": "done"},
                    ]
                )
                res_prohibited = run_exploration(driver=driver, charter=c, step_budget=5, agent=agent_prohibited, out_dir=out)
                self.assertEqual(res_prohibited["trajectory"][0]["status"], "prohibited")
                self.assertTrue(any(f.get("rule") == "charter-boundary-violation" for f in res_prohibited["findings"]))

        # Also test boundary scope phrases like 'stay in' and 'remain in'
        md_boundary_phrases = (
            "# Charter: Boundary phrases\n"
            "Device: phone\n"
            "Never: stay in `CameraRoll`, remain in `SDCard`.\n"
        )
        c_phrases = ExplorationCharter.from_markdown(md_boundary_phrases, charter_id="boundary_phrases")
        self.assertIn("stay in CameraRoll", c_phrases.never)
        self.assertNotIn("CameraRoll", c_phrases.never)
        self.assertIn("remain in SDCard", c_phrases.never)
        self.assertNotIn("SDCard", c_phrases.never)

    def test_exploration_real_charter_commuter_prohibited_boundary(self) -> None:
        """Verify real charter private/lab/explorer/charters/commuter.md catches tap of landing_phone_camera_card."""
        charter_path = repo_root() / "private" / "lab" / "explorer" / "charters" / "commuter.md"
        if not charter_path.is_file():
            self.skipTest("commuter.md not present in this checkout")

        c_real = load_charter(charter_path)
        out = self.base / "commuter_boundary_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "landing_phone_camera_card"},
                {"action": "finish", "reason": "done"},
            ]
        )
        res = run_exploration(driver=driver, charter=c_real, step_budget=5, agent=agent, out_dir=out)
        prohibited = [f for f in res["findings"] if f.get("rule") == "charter-boundary-violation"]
        self.assertEqual(len(prohibited), 1)
        self.assertIn("landing_phone_camera_card", prohibited[0]["detail"])
        self.assertEqual(res["trajectory"][0]["status"], "prohibited")

    def test_exploration_real_charter_commuter_tap_dict_syntax(self) -> None:
        """Verify real charter catches tap with {'tap': 'landing_phone_camera_card'} syntax and doesn't crash."""
        charter_path = repo_root() / "private" / "lab" / "explorer" / "charters" / "commuter.md"
        if not charter_path.is_file():
            self.skipTest("commuter.md not present in this checkout")

        c_real = load_charter(charter_path)
        out = self.base / "commuter_boundary_tap_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"tap": "landing_phone_camera_card"},
                {"action": "finish", "reason": "done"},
            ]
        )
        res = run_exploration(driver=driver, charter=c_real, step_budget=5, agent=agent, out_dir=out)
        prohibited = [f for f in res["findings"] if f.get("rule") == "charter-boundary-violation"]
        self.assertEqual(len(prohibited), 1)
        self.assertEqual(res["trajectory"][0]["status"], "prohibited")
        self.assertIn("landing_phone_camera_card", res["trajectory"][0]["action_description"])

    def test_exploration_real_charter_commuter_permits_legitimate_actions(self) -> None:
        """Verify real charter commuter.md blocks landing_phone_camera_card but permits legitimate navigation."""
        charter_path = repo_root() / "private" / "lab" / "explorer" / "charters" / "commuter.md"
        if not charter_path.is_file():
            self.skipTest("commuter.md not present in this checkout")

        c_real = load_charter(charter_path)
        out = self.base / "commuter_legitimate_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)

        # 1. Prohibited action is blocked
        agent_prohibited = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "landing_phone_camera_card"},
                {"action": "finish", "reason": "done"},
            ]
        )
        res_prohibited = run_exploration(driver=driver, charter=c_real, step_budget=5, agent=agent_prohibited, out_dir=out)
        self.assertEqual(res_prohibited["trajectory"][0]["status"], "prohibited")
        self.assertTrue(any(f.get("rule") == "charter-boundary-violation" for f in res_prohibited["findings"]))

        # 2. Legitimate actions are permitted with status "ok"
        legitimate_actions = [
            {"action": "tap", "target": "landing_custom_folder_card"},
            {"action": "tap", "target": "PSTLab"},
            {"action": "tap", "target": "revert_button"},
            {"action": "delete", "target": "unsharp_frame.jpg"},
        ]
        for act in legitimate_actions:
            agent_legit = MockExplorationAgent(
                scripted_actions=[
                    act,
                    {"action": "finish", "reason": "done"},
                ]
            )
            res_legit = run_exploration(driver=driver, charter=c_real, step_budget=5, agent=agent_legit, out_dir=out)
            status = res_legit["trajectory"][0]["status"]
            violations = [f for f in res_legit["findings"] if f.get("rule") == "charter-boundary-violation"]
            self.assertEqual(status, "ok", f"Action {act} was unexpectedly blocked: {violations}")
            self.assertEqual(len(violations), 0, f"Action {act} caused violations: {violations}")

    def test_exploration_real_charters_permit_culling_delete_actions(self) -> None:
        """Verify that under couch-culler.md, expedition.md, and hybrid-shooter.md, delete actions are permitted."""
        charters_dir = repo_root() / "private" / "lab" / "explorer" / "charters"
        target_charters = ["couch-culler.md", "expedition.md", "hybrid-shooter.md"]

        for c_name in target_charters:
            charter_path = charters_dir / c_name
            if not charter_path.is_file():
                continue

            c = load_charter(charter_path)
            out = self.base / f"delete_run_{c.id}"
            driver = MockDriver("phototok", app_id="test.app", out_dir=out)
            agent = MockExplorationAgent(
                scripted_actions=[
                    {"action": "delete", "target": "frame.jpg"},
                    {"action": "finish", "reason": "done"},
                ]
            )
            res = run_exploration(driver=driver, charter=c, step_budget=5, agent=agent, out_dir=out)
            status = res["trajectory"][0]["status"]
            violations = [f for f in res["findings"] if f.get("rule") == "charter-boundary-violation"]
            self.assertEqual(status, "ok", f"Delete was unexpectedly blocked under {c_name}: {violations}")
            self.assertEqual(len(violations), 0, f"Delete under {c_name} caused violations: {violations}")

    def test_exploration_trajectory_schema_and_persistence(self) -> None:
        """Verify that trajectory records metrics, screen hashes, timestamps, and findings."""
        out = self.base / "persist_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "tap", "target": "landing_card", "screen_name": "landing"},
                {"action": "finish", "reason": "done"},
            ]
        )
        res = run_exploration(driver=driver, charter=self.charter, step_budget=5, agent=agent, out_dir=out)
        self.assertIsNotNone(res)
        traj_path = out / "exploration.json"
        self.assertTrue(traj_path.is_file())
        data = json.loads(traj_path.read_text(encoding="utf-8"))
        for required_key in ("charter_id", "product", "status", "budget", "steps_taken", "trajectory", "findings"):
            self.assertIn(required_key, data)
        step_entry = data["trajectory"][0]
        self.assertIn("step", step_entry)
        self.assertIn("action", step_entry)
        self.assertIn("metrics", step_entry)
        self.assertIn("timestamp", step_entry)

    def test_findings_schema_compliance(self) -> None:
        """Validates generated findings against scripts/lab/schemas/finding.schema.json."""
        out = self.base / "schema_run"
        driver = MockDriver("phototok", app_id="test.app", out_dir=out)
        agent = MockExplorationAgent(
            scripted_actions=[
                {"action": "stuck", "reason": "Dead end hit"},
            ]
        )
        res = run_exploration(driver=driver, charter=self.charter, step_budget=5, agent=agent, out_dir=out)
        findings = res["findings"]
        self.assertGreaterEqual(len(findings), 1)

        schema_path = repo_root() / "scripts" / "lab" / "schemas" / "finding.schema.json"
        self.assertTrue(schema_path.is_file())
        schema = json.loads(schema_path.read_text(encoding="utf-8"))

        required_props = schema.get("required", [])
        for f in findings:
            for prop in required_props:
                self.assertIn(prop, f, f"Finding missing required property: {prop}")
            self.assertEqual(f["source"], "ai-explorer")
            self.assertIn(f["product"], ("phototok", "android-desktop", "macos", "linux"))
            self.assertIn(f["severity"], ("blocker", "major", "minor", "polish"))
            self.assertIn(f["confidence"], ("high", "medium", "low"))
            self.assertIsInstance(f["evidence"], list)
            self.assertIsInstance(f["new_since_last_run"], bool)


class TestModeratorSynthesis(unittest.TestCase):
    """Unit tests for actionable moderator synthesis and reporting enhancements."""

    def setUp(self) -> None:
        self.base = _scratch("test_moderator_synthesis")
        self.walkthrough = {
            "product": "phototok",
            "captured_at": "2026-10-22T12:00:00+00:00",
            "duration_s": 2.5,
            "device": {"model": "Pixel 4a", "android": "13", "size": "1080x2340", "density": 440},
            "scenarios": [
                {
                    "scenario": "U-TOK-01",
                    "title": "First launch",
                    "goal": "Open photos",
                    "use_case": "UC-1, UC-3",
                    "status": "pass",
                    "screens": [
                        {
                            "name": "landing",
                            "image": "01_landing.png",
                            "intent": "Open photos",
                            "expected": "Tap Folder or Cloud",
                            "actions_before": [],
                            "metrics": {"words": 15, "reading_seconds": 4.2, "longest_block_words": 10, "small_targets": 0, "texts": []},
                        },
                        {
                            "name": "feed",
                            "image": "02_feed.png",
                            "intent": "Browse photos",
                            "expected": "Swipe up",
                            "actions_before": ["Tapped Folder or Cloud"],
                            "metrics": {"words": 5, "reading_seconds": 1.4, "longest_block_words": 5, "small_targets": 0, "texts": []},
                        },
                    ],
                },
                {
                    "scenario": "U-TOK-02",
                    "title": "Culling",
                    "goal": "Delete bad photo",
                    "use_case": "UC-1",
                    "status": "pass",
                    "screens": [
                        {
                            "name": "photo",
                            "image": "03_photo.png",
                            "intent": "Cull photo",
                            "expected": "Swipe left",
                            "actions_before": ["Swiped up"],
                            "metrics": {"words": 20, "reading_seconds": 5.7, "longest_block_words": 12, "small_targets": 0, "texts": []},
                        }
                    ],
                },
            ],
        }

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_parse_use_cases(self) -> None:
        self.assertEqual(parse_use_cases("UC-1, UC-3"), ["UC-1", "UC-3"])
        self.assertEqual(parse_use_cases("UC-4"), ["UC-4"])
        self.assertEqual(parse_use_cases(["UC-5", "UC-6, UC-7"]), ["UC-5", "UC-6", "UC-7"])
        self.assertEqual(parse_use_cases(None), [])

    def test_use_case_fit_mapping_and_calculation(self) -> None:
        """Verify calculation of UC-* fit scores across scenarios and persona answers."""
        answers = {
            "commuter": {
                "persona": "commuter",
                "screens": [
                    {"screen": "U-TOK-01/landing", "verdict": "good"},
                    {"screen": "U-TOK-01/feed", "verdict": "good"},
                    {"screen": "U-TOK-02/photo", "verdict": "good"},
                ],
            },
            "newcomer": {
                "persona": "newcomer",
                "screens": [
                    {"screen": "U-TOK-01/landing", "verdict": "bad"},
                    {"screen": "U-TOK-01/feed", "verdict": "ok"},
                    {"screen": "U-TOK-02/photo", "verdict": "bad"},
                ],
            },
        }

        fit_by_uc = compute_use_case_fit(self.walkthrough, answers)
        self.assertIn("UC-1", fit_by_uc)
        self.assertIn("UC-3", fit_by_uc)
        uc1 = fit_by_uc["UC-1"]
        self.assertEqual(uc1["personas"]["commuter"]["fit"], "good")
        self.assertEqual(uc1["personas"]["newcomer"]["fit"], "poor")
        self.assertIn(uc1["overall_fit"], ("mixed", "ok"))

    def test_discoverability_misses_extraction(self) -> None:
        """Verify extraction of discoverability gaps between expected and would_do."""
        answers = {
            "commuter": {
                "persona": "commuter",
                "screens": [
                    {"screen": "U-TOK-01/landing", "would_do": "Tap Folder or Cloud"},
                ],
            },
            "newcomer": {
                "persona": "newcomer",
                "screens": [
                    {"screen": "U-TOK-01/landing", "would_do": "Look for Settings menu"},
                ],
            },
        }

        exploration = {
            "status": "dead_end",
            "findings": [
                {
                    "kind": "discoverability",
                    "screen": "landing",
                    "scenario": "U-TOK-01",
                    "detail": "Agent cycled searching for cloud import",
                }
            ],
        }

        gaps = extract_discoverability_gaps(self.walkthrough, answers, exploration=exploration)
        self.assertGreaterEqual(len(gaps), 1)
        landing_gap = next((g for g in gaps if "landing" in g["screen"]), None)
        self.assertIsNotNone(landing_gap)
        self.assertEqual(landing_gap["expected"], "Tap Folder or Cloud")
        self.assertTrue(any(m["persona"] == "newcomer" and "Settings" in m["would_do"] for m in landing_gap["missed_by"]))
        self.assertTrue(landing_gap.get("has_exploration_dead_end", False))

        misses = extract_discoverability_misses(self.walkthrough, answers, exploration=exploration)
        self.assertGreaterEqual(len(misses), 1)

    def test_synthesize_tradeoffs(self) -> None:
        """Verify automatic trade-off card synthesis from split screens."""
        answers = {
            "commuter": {
                "screens": [
                    {"screen": "U-TOK-01/landing", "verdict": "good", "would_do": "Swipe left", "issues": []},
                ],
                "overall": {},
            },
            "newcomer": {
                "screens": [
                    {"screen": "U-TOK-01/landing", "verdict": "bad", "would_do": "Tap trash can", "issues": [{"what": "Accidental deletion without confirm", "severity": "major", "kind": "trust"}]},
                ],
                "overall": {},
            },
        }

        tradeoffs = synthesize_tradeoffs(self.walkthrough, answers)
        self.assertEqual(len(tradeoffs), 1)
        t = tradeoffs[0]
        self.assertEqual(t["screens"], ["U-TOK-01/landing"])
        self.assertIn("commuter", t["tension"])
        self.assertIn("newcomer", t["tension"])
        self.assertEqual(len(t["options"]), 3)
        self.assertIn("commuter", t["options"][0]["helps"])
        self.assertIn("newcomer", t["options"][0]["hurts"])
        self.assertTrue(t["recommendation"])
        self.assertTrue(t["decide_if"])

    def test_tradeoffs_rendering_html_and_markdown(self) -> None:
        """Verify rendering of multi-option trade-off cards in HTML and Markdown reports."""
        answers = {
            "commuter": {"persona": "commuter", "screens": [{"screen": "U-TOK-01/landing", "verdict": "good"}]},
            "newcomer": {"persona": "newcomer", "screens": [{"screen": "U-TOK-01/landing", "verdict": "bad"}]},
        }
        decisions = {
            "summary": "Clear tension between speed and caution.",
            "tradeoffs": [
                {
                    "title": "One-Tap Delete vs Confirm Dialog",
                    "screens": ["U-TOK-01/landing"],
                    "tension": "Speed vs safety",
                    "options": [
                        {"option": "Instant delete", "helps": ["commuter"], "hurts": ["newcomer"], "cost": "Undo snackbar"},
                        {"option": "Modal confirm", "helps": ["newcomer"], "hurts": ["commuter"], "cost": "One tap per item"},
                    ],
                    "recommendation": "Instant delete with Undo snackbar",
                    "decide_if": "If mobile culling is the primary use case",
                }
            ],
            "consensus": [
                {
                    "title": "Low contrast button",
                    "screens": ["U-TOK-01/landing"],
                    "personas": ["commuter", "newcomer"],
                    "severity": "blocker",
                    "evidence": "Contrast ratio 2.1:1",
                    "suggestion": "Use darker tint",
                }
            ],
            "discoverability": [],
            "keep": [{"title": "Fast scroll", "screens": ["U-TOK-01/landing"], "personas": ["commuter"]}],
            "persona_fit": [{"persona": "commuter", "fit": "good", "note": "Satisfied"}],
        }

        html = render_html(self.walkthrough, answers, decisions, self.base)
        self.assertIn("One-Tap Delete vs Confirm Dialog", html)
        self.assertIn("helps: commuter", html)
        self.assertIn("hurts: newcomer", html)
        self.assertIn("cost: Undo snackbar", html)
        self.assertIn("blocker", html)

        md = render_markdown(self.walkthrough, answers, decisions)
        self.assertIn("### One-Tap Delete vs Confirm Dialog", md)
        self.assertIn("Instant delete", md)
        self.assertIn("Modal confirm", md)
        self.assertIn("Recommendation: Instant delete with Undo snackbar", md)

    def test_write_report_end_to_end_with_exploration(self) -> None:
        """Verify full write_report writes both html and md reports with exploration traces."""
        run_dir = self.base / "report_run"
        panel_dir = run_dir / "panel"
        answers_dir = panel_dir / "answers"
        answers_dir.mkdir(parents=True)
        img_dir = panel_dir / "images"
        img_dir.mkdir(parents=True)

        (run_dir / "walkthrough.json").write_text(json.dumps(self.walkthrough), encoding="utf-8")
        commuter_ans = {
            "persona": "commuter",
            "screens": [
                {"screen": "U-TOK-01/landing", "verdict": "good", "first_impression": "Fast", "read_text": "none", "would_do": "Tap Folder", "issues": []},
            ],
            "overall": {"would_keep_using": "yes", "why": "great", "top_problems": [], "top_strengths": ["speed"], "one_change": ""},
        }
        (answers_dir / "commuter.json").write_text(json.dumps(commuter_ans), encoding="utf-8")

        exploration = {
            "persona": "MockExplorer",
            "status": "pass",
            "trajectory": [{"screen": "landing", "action": "tap"}],
            "dead_ends": [],
        }
        (run_dir / "exploration.json").write_text(json.dumps(exploration), encoding="utf-8")

        html_p, md_p = write_report(run_dir)
        self.assertTrue(html_p.is_file())
        self.assertTrue(md_p.is_file())

        html_text = html_p.read_text(encoding="utf-8")
        md_text = md_p.read_text(encoding="utf-8")

        self.assertIn("Use-case fit (UC-* flows)", html_text)
        self.assertIn("UC-1", html_text)
        self.assertIn("## Use-case fit (UC-* flows)", md_text)
        self.assertIn("| UC-1 |", md_text)

    def test_write_report_with_integer_dead_ends_from_exploration(self) -> None:
        """Verify write_report handles real exploration output where dead_ends is an integer without TypeError."""
        run_dir = self.base / "report_run_int_dead_ends"
        panel_dir = run_dir / "panel"
        answers_dir = panel_dir / "answers"
        answers_dir.mkdir(parents=True)
        img_dir = panel_dir / "images"
        img_dir.mkdir(parents=True)

        (run_dir / "walkthrough.json").write_text(json.dumps(self.walkthrough), encoding="utf-8")
        commuter_ans = {
            "persona": "commuter",
            "screens": [
                {"screen": "U-TOK-01/landing", "verdict": "good", "first_impression": "Fast", "read_text": "none", "would_do": "Tap Folder", "issues": []},
            ],
            "overall": {"would_keep_using": "yes", "why": "great", "top_problems": [], "top_strengths": ["speed"], "one_change": ""},
        }
        (answers_dir / "commuter.json").write_text(json.dumps(commuter_ans), encoding="utf-8")

        # In real exploration runs, run_exploration sets "dead_ends": int
        exploration = {
            "persona": "MockExplorer",
            "status": "dead_end",
            "failure_reason": "Cycle detected",
            "trajectory": [{"screen": "landing", "action": "tap"}],
            "dead_ends": 1,
            "findings": [
                {
                    "rule": "discoverability-dead-end",
                    "checkpoint": "landing",
                    "message": "Stuck in navigation cycle",
                }
            ],
        }
        (run_dir / "exploration.json").write_text(json.dumps(exploration), encoding="utf-8")

        html_p, md_p = write_report(run_dir)
        self.assertTrue(html_p.is_file())
        self.assertTrue(md_p.is_file())

        html_text = html_p.read_text(encoding="utf-8")
        md_text = md_p.read_text(encoding="utf-8")
        self.assertIn("Stuck in navigation cycle", html_text)
        self.assertIn("Stuck in navigation cycle", md_text)

    def test_write_report_handles_null_severity_consensus(self) -> None:
        """Verify write_report safely renders consensus issues when severity is null (None)."""
        run_dir = self.base / "report_run_null_severity"
        panel_dir = run_dir / "panel"
        panel_dir.mkdir(parents=True)
        (panel_dir / "answers").mkdir(parents=True)
        (panel_dir / "images").mkdir(parents=True)

        (run_dir / "walkthrough.json").write_text(json.dumps(self.walkthrough), encoding="utf-8")
        (panel_dir / "answers" / "commuter.json").write_text(json.dumps({
            "persona": "commuter",
            "screens": [{"screen": "U-TOK-01/landing", "verdict": "good", "would_do": "tap", "issues": []}],
            "overall": {"would_keep_using": "yes", "why": "", "top_problems": [], "top_strengths": [], "one_change": ""},
        }), encoding="utf-8")

        decisions = {
            "consensus": [
                {
                    "title": "Unclear button",
                    "severity": None,
                    "screens": ["landing"],
                    "personas": ["commuter"],
                    "suggestion": "Add text",
                    "evidence": "Observed hesitation",
                }
            ]
        }
        (panel_dir / "decisions.json").write_text(json.dumps(decisions), encoding="utf-8")

        html_p, md_p = write_report(run_dir)
        self.assertTrue(html_p.is_file())
        self.assertTrue(md_p.is_file())
        md_text = md_p.read_text(encoding="utf-8")
        self.assertIn("[MAJOR] Unclear button", md_text)


class TestUsabilityExploreCli(unittest.TestCase):
    """Unit tests for the usability-explore CLI command and arguments."""

    def setUp(self) -> None:
        self.base = _scratch("test_explore_cli")
        self.charter_file = self.base / "test_charter.md"
        self.charter_file.write_text(
            "# Charter: Commuter\n"
            "Device: phone\n"
            "Goal: Test exploration CLI\n"
            "Time box: 10 actions\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        shutil.rmtree(self.base, ignore_errors=True)

    def test_cli_parser_usability_explore_defaults(self) -> None:
        """Verify CLI parser parses defaults for usability-explore."""
        parser = build_parser()
        args = parser.parse_args(["usability-explore", "--charter", str(self.charter_file)])
        self.assertEqual(args.product, "phototok")
        self.assertEqual(args.agent, "mock")
        self.assertEqual(args.budget, 25)
        self.assertEqual(args.driver, "auto")
        self.assertIsNone(args.serial)
        self.assertIsNone(args.out)

    def test_cli_parser_usability_explore_custom_args(self) -> None:
        """Verify CLI parser accepts custom arguments for all options."""
        parser = build_parser()
        args = parser.parse_args([
            "usability-explore",
            "--product", "macos",
            "--charter", str(self.charter_file),
            "--agent", "gemini",
            "--budget", "12",
            "--driver", "mock",
            "--out", str(self.base / "out"),
        ])
        self.assertEqual(args.product, "macos")
        self.assertEqual(args.agent, "gemini")
        self.assertEqual(args.budget, 12)
        self.assertEqual(args.driver, "mock")
        self.assertEqual(args.out, str(self.base / "out"))

    def test_cli_rejects_missing_charter(self) -> None:
        """Verify parser refuses invocation without --charter."""
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["usability-explore"])

    def test_cli_android_requires_serial_for_hardware(self) -> None:
        """Verify main() returns code 2 if Android hardware exploration is called without serial."""
        code = main([
            "usability-explore",
            "--product", "phototok",
            "--charter", str(self.charter_file),
            "--driver", "auto",
        ])
        self.assertEqual(code, 2)

    def test_cli_usability_explore_mock_execution(self) -> None:
        """Verify mock execution runs cleanly, produces exploration.json, and returns 0."""
        out = self.base / "mock_cli_run"
        code = main([
            "usability-explore",
            "--product", "phototok",
            "--charter", str(self.charter_file),
            "--agent", "mock",
            "--driver", "mock",
            "--budget", "5",
            "--out", str(out),
        ])
        self.assertIn(code, (0, 1))
        self.assertTrue((out / "exploration.json").is_file())
        exp_data = json.loads((out / "exploration.json").read_text(encoding="utf-8"))
        self.assertEqual(exp_data["product"], "phototok")
        self.assertLessEqual(exp_data["steps_taken"], 5)


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


class TestTextLoadShapes(unittest.TestCase):
    def test_full_screen_overlay_is_a_container_and_a_one_line_row_is_a_row(self) -> None:
        from lab.usability.textload import analyse

        callouts = "".join(_node(text=t, bounds=f"[100,{300 + 60 * i}][400,{340 + 60 * i}]")
                           for i, t in enumerate(["Next", "Delete", "Copy", "Zoom", "Selection", "Undo delete"]))
        overlay = _node(rid="controls_guide", clickable=True, bounds="[0,0][1080,1920]", children=callouts)
        row = _node(clickable=True, bounds="[0,1500][1080,1640]", children=_node(text="Link RAW + JPEG", bounds="[40,1520][700,1600]"))
        tl = analyse(_xml(overlay, row))
        self.assertEqual(tl["wordy_controls"], 0)
        self.assertEqual(tl["verdict"], "ok")
        self.assertEqual([c["kind"] for c in tl["controls"]], ["row"])

    def test_a_screen_of_more_than_40_words_is_over_even_without_controls(self) -> None:
        from lab.usability.textload import analyse

        body = _node(text=" ".join(["word"] * 41), bounds="[0,300][1080,900]")
        tl = analyse(_xml(body))
        self.assertTrue(tl["text_heavy"])
        self.assertEqual(tl["verdict"], "over")


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


class TestTapsAvoidTheNavigationBar(unittest.TestCase):
    def test_a_target_half_under_the_navigation_bar_is_scrolled_into_view_first(self) -> None:
        nav = _node(rid="navigationBarBackground", bounds="[0,1794][1080,1920]")
        low = _xml(_node(rid="card", clickable=True, bounds="[42,1793][1038,1919]"), nav)
        high = _xml(_node(rid="card", clickable=True, bounds="[42,1400][1038,1600]"), nav)
        adb = FakeAdb([low, high])
        out = _scratch("test_usability_navbar")
        try:
            d = drv.ScenarioDriver(adb, "com.phototok.lab", out, settle_ms=0)
            d.run({"id": "U", "steps": [{"tap": {"id": "card", "scroll": True}}]})
        finally:
            shutil.rmtree(out, ignore_errors=True)
        swipes = [i for i in adb.inputs if i[0] == "swipe"]
        taps = [i for i in adb.inputs if i[0] == "tap"]
        self.assertEqual(len(swipes), 1)
        self.assertEqual(taps, [("tap", "540", "1500")])


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
