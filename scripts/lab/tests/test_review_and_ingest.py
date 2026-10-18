"""Unit tests for C-20, C-21, C-22 review bundle builder, extraction, ingestion, and triage."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.ingest import extract_findings_from_raw, ingest_review_bundles, triage_finding  # noqa: E402
from lab.review_bundle import build_review_bundles, compute_image_change_fraction  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402


class TestReviewAndIngest(unittest.TestCase):
    def setUp(self) -> None:
        self.base = Path(tempfile.mkdtemp(prefix="pstlab-test-review-"))  # per process, see test_fixtures

    def tearDown(self) -> None:
        if self.base.exists():
            shutil.rmtree(self.base)

    def test_change_filter_and_bundle_builder_and_ingest(self) -> None:
        prev_run = self.base / "2026-10-16-0900-1111111"
        curr_run = self.base / "2026-10-17-0900-2222222"
        priv_lab = self.base / "private_lab"
        (priv_lab / "expectations").mkdir(parents=True, exist_ok=True)
        (priv_lab / "review").mkdir(parents=True, exist_ok=True)
        (priv_lab / "review" / "known_findings.yaml").write_text("[]\n", encoding="utf-8")
        (priv_lab / "review" / "instructions.md").write_text("Review instructions\n", encoding="utf-8")
        (priv_lab / "expectations" / "J-AD-02.md").write_text(
            "---\nref: test-ref\n---\n## three-up\nThree equal frames.\n",
            encoding="utf-8",
        )

        tree_payload = {
            "platform": "android",
            "window": {"w": 1280, "h": 800, "scale": 2.0, "unit": "dp"},
            "nodes": [],
        }

        for rdir in (prev_run, curr_run):
            rdir.mkdir(parents=True, exist_ok=True)
            (rdir / "run.json").write_text(
                json.dumps(
                    {
                        "started": "2026-10-17T09:00:00Z",
                        "finished": "2026-10-17T09:05:00Z",
                        "commit": rdir.name.split("-")[-1],
                        "products": {"android-desktop": {"status": "ran"}},
                        "tools": {},
                        "journeys": [],
                    }
                ),
                encoding="utf-8",
            )
            jdir = rdir / "android-desktop" / "tab-landscape" / "J-AD-02"
            jdir.mkdir(parents=True, exist_ok=True)
            (jdir / "result.json").write_text(
                json.dumps(
                    {
                        "id": "J-AD-02",
                        "product": "android-desktop",
                        "config": "tab-landscape",
                        "status": "pass",
                        "duration_s": 2.0,
                        "checkpoints": ["three-up", "after-keep"],
                    }
                ),
                encoding="utf-8",
            )
            img1 = Image.new("RGB", (640, 400), (50, 50, 50))
            img1.save(jdir / "01_three-up.png", format="PNG")
            (jdir / "01_three-up.tree.json").write_text(json.dumps(tree_payload), encoding="utf-8")
            img2 = Image.new("RGB", (640, 400), (50, 50, 50))
            if rdir == curr_run:
                draw = ImageDraw.Draw(img2)
                draw.rectangle([100, 100, 300, 250], fill=(240, 20, 20))
            img2.save(jdir / "02_after-keep.png", format="PNG")
            (jdir / "02_after-keep.tree.json").write_text(json.dumps(tree_payload), encoding="utf-8")

        self.assertEqual(
            compute_image_change_fraction(
                curr_run / "android-desktop/tab-landscape/J-AD-02/01_three-up.png",
                prev_run / "android-desktop/tab-landscape/J-AD-02/01_three-up.png",
            ),
            0.0,
        )
        bundles = build_review_bundles(curr_run, include_all=False, prev_run_dir=prev_run, private_lab_dir=priv_lab)
        self.assertEqual(len(bundles), 1)
        manifest = json.loads((bundles[0] / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(len(manifest), 1)
        self.assertEqual(manifest[0]["checkpoint"], "after-keep")

        raw_txt = bundles[0] / "findings.raw.txt"
        raw_txt.write_text(
            "Here is the JSON array:\n```json\n"
            + json.dumps(
                [
                    {
                        "source": "ai-review",
                        "severity": "major",
                        "confidence": "high",
                        "rule": "ai:occlusion",
                        "message": "Red bar covers image",
                        "evidence": [manifest[0]["image"]],
                        "region": [100, 100, 200, 150],
                    }
                ]
            )
            + "\n```\n",
            encoding="utf-8",
        )
        extracted = extract_findings_from_raw(raw_txt, bundles[0] / "findings.json")
        self.assertEqual(len(extracted), 1)

        ai_findings = ingest_review_bundles(curr_run, private_lab_dir=priv_lab)
        self.assertEqual(len(ai_findings), 1)
        self.assertEqual(ai_findings[0].region, [200.0, 200.0, 400.0, 300.0])
        self.assertTrue(ai_findings[0].new_since_last_run)

        fid = ai_findings[0].id
        triage_finding(curr_run, fid, reason="accepted test finding", until="2027-01-01", private_lab_dir=priv_lab)
        ai_after_triage = ingest_review_bundles(curr_run, private_lab_dir=priv_lab)
        self.assertEqual(ai_after_triage, [])


if __name__ == "__main__":
    unittest.main()
