"""Unit tests for C-02 deterministic fixture generator."""

from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.fixtures import generate_fixtures  # noqa: E402
from PIL import Image, ImageFilter, ImageStat  # noqa: E402


class TestFixtures(unittest.TestCase):
    def setUp(self) -> None:
        # A directory of this process's own: fixed /tmp/pstlab/<name> paths raced with the lab
        # tests of other sessions' worktrees, whose setUp removed this run's files (2026-10-18).
        tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-fixtures-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        self.dir_a = tmp / "a"
        self.dir_b = tmp / "b"

    def tearDown(self) -> None:
        for d in (self.dir_a, self.dir_b):
            if d.exists():
                shutil.rmtree(d)

    def test_deterministic_generation_and_sharpness_order(self) -> None:
        generate_fixtures("burst-jpeg", self.dir_a / "burst-jpeg")
        generate_fixtures("burst-jpeg", self.dir_b / "burst-jpeg")

        files_a = sorted((self.dir_a / "burst-jpeg").glob("*.JPG"))
        self.assertEqual(len(files_a), 15)
        for fa in files_a:
            fb = self.dir_b / "burst-jpeg" / fa.name
            self.assertEqual(fa.read_bytes(), fb.read_bytes(), f"{fa.name} is not byte-identical across runs")

        def edge_energy(p: Path) -> float:
            with Image.open(p) as im:
                edges = im.convert("L").filter(ImageFilter.FIND_EDGES)
                return float(ImageStat.Stat(edges).var[0])

        # In burst-jpeg, burst 1 has sharpest=6 (DSC_0006.JPG) and DSC_0001.JPG is blurred
        e_sharp = edge_energy(self.dir_a / "burst-jpeg" / "DSC_0006.JPG")
        e_blur = edge_energy(self.dir_a / "burst-jpeg" / "DSC_0001.JPG")
        self.assertGreater(e_sharp, e_blur * 1.5)

        with Image.open(self.dir_a / "burst-jpeg" / "DSC_0006.JPG") as im:
            exif = im.getexif()
            self.assertEqual(exif.get(274), 1)

    def test_decoys_and_corrupt(self) -> None:
        generate_fixtures("decoys", self.dir_a / "decoys")
        self.assertTrue(((self.dir_a / "decoys") / "Selection" / "DSC_9001.JPG").is_file())
        corrupt = self.dir_a / "decoys" / "DSC_0999.JPG"
        self.assertTrue(corrupt.is_file())
        self.assertEqual(corrupt.read_bytes()[:2], b"\xff\xd8")


if __name__ == "__main__":
    unittest.main()
