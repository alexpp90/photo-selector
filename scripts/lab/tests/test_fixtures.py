"""Unit tests for C-02 deterministic fixture generator."""

from __future__ import annotations

import shutil
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.fixtures import _resolve_corpus_samples, generate_fixtures  # noqa: E402
from PIL import Image, ImageFilter, ImageStat  # noqa: E402


class TestFixtures(unittest.TestCase):
    def setUp(self) -> None:
        self.dir_a = Path("/tmp/pstlab/test_fixtures_a")
        self.dir_b = Path("/tmp/pstlab/test_fixtures_b")
        for d in (self.dir_a, self.dir_b):
            if d.exists():
                shutil.rmtree(d)

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

    def test_corpus_resolves_committed_bitmaps_next_to_manifest(self) -> None:
        # Committed ("repo") samples sit beside the manifest, downloaded ones in the cache.
        root = self.dir_a / "root"
        cache = self.dir_a / "cache"
        (root / "scripts").mkdir(parents=True)
        (root / "testdata" / "formats" / "bitmaps").mkdir(parents=True)
        cache.mkdir()
        (root / "scripts" / "fetch_format_corpus.py").write_text(
            f"import sys\nif '--print-dir' in sys.argv: print({str(cache)!r})\n", encoding="utf-8"
        )
        (root / "testdata" / "formats" / "manifest.json").write_text(
            '{"samples": ['
            '{"format": "heic", "file": "bitmaps/sample.heic", "source": "repo"},'
            '{"format": "dng", "file": "x.dng", "source": "corpus"}]}',
            encoding="utf-8",
        )
        (root / "testdata" / "formats" / "bitmaps" / "sample.heic").write_bytes(b"heic")
        (cache / "x.dng").write_bytes(b"dng")

        found = _resolve_corpus_samples(["heic", "dng"], root=root)

        self.assertEqual(found["heic"], root / "testdata" / "formats" / "bitmaps" / "sample.heic")
        self.assertEqual(found["dng"], cache / "x.dng")


if __name__ == "__main__":
    unittest.main()
