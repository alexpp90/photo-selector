"""`--no-build` refuses an APK that is not this tree's (LAB-27, 2026-10-19 lab report).

On 2026-10-19 a `--no-build` pass on the Pixel Tablet tested the 2026-10-18 `.lab` APK without
a word. The device side below replays what the Moto G77 printed on 2026-10-19 for `pm path` and
`sha256sum` of its installed `com.phototok.lab`.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.android.adb import AdbClient  # noqa: E402
from lab.android.build import (  # noqa: E402
    apk_path,
    build_lab_apks,
    ensure_current_lab_apk,
    read_build_stamp,
    tree_fingerprint,
)

BASE_APK = "/data/app/~~9PchcNyViAZH3nCDmbBPsA==/com.phototok.lab-PopgD7LnGu3ZW1IdVfxbhw==/base.apk"
# The APK the Moto G77 had installed (2026-10-19 19:51), i.e. some earlier build.
OLD_APK_SHA = "c3292ab6cdeea92e7f6818e030a42e8ce42cc5d41bf9827ad0013d1b7976b370"


class FakePhone:
    """`adb -s ZY22MQCBL6 …` with the Moto G77's captured `pm path` / `sha256sum` output."""

    def __init__(self, installed_sha: str | None) -> None:
        self.installed_sha = installed_sha
        self.installed: list[str] = []

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        args = cmd[3:] if cmd[1] == "-s" else cmd[1:]
        out = b""
        if args[:3] == ["shell", "pm", "path"]:
            if self.installed_sha is None:
                raise subprocess.CalledProcessError(1, cmd, b"", b"")
            out = f"package:{BASE_APK}\n".encode()
        elif args[:2] == ["shell", "sha256sum"]:
            out = f"{self.installed_sha}  {args[2]}\n".encode()
        elif args[0] == "install":
            self.installed.append(args[-1])
            self.installed_sha = hashlib.sha256(Path(args[-1]).read_bytes()).hexdigest()
            out = b"Performing Streamed Install\nSuccess\n"
        return subprocess.CompletedProcess(cmd, 0, out, b"")


class TestNoBuild(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="pstlab-test-stamp-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
        src = self.root / "products" / "android" / "phototok" / "src"
        src.mkdir(parents=True)
        (src / "Feed.kt").write_text("val revert = 1\n")
        (self.root / "docs").mkdir()
        (self.root / "docs" / "README.md").write_text("lab\n")
        (self.root / ".gitignore").write_text("build/\n")
        self.git("init", "-q")
        self.commit("app")

    def git(self, *args: str) -> None:
        subprocess.run(["git", "-c", "user.name=lab", "-c", "user.email=lab@example.invalid", *args],
                       cwd=self.root, env=self.env, check=True, capture_output=True)

    def commit(self, msg: str) -> None:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)

    def build(self) -> str:
        """`assembleLab` as the lab runs it, with Gradle replaced by writing the APK."""

        def gradle(cmd: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
            out = cwd / "phototok" / "build" / "outputs" / "apk" / "lab"
            out.mkdir(parents=True, exist_ok=True)
            code = (self.root / "products" / "android" / "phototok" / "src" / "Feed.kt").read_bytes()
            (out / "phototok-lab.apk").write_bytes(b"PK" + code)
            return subprocess.CompletedProcess(cmd, 0, "BUILD SUCCESSFUL", "")

        ok, reason = build_lab_apks(["phototok"], self.root / "gradle.log", root=self.root, run=gradle)
        self.assertTrue(ok, reason)
        return hashlib.sha256(apk_path("phototok", self.root).read_bytes()).hexdigest()

    def check(self, phone: FakePhone) -> tuple[bool, str | None]:
        return ensure_current_lab_apk(AdbClient("ZY22MQCBL6", exec_fn=phone), "phototok", root=self.root)

    def test_without_a_build_of_this_checkout_it_refuses(self) -> None:
        ok, reason = self.check(FakePhone(OLD_APK_SHA))
        self.assertFalse(ok)
        self.assertIn("run without --no-build", str(reason))

    def test_a_device_with_an_older_apk_gets_this_trees_build(self) -> None:
        # The Pixel Tablet case: the device's APK is not the one the tree builds.
        sha = self.build()
        phone = FakePhone(OLD_APK_SHA)
        self.assertEqual(self.check(phone), (True, None))
        self.assertEqual(phone.installed, [str(apk_path("phototok", self.root))])
        self.assertEqual(phone.installed_sha, sha)

    def test_a_device_with_this_trees_apk_is_used_as_it_is(self) -> None:
        phone = FakePhone(self.build())
        self.assertEqual(self.check(phone), (True, None))
        self.assertEqual(phone.installed, [])

    def test_a_device_without_the_app_gets_it(self) -> None:
        self.build()
        phone = FakePhone(None)
        self.assertEqual(self.check(phone), (True, None))
        self.assertEqual(len(phone.installed), 1)

    def test_app_changes_after_the_build_are_refused(self) -> None:
        self.build()
        (self.root / "products" / "android" / "phototok" / "src" / "Feed.kt").write_text("val revert = 2\n")
        ok, reason = self.check(FakePhone(OLD_APK_SHA))
        self.assertFalse(ok)
        self.assertIn("the tree is now", str(reason))
        self.commit("app change")  # committed or not: still not what was built
        self.assertFalse(self.check(FakePhone(OLD_APK_SHA))[0])

    def test_a_commit_outside_the_android_tree_keeps_the_build(self) -> None:
        sha = self.build()
        (self.root / "docs" / "README.md").write_text("lab report\n")
        self.commit("docs")
        self.assertEqual(self.check(FakePhone(sha)), (True, None))

    def test_a_flow_change_keeps_the_build(self) -> None:
        # The e2e flows live in products/android/<module>/tests/e2e/ but are not in the APK.
        sha = self.build()
        flow = self.root / "products" / "android" / "phototok" / "tests" / "e2e" / "_ensure_folder.yaml"
        flow.parent.mkdir(parents=True)
        flow.write_text("appId: com.phototok.lab\n")
        self.assertEqual(self.check(FakePhone(sha)), (True, None))
        self.commit("flow")
        self.assertEqual(self.check(FakePhone(sha)), (True, None))

    def test_the_stamp_names_the_tree_the_apk_was_built_from(self) -> None:
        before = tree_fingerprint(self.root)
        sha = self.build()
        self.assertEqual(read_build_stamp("phototok", self.root), {"fingerprint": before, "apk": "phototok-lab.apk", "sha256": sha})


if __name__ == "__main__":
    unittest.main()
