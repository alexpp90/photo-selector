"""The lab skips `_ensure_folder.yaml` only when the launched app already shows what it ends on.

The XML in tests/data/start_screens/ are real uiautomator dumps from the 2026-11-09 full lab run
(Pixel 4a, Pixel Tablet). The two dialog screens are synthetic: no dump of a dialog window that
the ensure flow answers was captured (the lab dumps checkpoints, not the launch), so they are
built from the documented shape, a dialog window being the only window and exporting no test tags.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import lab.android.adb as adb_mod  # noqa: E402
import lab.android.configs as android_configs  # noqa: E402
import lab.android.runner as runner_mod  # noqa: E402
import lab.android.start_state as ss  # noqa: E402
from lab.android.adb import AdbClient  # noqa: E402
from lab.android.runner import run_android_journey  # noqa: E402
from test_android_device_state import Phone  # noqa: E402

DATA = Path(__file__).resolve().parent / "data" / "start_screens"
TOK, AD = "com.phototok.lab", "com.photoselectortoolbox.lab"


def shot(name: str) -> str:
    return (DATA / name).read_text(encoding="utf-8")


def dialog(app_id: str, text: str) -> str:
    return (
        '<hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" '
        f'package="{app_id}" bounds="[84,900][996,1500]"><node text="{text}" resource-id="" '
        f'class="android.widget.TextView" package="{app_id}" bounds="[100,920][900,1000]"/></node></hierarchy>'
    )


class Replay:
    """A `dump` that returns the given screens in order (the last one repeats) on a fake clock."""

    def __init__(self, screens: list[str], step_s: float = 1.0) -> None:
        self.screens, self.now, self.step_s, self.calls = screens, 0.0, step_s, 0

    def dump(self) -> str:
        screen = self.screens[min(self.calls, len(self.screens) - 1)]
        self.calls += 1
        self.now += self.step_s  # a dump takes time
        return screen

    def sleep(self, s: float) -> None:
        self.now += s

    def clock(self) -> float:
        return self.now

    def ready(self, product: str, app_id: str) -> bool:
        return ss.app_already_ready(product, app_id, self.dump, sleep=self.sleep, clock=self.clock)


class TestClassify(unittest.TestCase):
    def test_real_screens_the_flow_ends_on_are_ready(self) -> None:
        self.assertEqual(ss.classify_start_screen(shot("tok_feed_ready.xml"), "phototok", TOK), ss.READY)
        self.assertEqual(ss.classify_start_screen(shot("ad_workspace_ready.xml"), "android-desktop", AD), ss.READY)

    def test_real_screens_with_work_for_the_flow_are_not_ready(self) -> None:
        self.assertEqual(ss.classify_start_screen(shot("tok_landing.xml"), "phototok", TOK), ss.NEEDS_FLOW)
        self.assertEqual(ss.classify_start_screen(shot("tok_controls_guide.xml"), "phototok", TOK), ss.NEEDS_FLOW)
        self.assertEqual(ss.classify_start_screen(shot("ad_coach_overlay.xml"), "android-desktop", AD), ss.NEEDS_FLOW)

    def test_a_dialog_window_alone_is_never_ready(self) -> None:
        for app, product, text in (
            (TOK, "phototok", "Show new"),
            (TOK, "phototok", "Link both"),
            (AD, "android-desktop", "Uncompleted deletions"),
        ):
            self.assertEqual(ss.classify_start_screen(dialog(app, text), product, app), ss.NEEDS_FLOW, text)
        self.assertEqual(ss.classify_start_screen(dialog(TOK, "Anything else"), "phototok", TOK), ss.PENDING)

    def test_a_dialog_text_over_the_ready_screen_blocks_the_skip(self) -> None:
        both = shot("tok_feed_ready.xml").replace("</hierarchy>", dialog(TOK, "Show new").split(">", 1)[1])
        self.assertEqual(ss.classify_start_screen(both, "phototok", TOK), ss.NEEDS_FLOW)

    def test_another_apps_window_or_the_wrong_product_is_not_ready(self) -> None:
        self.assertEqual(ss.classify_start_screen(shot("tok_feed_ready.xml"), "phototok", AD), ss.PENDING)
        self.assertEqual(ss.classify_start_screen(shot("tok_feed_ready.xml"), "android-desktop", TOK), ss.PENDING)


class TestPoll(unittest.TestCase):
    def test_a_screen_that_stays_ready_skips_the_flow(self) -> None:
        r = Replay([shot("tok_feed_ready.xml")])
        self.assertTrue(r.ready("phototok", TOK))
        self.assertGreaterEqual(r.now, ss.STABLE_FOR_S)  # it waited out the flow's own dialog window

    def test_a_dialog_that_arrives_after_the_feed_runs_the_flow(self) -> None:
        r = Replay([shot("tok_feed_ready.xml"), shot("tok_feed_ready.xml"), dialog(TOK, "Show new")])
        self.assertFalse(r.ready("phototok", TOK))

    def test_a_ready_screen_that_goes_away_runs_the_flow(self) -> None:
        r = Replay([shot("ad_workspace_ready.xml"), dialog(AD, "Something unknown")])
        self.assertFalse(r.ready("android-desktop", AD))

    def test_the_landing_runs_the_flow_at_once(self) -> None:
        r = Replay([shot("tok_landing.xml")])
        self.assertFalse(r.ready("phototok", TOK))
        self.assertEqual(r.calls, 1)

    def test_a_screen_that_never_settles_times_out_into_the_flow(self) -> None:
        r = Replay([dialog(TOK, "Loading")])
        self.assertFalse(r.ready("phototok", TOK))
        self.assertGreaterEqual(r.now, ss.PROBE_TIMEOUT_S)

    def test_a_failing_dump_runs_the_flow(self) -> None:
        def boom() -> str:
            raise RuntimeError("uiautomator dump failed")

        self.assertFalse(ss.app_already_ready("phototok", TOK, boom, sleep=lambda s: None))


class RecordingPhone(Phone):
    """The shared Pixel 4a fake, serving one real screen for every UI dump."""

    def __init__(self, screen: str) -> None:
        super().__init__()
        self.screen = screen

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        out = super().__call__(cmd, timeout)
        if "uiautomator dump" in " ".join(cmd) and "ERROR" not in out.stdout.decode():
            self.files[cmd[-1]] = self.screen
        return out


JOURNEY = {
    "id": "J-TOK-02", "product": "phototok", "fixture": "smoke", "configs": ["phone-portrait"],
    "steps": [{"checkpoint": "fifth"}],
}


class TestRunnerWiring(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-start-state-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for mod, name, val in (
            (android_configs, "ROTATION_POLL_S", 0.0), (runner_mod, "SHADE_SETTLE_S", 0.0),
            (runner_mod, "ROTATION_SETTLE_S", 0.0), (adb_mod, "DUMP_RETRY_S", 0.0),
            (ss, "STABLE_FOR_S", 0.0), (ss, "PROBE_TIMEOUT_S", 0.0), (ss, "PROBE_INTERVAL_S", 0.0),
        ):
            self.addCleanup(setattr, mod, name, getattr(mod, name))
            setattr(mod, name, val)

    def _run(self, screen: str) -> tuple[list[str], Path]:
        flows: list[str] = []

        def maestro(serial: str | None, flow: Path, junit: Path) -> int:
            flows.append(flow.name)
            return 0

        run_android_journey(
            manifest=JOURNEY, config_name="phone-portrait", run_dir=self.tmp,
            adb=AdbClient("TEST", exec_fn=RecordingPhone(screen)), maestro_fn=maestro,
            staging_root=self.tmp / "stage",
        )
        return flows, self.tmp / "phototok" / "phone-portrait" / "J-TOK-02"

    def test_a_ready_app_does_not_pay_for_the_ensure_flow(self) -> None:
        flows, j_dir = self._run(shot("tok_feed_ready.xml"))
        self.assertEqual(flows, [])
        self.assertIn("ensure_folder", (j_dir / "result.json").read_text(encoding="utf-8"))

    def test_an_app_on_the_landing_still_runs_it(self) -> None:
        flows, j_dir = self._run(shot("tok_landing.xml"))
        self.assertEqual(flows, ["_ensure_folder.yaml"])
        self.assertNotIn("ensure_folder", (j_dir / "result.json").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
