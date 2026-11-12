"""Usability walks: a defined start per scenario and per exploration, state-aware toggles, the picker tap.

Backlog 2026-10-24 (three entries): scenarios inherited the last one's snackbar and toggles, an
exploration began wherever the app resumed, and DocumentsUI on the API 30 reference AVD ignored
`adb shell input tap`.
"""

from __future__ import annotations

import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from test_usability import FakeAdb  # noqa: E402

from lab.paths import repo_root  # noqa: E402
from lab.usability import capture as cap  # noqa: E402
from lab.usability import driver as drv  # noqa: E402
from lab.usability.capture import load_scenarios  # noqa: E402
from lab.usability.explore import ExplorationCharter, opening_steps  # noqa: E402


def xml(*nodes: str) -> str:
    return ("<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\">"
            f'<node package="x" resource-id="" text="" content-desc="" bounds="[0,0][1080,1920]">{"".join(nodes)}</node></hierarchy>')


def node(rid: str = "", text: str = "", desc: str = "", bounds: str = "[100,100][300,200]", package: str = "app",
         checked: bool = False, selected: bool = False) -> str:
    return (f'<node package="{package}" resource-id="{rid}" text="{text}" content-desc="{desc}" clickable="true" '
            f'checked="{str(checked).lower()}" selected="{str(selected).lower()}" bounds="{bounds}"/>')


class DriverCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)

    def driver(self, screens: list[str], maestro: Any = None) -> tuple[drv.ScenarioDriver, FakeAdb]:
        adb = FakeAdb(screens)
        d = drv.ScenarioDriver(adb, "app", self.base, settle_ms=0, maestro_fn=maestro)  # type: ignore[arg-type]
        return d, adb

    def taps(self, adb: FakeAdb) -> list[tuple[str, ...]]:
        return [i for i in adb.inputs if i[0] == "tap"]


class TestSetState(DriverCase):
    def run_step(self, tag_state: dict[str, bool], want: bool) -> list[tuple[str, ...]]:
        d, adb = self.driver([xml(node("toggle", bounds="[0,0][100,100]", **tag_state))])
        d._step({"set_state": {"id": "toggle", "on": want}, "settle_ms": 0})
        return self.taps(adb)

    def test_a_toggle_that_is_off_is_tapped_to_turn_it_on(self) -> None:
        self.assertEqual(self.run_step({"checked": False}, True), [("tap", "50", "50")])

    def test_a_toggle_that_already_has_the_state_is_left_alone(self) -> None:
        self.assertEqual(self.run_step({"checked": True}, True), [])
        self.assertEqual(self.run_step({"checked": False}, False), [])

    def test_a_selected_chip_counts_as_on(self) -> None:
        self.assertEqual(self.run_step({"selected": True}, False), [("tap", "50", "50")])


class TestPickerTap(DriverCase):
    PICKER = xml(node(text="PSTLab", bounds="[0,300][500,400]", package="com.google.android.documentsui"))
    TARGET = {"text": "PSTLab"}

    def test_a_picker_that_ignores_the_tap_is_tapped_through_maestro(self) -> None:
        flows: list[str] = []

        def maestro(serial: Any, flow: Path, junit: Path) -> int:
            flows.append(flow.read_text(encoding="utf-8"))
            return 0

        d, adb = self.driver([self.PICKER], maestro)
        d._step({"tap": self.TARGET, "settle_ms": 0})
        self.assertEqual(len(self.taps(adb)), 1)  # the adb tap that did nothing
        self.assertEqual(len(flows), 1)
        self.assertRegex(flows[0], r"appId: com\.google\.android\.documentsui\n---\n- tapOn:\n    text: \"PSTLab\"")

    def test_a_picker_that_reacted_is_not_tapped_again(self) -> None:
        opened = xml(node(text="usability-trip", bounds="[0,300][500,400]", package="com.google.android.documentsui"))
        calls: list[Path] = []
        d, adb = self.driver([self.PICKER, self.PICKER, opened], lambda s, f, j: calls.append(f) or 0)
        d._step({"tap": self.TARGET, "settle_ms": 0})
        self.assertEqual(calls, [])

    def test_an_app_tap_never_goes_through_maestro(self) -> None:
        calls: list[Path] = []
        d, _ = self.driver([xml(node(text="Open Folder", package="app"))], lambda s, f, j: calls.append(f) or 0)
        d._step({"tap": {"text": "Open Folder"}, "settle_ms": 0})
        self.assertEqual(calls, [])

    def test_a_failing_maestro_tap_ends_the_scenario_with_a_reason(self) -> None:
        d, _ = self.driver([self.PICKER], lambda s, f, j: 1)
        with self.assertRaisesRegex(drv.StepFailed, "Maestro could not tap"):
            d._step({"tap": self.TARGET, "settle_ms": 0})

    def test_the_flow_folder_is_removed(self) -> None:
        seen: list[Path] = []
        d, _ = self.driver([self.PICKER], lambda s, f, j: seen.append(f.parent) or 0)
        d._step({"tap": self.TARGET, "settle_ms": 0})
        self.assertFalse(seen[0].exists())


