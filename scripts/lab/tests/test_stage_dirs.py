"""J-TOK-06 needs /sdcard/DCIM/Camera; the lab creates it for the journey and removes it again."""

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
from lab.android.adb import AdbClient  # noqa: E402
from lab.android.runner import STAGED_PHOTO_NAME, run_android_journey  # noqa: E402
from lab.paths import ensure_android_stage_path, repo_root  # noqa: E402
from lab.schema import validate  # noqa: E402
from test_android_device_state import Phone  # noqa: E402
import yaml  # noqa: E402  (after `lab`, which supplies a stand-in where PyYAML is missing)

CAMERA = "/sdcard/DCIM/Camera"
J_TOK_06 = yaml.safe_load((repo_root() / "scripts" / "lab" / "journeys" / "J-TOK-06.yaml").read_text(encoding="utf-8"))


class StorageTracker(Phone):
    """The shared Pixel 4a fake plus a tiny shared-storage tree: `test -d`, mkdir, push, rm, rmdir."""

    def __init__(self, dirs: set[str], files: set[str] | None = None) -> None:
        super().__init__()
        self.dirs, self.stored = set(dirs), set(files or ())

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        tail = cmd[cmd.index("shell") + 1:] if "shell" in cmd else cmd
        joined = " ".join(tail)

        def ok(out: str = "") -> subprocess.CompletedProcess[bytes]:
            return subprocess.CompletedProcess(cmd, 0, stdout=out.encode(), stderr=b"")

        if joined.startswith("test -d "):
            path = joined.split("'")[1]
            self.commands.append(joined)
            return ok("yes\n" if path in self.dirs else "no\n")
        if tail[:2] == ["mkdir", "-p"] and not tail[2].startswith("/sdcard/PSTLab"):
            self.commands.append(joined)
            parts = tail[2].split("/")
            self.dirs.update("/".join(parts[:i]) for i in range(2, len(parts) + 1))
            return ok()
        if cmd[-3:-2] == ["push"] and not cmd[-1].startswith("/sdcard/PSTLab"):
            self.commands.append(" ".join(cmd[-3:]))
            if cmd[-1].rsplit("/", 1)[0] not in self.dirs:
                return subprocess.CompletedProcess(cmd, 1, stdout=b"", stderr=b"no such directory")
            self.stored.add(cmd[-1])
            return ok()
        if tail[:2] == ["rm", "-f"] and tail[2].startswith("/sdcard/DCIM"):
            self.commands.append(joined)
            self.stored.discard(tail[2])
            return ok()
        if tail[:1] == ["rmdir"]:
            self.commands.append(joined)
            d = tail[1]
            if any(f.startswith(d + "/") for f in self.stored) or any(x.startswith(d + "/") for x in self.dirs):
                return subprocess.CompletedProcess(cmd, 1, stdout=b"", stderr=b"Directory not empty")
            self.dirs.discard(d)
            return ok()
        return super().__call__(cmd, timeout)


