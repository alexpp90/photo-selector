"""Unit tests for C-00 path safety guards."""

from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.paths import ensure_android_lab_path, ensure_under, repo_root  # noqa: E402


class TestPaths(unittest.TestCase):
    def test_repo_root_finds_agents_md(self) -> None:
        r = repo_root()
        self.assertTrue((r / "AGENTS.md").is_file())

    def test_ensure_under_allows_valid_lab_paths(self) -> None:
        p = ensure_under("/tmp/pstlab/burst-sharpness/DSC_0001.JPG", "/tmp/pstlab")
        self.assertTrue(str(p).startswith("/tmp/pstlab") or str(p).startswith("/private/tmp/pstlab"))

    def test_ensure_under_rejects_outside_paths(self) -> None:
        for bad in ("/Users/someone/Pictures", "/etc/passwd", "/tmp/other_dir/file.jpg", "/"):
            with self.assertRaises(ValueError):
                ensure_under(bad, "/tmp/pstlab")

    def test_ensure_under_rejects_parent_traversal(self) -> None:
        with self.assertRaises(ValueError):
            ensure_under("/tmp/pstlab/../../etc/passwd", "/tmp/pstlab")

    def test_ensure_under_rejects_symlink_escape(self) -> None:
        Path("/tmp/pstlab").mkdir(parents=True, exist_ok=True)
        base = Path(tempfile.mkdtemp(prefix="test_symlink_guard-", dir="/tmp/pstlab"))  # per process
        self.addCleanup(shutil.rmtree, base, True)
        link = base / "escape_link"
        try:
            if link.exists() or link.is_symlink():
                link.unlink()
            with tempfile.TemporaryDirectory() as outside_dir:
                link.symlink_to(outside_dir)
                with self.assertRaises(ValueError):
                    ensure_under(link / "secret.txt", "/tmp/pstlab")
        finally:
            if link.exists() or link.is_symlink():
                link.unlink()

    def test_ensure_android_lab_path_allows_sdcard_pstlab(self) -> None:
        self.assertEqual(
            ensure_android_lab_path("/sdcard/PSTLab/cull-basic"),
            "/sdcard/PSTLab/cull-basic",
        )
        self.assertEqual(ensure_android_lab_path("/sdcard/PSTLab"), "/sdcard/PSTLab")

    def test_ensure_android_lab_path_rejects_dcim_and_traversal(self) -> None:
        for bad in (
            "/sdcard/DCIM/Camera",
            "/sdcard/PSTLab/../DCIM",
            "/storage/emulated/0/DCIM",
            "",
        ):
            with self.assertRaises(ValueError):
                ensure_android_lab_path(bad)


if __name__ == "__main__":
    unittest.main()