class Recorder(drv.BaseDriver):
    def __init__(self, base: Path) -> None:
        super().__init__("app", base, settle_ms=0)
        self.log: list[str] = []

    def fresh_start(self) -> None:
        self.log.append("fresh")

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        self.log.append(scenario["id"])
        return {"scenario": scenario["id"], "status": "pass", "screens": []}


class TestFreshStart(DriverCase):
    SCENARIOS = [{"id": "A", "steps": [{"launch": {"clear": True}}]}, {"id": "B", "steps": [{"wait_ms": 0}]},
                 {"id": "C", "steps": [{"launch": {}}, {"wait_ms": 0}]}]

    def walk(self, **spec: Any) -> list[str]:
        rec = Recorder(self.base)
        cap._execute_scenarios({"app_id": "app", "scenarios": self.SCENARIOS, **spec}, "android-desktop", rec,
                               self.base / "out", None, None, "auto")
        return rec.log

    def test_each_scenario_after_the_first_starts_from_a_relaunch(self) -> None:
        self.assertEqual(self.walk(fresh_start=True), ["A", "fresh", "B", "C"])  # C opens with its own launch

    def test_without_the_flag_scenarios_continue_as_before(self) -> None:
        self.assertEqual(self.walk(), ["A", "B", "C"])

    def test_frameduel_asks_for_it_and_jackpix_does_not(self) -> None:
        self.assertTrue(load_scenarios("android-desktop", repo_root()).get("fresh_start"))
        self.assertFalse(load_scenarios("phototok", repo_root()).get("fresh_start"))

    def test_fresh_start_stops_and_starts_the_app_without_clearing_it(self) -> None:
        d, adb = self.driver([xml(node())])
        calls: list[tuple[str, ...]] = []
        adb.force_stop = lambda app: calls.append(("force-stop", app)) or ""  # type: ignore[attr-defined]
        d.fresh_start()
        self.assertEqual(calls + [i for i in adb.inputs if i[0] == "start"], [("force-stop", "app"), ("start", "app")])


class TestWalkSteps(unittest.TestCase):
    def test_frameduel_steps_no_longer_toggle_a_state_the_app_defaults_to(self) -> None:
        steps = [s for sc in load_scenarios("android-desktop", repo_root())["scenarios"] for s in sc["steps"]]
        self.assertNotIn({"id": "toggle_thirds", "label": "Thirds"}, [s.get("tap") for s in steps])
        # The details toggle is a switch that is on by default: the walk sets it off from the tree's
        # checked state, it never taps blind (a tap would show what the panel was told is hidden).
        details = [s["set_state"] for s in steps if s.get("set_state", {}).get("id") == "details_toggle"]
        self.assertEqual([d["on"] for d in details], [False])
        self.assertFalse([s for s in steps if s.get("optional_tap", {}).get("id") == "details_toggle"])

    def test_jackpix_hands_pro_mode_back_off_after_u_tok_03(self) -> None:
        sc = next(s for s in load_scenarios("phototok", repo_root())["scenarios"] if s["id"] == "U-TOK-03")
        last = [s for s in sc["steps"] if "screen" not in s][-3:]
        self.assertEqual(last[1]["set_state"]["id"], "settings_pro_mode_toggle")
        self.assertFalse(last[1]["set_state"]["on"])


class TestExplorationStart(unittest.TestCase):
    def test_the_opening_is_the_first_scenario_without_its_screens(self) -> None:
        spec = load_scenarios("phototok", repo_root())
        steps = opening_steps(spec)
        self.assertEqual(steps[0], {"launch": {"clear": True}})
        self.assertTrue(all("screen" not in s for s in steps))
        self.assertEqual(opening_steps({}), [{"launch": {}}])

    def test_every_charter_names_the_folder_the_session_pushes(self) -> None:
        charters = repo_root() / "private" / "lab" / "explorer" / "charters"
        if not charters.is_dir():
            self.skipTest("the private overlay (private/lab/explorer/charters) is not checked out")
        pushed = {p: str(load_scenarios(p, repo_root())["fixture"]["remote"]).replace("/sdcard/", "", 1)
                  for p in ("phototok", "android-desktop")}
        for path in sorted(charters.glob("*.md")):
            charter = ExplorationCharter.from_markdown(path)
            with self.subTest(charter=path.stem):
                self.assertEqual(charter.folder, pushed[charter.product])


if __name__ == "__main__":
    unittest.main()