class TestStageDirs(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-stage-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for mod, name in ((android_configs, "ROTATION_POLL_S"), (runner_mod, "SHADE_SETTLE_S"),
                          (runner_mod, "ROTATION_SETTLE_S"), (adb_mod, "DUMP_RETRY_S")):
            self.addCleanup(setattr, mod, name, getattr(mod, name))
            setattr(mod, name, 0.0)

    def _run(self, phone: StorageTracker, seen: list[set[str]], boom: bool = False) -> str:
        def maestro(serial: str | None, flow: Path, junit: Path) -> int:
            seen.append(set(phone.stored))
            if boom:
                raise RuntimeError("maestro died")
            return 0

        try:
            status, _ = run_android_journey(
                manifest=J_TOK_06, config_name="phone-portrait", run_dir=self.tmp,
                adb=AdbClient("TEST", exec_fn=phone), maestro_fn=maestro, staging_root=self.tmp / "stage",
            )
        except RuntimeError:
            return "raised"
        return status

    def test_the_manifest_stages_the_camera_roll_instead_of_requiring_it(self) -> None:
        self.assertEqual(validate(J_TOK_06, "journey"), [])
        self.assertEqual(J_TOK_06["stage_dirs"], [CAMERA])
        self.assertNotIn("requires_dirs", J_TOK_06)

    def test_a_device_without_the_camera_roll_gets_it_for_the_journey_only(self) -> None:
        phone = StorageTracker({"/sdcard"})  # Xperia XA2, Moto G77: no DCIM at all
        seen: list[set[str]] = []
        self.assertIn(self._run(phone, seen), ("pass", "fail"))  # the fake screen is not the real app: only "ran" matters
        self.assertEqual(seen, [{f"{CAMERA}/{STAGED_PHOTO_NAME}"}])  # the flow ran with a photo in it
        self.assertEqual(phone.dirs, {"/sdcard"})  # Camera and the DCIM it needed are gone again
        self.assertEqual(phone.stored, set())
        self.assertFalse(any("rm -r" in c for c in phone.commands if "DCIM" in c))

    def test_a_device_with_a_camera_roll_is_left_alone(self) -> None:
        own = f"{CAMERA}/IMG_0001.jpg"
        phone = StorageTracker({"/sdcard", "/sdcard/DCIM", CAMERA}, {own})
        seen: list[set[str]] = []
        self.assertIn(self._run(phone, seen), ("pass", "fail"))  # the fake screen is not the real app: only "ran" matters
        self.assertEqual(phone.stored, {own})
        self.assertEqual(phone.dirs, {"/sdcard", "/sdcard/DCIM", CAMERA})
        self.assertFalse(any(c.startswith(("mkdir", "rmdir", "rm -f /sdcard/DCIM")) for c in phone.commands))

    def test_an_existing_dcim_without_camera_stays_when_the_journey_ends(self) -> None:
        phone = StorageTracker({"/sdcard", "/sdcard/DCIM"})
        self.assertIn(self._run(phone, []), ("pass", "fail"))
        self.assertEqual(phone.dirs, {"/sdcard", "/sdcard/DCIM"})

    def test_a_journey_that_blows_up_still_cleans_up(self) -> None:
        phone = StorageTracker({"/sdcard"})
        self.assertEqual(self._run(phone, [], boom=True), "raised")
        self.assertEqual((phone.dirs, phone.stored), ({"/sdcard"}, set()))

    def test_something_else_in_the_staged_directory_is_never_deleted(self) -> None:
        phone = StorageTracker({"/sdcard"})
        orig = phone.__call__

        def maestro(serial: str | None, flow: Path, junit: Path) -> int:
            phone.stored.add(f"{CAMERA}/by_the_camera.jpg")  # the camera app saved a photo meanwhile
            return 0

        run_android_journey(
            manifest=J_TOK_06, config_name="phone-portrait", run_dir=self.tmp,
            adb=AdbClient("TEST", exec_fn=orig), maestro_fn=maestro, staging_root=self.tmp / "stage",
        )
        self.assertIn(f"{CAMERA}/by_the_camera.jpg", phone.stored)

    def test_only_the_camera_roll_can_be_staged(self) -> None:
        self.assertEqual(ensure_android_stage_path(CAMERA + "/"), CAMERA)
        for bad in ("/sdcard/DCIM", "/sdcard/DCIM/Camera/x", "/sdcard/DCIM/Camera/../..", "/sdcard/Download", "/sdcard/PSTLab"):
            with self.assertRaises(ValueError, msg=bad):
                ensure_android_stage_path(bad)
            with self.assertRaises(ValueError, msg=bad):
                AdbClient("T", exec_fn=StorageTracker({"/sdcard"})).stage_dir(bad, self.tmp / "x.jpg", "x.jpg")


if __name__ == "__main__":
    unittest.main()
