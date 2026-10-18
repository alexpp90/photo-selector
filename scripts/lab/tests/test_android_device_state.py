"""Android runner against device behaviour captured on the dedicated devices, 2026-10-17.

Each fake replays what a real device printed (quoted next to it), not what the lab's author
assumed: LAB-6 (the launcher resets the rotation), LAB-15 (notification shade in front),
LAB-18 (the uiautomator dump file), LAB-19 (Maestro loses a wireless device) and the in-use
guard on personal devices.
"""

from __future__ import annotations

import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lab.android.adb as adb_mod  # noqa: E402
import lab.android.configs as android_configs  # noqa: E402
import lab.android.runner as runner_mod  # noqa: E402
from lab.android.adb import AdbClient  # noqa: E402
from lab.android.runner import default_maestro_runner, run_android_journey  # noqa: E402
from PIL import Image  # noqa: E402

APP = "com.phototok.lab"
# Pixel 4a, `dumpsys window displays` (portrait captured; landscape is the same line rotated).
DISPLAYS = {
    0: b"    init=1080x2340 440dpi mMinSizeOfResizeableTaskDp=220 cur=1080x2340 app=1080x2072 rng=1080x1003-2072x2072\n",
    1: b"    init=1080x2340 440dpi mMinSizeOfResizeableTaskDp=220 cur=2340x1080 app=2208x1080 rng=1080x1003-2072x2072\n",
}
# The shade on the S25 Ultra (2026-10-17): the dumped window is SystemUI's, nothing of the app.
# (Structure only; the real dump held personal notifications and is not copied.)
SHADE_XML = (
    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"1\">"
    '<node index="0" text="" resource-id="com.android.systemui:id/notification_panel" '
    'class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" clickable="false" '
    'bounds="[0,0][2340,1080]"><node index="0" text="" resource-id="com.android.systemui:id/expandableNotificationRow" '
    'class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" clickable="true" '
    'bounds="[100,100][2240,400]" /></node></hierarchy>'
)


def app_xml(rotation: int, dialog: bool = False) -> str:
    w, h = (2340, 1080) if rotation % 2 else (1080, 2340)
    if dialog:  # J-TOK-03's delete dialog on the Pixel 4a: its own window, [100,776][980,1568]
        w, h = 880, 792
    return (
        f"<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"{rotation}\">"
        f'<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="{APP}" '
        f'content-desc="" clickable="false" bounds="[0,0][{w},{h}]"><node index="0" text="" '
        f'resource-id="viewer.photo" class="android.view.View" package="{APP}" content-desc="" '
        f'clickable="false" bounds="[0,0][{w},{h}]" /></node></hierarchy>'
    )


class Phone:
    """A Pixel 4a as the lab drives it.

    Measured: with `accelerometer_rotation 0` and `user_rotation 1` the display is landscape;
    `am force-stop` of the app brings the portrait-only Pixel launcher to the front, after
    which `settings get system user_rotation` reads 0 and `cur=1080x2340` (Home does the same).
    `uiautomator dump` printed "ERROR: could not get idle state." once while the feed animated.
    """

    def __init__(self, shade_open: int = 0, idle_errors: int = 0, app_follows_rotation: bool = True,
                 dialog: bool = False) -> None:
        self.dialog = dialog
        self.user_rotation = 0
        self.app_front = False
        self.shade_open = shade_open  # dumps that still show the shade
        self.idle_errors = idle_errors
        self.app_follows_rotation = app_follows_rotation
        self.files: dict[str, str] = {}
        self.commands: list[str] = []

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        joined = " ".join(cmd)
        self.commands.append(joined)

        def ok(out: bytes | str = b"") -> subprocess.CompletedProcess[bytes]:
            return subprocess.CompletedProcess(cmd, 0, stdout=out.encode() if isinstance(out, str) else out, stderr=b"")

        if "settings put system user_rotation" in joined:
            self.user_rotation = int(cmd[-1])
        elif "settings get system user_rotation" in joined:
            return ok(f"{self.user_rotation}\n")
        elif "am force-stop " + APP in joined or "KEYCODE_HOME" in joined:
            self.app_front = False
            self.user_rotation = 0  # the launcher's fixed portrait
        elif "am start" in joined:
            self.app_front = True
        elif "dumpsys window displays" in joined:
            return ok(DISPLAYS[self.user_rotation % 2])
        elif "wm density" in joined:
            return ok("Physical density: 440\n")
        elif "resolve-activity" in joined:
            return ok(f"priority=0\n{cmd[-1]}/.MainActivity\n")
        elif "dumpsys activity activities" in joined:
            return ok(f"  * Task{{a1 #12 type=standard A=10074:{APP} U=0 visible=true mode=fullscreen sz=1}}\n")
        elif "uiautomator dump" in joined:
            if self.idle_errors:
                self.idle_errors -= 1
                return ok("ERROR: could not get idle state.\n")
            if self.shade_open:
                self.shade_open -= 1
                self.files[cmd[-1]] = SHADE_XML
            else:
                rot = self.user_rotation if self.app_follows_rotation else 0
                self.files[cmd[-1]] = app_xml(rot, self.dialog)
            return ok(f"UI hierchary dumped to: {cmd[-1]}\n")
        elif cmd[-3:-1] == ["pull", adb_mod.REMOTE_DUMP]:
            Path(cmd[-1]).write_text(self.files[adb_mod.REMOTE_DUMP], encoding="utf-8")
        elif "rm -f" in joined:
            self.files.pop(cmd[-1], None)
        elif "exec-out screencap -p" in joined:
            buf = io.BytesIO()
            Image.new("RGB", (54, 117), (20, 40, 60)).save(buf, format="PNG")
            return ok(buf.getvalue())
        elif "find . -type f" in joined:
            return ok("48074 ./DSC_0001.JPG\n")
        return ok("0\n")


