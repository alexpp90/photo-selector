"""products/macos-desktop/scripts/build_app.sh with stub `swift` and `codesign` tools: a failed
signature fails the build (it used to be `|| true`), and the bundle's Info.plist is well formed and
carries the folder-access usage strings."""

from __future__ import annotations

import os
import plistlib
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "products" / "macos-desktop" / "scripts" / "build_app.sh"

SWIFT_STUB = """#!/bin/sh
for a in "$@"; do
  if [ "$a" = "--show-bin-path" ]; then echo "$STUB_BIN"; exit 0; fi
done
exit 0
"""
CODESIGN_STUB = """#!/bin/sh
echo "codesign: $*" >> "$STUB_LOG"
if [ "$STUB_CODESIGN_FAILS" = "1" ]; then echo "stub: signing refused" >&2; exit 1; fi
exit 0
"""


def _executable(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


@unittest.skipUnless(shutil.which("bash") and shutil.which("sh"), "needs a POSIX shell")
class BuildAppTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)
        self.product = root / "products" / "macos-desktop"
        (self.product / "scripts").mkdir(parents=True)
        (self.product / "Resources").mkdir()
        shutil.copy(SCRIPT, self.product / "scripts" / "build_app.sh")
        (self.product / "VERSION").write_text("1.2.3\n", encoding="utf-8")
        (self.product / "Resources" / "AppIcon.icns").write_bytes(b"icns")
        self.stubs = root / "stubs"
        self.stubs.mkdir()
        _executable(self.stubs / "swift", SWIFT_STUB)
        _executable(self.stubs / "codesign", CODESIGN_STUB)
        self.bin = root / "bin"
        self.bin.mkdir()
        (self.bin / "PhotoSelectorApp").write_bytes(b"binary")
        self.log = root / "codesign.log"

    def _run(self, codesign_fails: bool) -> subprocess.CompletedProcess:
        env = dict(os.environ, PATH=f"{self.stubs}{os.pathsep}{os.environ['PATH']}",
                   STUB_BIN=str(self.bin), STUB_LOG=str(self.log),
                   STUB_CODESIGN_FAILS="1" if codesign_fails else "0")
        return subprocess.run(["bash", str(self.product / "scripts" / "build_app.sh"), "release"],
                              env=env, capture_output=True, text=True, timeout=60)

    def test_a_failed_signature_fails_the_build(self):
        result = self._run(codesign_fails=True)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("signing", result.stderr)

    def test_a_signed_bundle_prints_its_path_last_and_signs_it(self):
        result = self._run(codesign_fails=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        app = Path(result.stdout.strip().splitlines()[-1])
        self.assertEqual(app.name, "Lensjet.app")
        self.assertTrue((app / "Contents" / "MacOS" / "PhotoSelectorApp").is_file())
        self.assertIn(f"--sign - {app}", self.log.read_text(encoding="utf-8"))

    def test_info_plist_is_well_formed_and_explains_folder_access(self):
        result = self._run(codesign_fails=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        app = Path(result.stdout.strip().splitlines()[-1])
        with open(app / "Contents" / "Info.plist", "rb") as handle:
            info = plistlib.load(handle)
        self.assertEqual(info["CFBundleShortVersionString"], "1.2.3")
        self.assertEqual(info["CFBundleIdentifier"], "app.alpatix.lensjet")
        for key in ("NSDesktopFolderUsageDescription", "NSDocumentsFolderUsageDescription",
                    "NSDownloadsFolderUsageDescription", "NSRemovableVolumesUsageDescription",
                    "NSNetworkVolumesUsageDescription"):
            self.assertTrue(info.get(key, "").strip(), key)
            self.assertIn("Lensjet", info[key])


if __name__ == "__main__":
    unittest.main()