JOURNEY = {
    "id": "J-TOK-02",
    "product": "phototok",
    "fixture": "smoke",
    "configs": ["phone-landscape"],
    "fresh_install": True,
    "steps": [{"checkpoint": "fifth"}, {"background": 0}, {"checkpoint": "returned"}],
    "layout_expect": {"fifth": {"required": ["viewer.photo"]}},
}


class TestAndroidDeviceState(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-android-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for mod, name in ((android_configs, "ROTATION_POLL_S"), (runner_mod, "SHADE_SETTLE_S"), (runner_mod, "ROTATION_SETTLE_S"), (adb_mod, "DUMP_RETRY_S")):
            self.addCleanup(setattr, mod, name, getattr(mod, name))
            setattr(mod, name, 0.0)

    def _run(self, phone: Phone, config: str = "phone-landscape") -> tuple[str, Path]:
        status, _ = run_android_journey(
            manifest=JOURNEY, config_name=config, run_dir=self.tmp, adb=AdbClient("TEST", exec_fn=phone),
            maestro_fn=lambda serial, flow, junit: 0, staging_root=self.tmp / "stage",
        )
        return status, self.tmp / "phototok" / config / "J-TOK-02"

    def test_landscape_survives_the_launcher_resetting_the_rotation(self) -> None:
        # LAB-6: the rotation set before launch was undone by force-stop; every
        # phone-landscape journey ran in portrait and was reported as a pass.
        phone = Phone()
        status, j_dir = self._run(phone)
        self.assertEqual(status, "pass")
        for cp in ("01_fifth", "02_returned"):
            self.assertIn('rotation="1"', (j_dir / f"{cp}.xml").read_text(encoding="utf-8"))

    def test_an_app_that_stays_portrait_is_an_error_not_a_pass(self) -> None:
        status, j_dir = self._run(Phone(app_follows_rotation=False))
        self.assertEqual(status, "error")
        reason = (j_dir / "result.json").read_text(encoding="utf-8")
        self.assertIn("not applied", reason)
        self.assertFalse((j_dir / "01_fifth.png").exists())

    def test_a_dialog_window_is_not_mistaken_for_the_wrong_orientation(self) -> None:
        status, j_dir = self._run(Phone(dialog=True), "phone-portrait")
        self.assertEqual(status, "pass")
        self.assertTrue((j_dir / "01_fifth.png").is_file())

    def test_the_shade_is_collapsed_before_every_checkpoint(self) -> None:
        phone = Phone(shade_open=1)  # gone after the second collapse
        status, j_dir = self._run(phone, "phone-portrait")
        self.assertEqual(status, "pass")
        collapses = [c for c in phone.commands if c.endswith("cmd statusbar collapse")]
        self.assertGreaterEqual(len(collapses), 3)  # before each checkpoint, plus the retry

    def test_a_shade_that_stays_open_is_never_photographed(self) -> None:
        # LAB-15: 53 SystemUI "findings" and screenshots of personal notifications.
        status, j_dir = self._run(Phone(shade_open=99), "phone-portrait")
        self.assertEqual(status, "error")
        self.assertIn("com.android.systemui", (j_dir / "result.json").read_text(encoding="utf-8"))
        self.assertEqual(list(j_dir.glob("*.png")), [])
        self.assertEqual(list(j_dir.glob("*.xml")), [])

    def test_do_not_disturb_is_on_during_the_journey_and_restored(self) -> None:
        phone = Phone()
        self._run(phone, "phone-portrait")
        dnd = [c.rsplit(" ", 1)[1] for c in phone.commands if "set_dnd" in c]
        self.assertEqual(dnd, ["on", "off"])  # the fake's zen_mode reads 0: DND was off

    def test_the_dump_lives_in_the_lab_root_and_is_removed(self) -> None:
        # LAB-18: /sdcard/pst_dump.xml was left in the storage root of personal devices.
        phone = Phone(idle_errors=1)
        status, _ = self._run(phone, "phone-portrait")
        self.assertEqual(status, "pass")
        dumps = [c for c in phone.commands if "uiautomator dump" in c]
        self.assertTrue(all(c.endswith(" /sdcard/PSTLab/pst_dump.xml") for c in dumps))
        self.assertFalse(any("/sdcard/pst_" in c for c in phone.commands))
        self.assertEqual(phone.files, {})  # every dump removed after its pull


class TestMaestroRetry(unittest.TestCase):
    def test_a_lost_wireless_device_is_reconnected_and_retried(self) -> None:
        # LAB-19: the Tab S11 Ultra over TLS adb was still "not connected" on an immediate retry.
        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-maestro-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        junit = tmp / "maestro_01.xml"
        serial = "adb-R52Y90AF4LE-fAYuME._adb-tls-connect._tcp"
        attempts: list[int] = []
        waited: list[str | None] = []

        def once(s: str | None, flow: Path, out: Path) -> int:
            attempts.append(1)
            if len(attempts) < 3:
                out.with_suffix(".log").write_text(f"Device {s} was requested, but it is not connected.\n", encoding="utf-8")
                return 1
            out.with_suffix(".log").write_text("[Passed] 01_launch\n", encoding="utf-8")
            return 0

        rc = default_maestro_runner(serial, Path("flow.yaml"), junit, run_once=once,
                                    wait_for_device=waited.append, sleep=lambda s: None)
        self.assertEqual((rc, len(attempts), waited), (0, 3, [serial, serial]))

    def test_a_broken_flow_is_not_retried(self) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-maestro-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        attempts: list[int] = []

        def once(s: str | None, flow: Path, out: Path) -> int:
            attempts.append(1)
            out.with_suffix(".log").write_text("[Failed] 02_open_info (Element not found: Id matching regex: x)\n", encoding="utf-8")
            return 1

        rc = default_maestro_runner("s", Path("f.yaml"), tmp / "m.xml", run_once=once,
                                    wait_for_device=lambda s: None, sleep=lambda s: None)
        self.assertEqual((rc, len(attempts)), (1, 1))


class InUseDevice:
    """`dumpsys` output of a device in a given state, in the Pixel 4a's captured format."""

    HOME = b"priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.google.android.apps.nexuslauncher/.NexusLauncherActivity\n"

    def __init__(self, wakefulness: str, keyguard: bool, top: str) -> None:
        self.out = {
            "dumpsys power": f"  mWakefulness={wakefulness}\n".encode(),
            "dumpsys window": f"    isKeyguardShowing={'true' if keyguard else 'false'}\n    mDreamingLockscreen={'true' if keyguard else 'false'}\n".encode(),
            "dumpsys activity activities": f"    topResumedActivity=ActivityRecord{{db83cbe u0 {top} t1746}}\n".encode(),
        }

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        joined = " ".join(cmd)
        if "resolve-activity" in joined:
            return subprocess.CompletedProcess(cmd, 0, stdout=self.HOME, stderr=b"")
        for key, val in self.out.items():
            if joined.endswith(key):
                return subprocess.CompletedProcess(cmd, 0, stdout=val, stderr=b"")
        return subprocess.CompletedProcess(cmd, 0, stdout=b"", stderr=b"")


class TestInUseBy(unittest.TestCase):
    def _in_use(self, wakefulness: str, keyguard: bool, top: str) -> str | None:
        return AdbClient("S", exec_fn=InUseDevice(wakefulness, keyguard, top)).in_use_by()

    def test_an_app_on_an_awake_unlocked_screen_means_in_use(self) -> None:
        # 2026-10-17: the guard stopped the S25 Ultra with Garmin Connect on screen.
        top = "com.garmin.android.apps.connectmobile/.activities.MainActivity"
        self.assertEqual(self._in_use("Awake", False, top), "com.garmin.android.apps.connectmobile")

    def test_idle_states_are_not_in_use(self) -> None:
        app = "com.garmin.android.apps.connectmobile/.activities.MainActivity"
        self.assertIsNone(self._in_use("Dozing", True, app))  # captured on the Pixel 4a
        self.assertIsNone(self._in_use("Awake", True, app))  # lock screen showing
        self.assertIsNone(self._in_use("Awake", False, "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"))
        self.assertIsNone(self._in_use("Awake", False, "com.phototok.lab/com.phototok.MainActivity"))


if __name__ == "__main__":
    unittest.main()
