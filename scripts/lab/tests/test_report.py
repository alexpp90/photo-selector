"""Unit tests for C-06 HTML report generator."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.report import generate_report  # noqa: E402
from PIL import Image  # noqa: E402


class TestReport(unittest.TestCase):
    def setUp(self) -> None:
        self.run_dir = Path(tempfile.mkdtemp(prefix="pstlab-test-report-"))  # per process, see test_fixtures

    def tearDown(self) -> None:
        if self.run_dir.exists():
            shutil.rmtree(self.run_dir)

    def test_generate_self_contained_report(self) -> None:
        (self.run_dir / "run.json").write_text(
            json.dumps(
                {
                    "started": "2026-10-17T09:00:00Z",
                    "finished": "2026-10-17T09:05:00Z",
                    "commit": "526fb59",
                    "products": {
                        "android-desktop": {"status": "ran"},
                        "phototok": {"status": "not-run", "reason": "phone off"},
                    },
                    "tools": {"python": "3.13"},
                    "journeys": [
                        {
                            "product": "android-desktop",
                            "config": "tab-landscape",
                            "journey": "J-AD-02",
                            "result": "fail",
                            "dir": "android-desktop/tab-landscape/J-AD-02",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        j_dir = self.run_dir / "android-desktop" / "tab-landscape" / "J-AD-02"
        j_dir.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (200, 120), (40, 80, 120)).save(j_dir / "01_three-up.png", format="PNG")
        tree_payload = {
            "platform": "android",
            "window": {"w": 1480, "h": 924, "scale": 2.0, "unit": "dp"},
            "nodes": [],
        }
        (j_dir / "01_three-up.tree.json").write_text(
            json.dumps(tree_payload),
            encoding="utf-8",
        )
        (self.run_dir / "findings.det.json").write_text(
            json.dumps(
                [
                    {
                        "id": "112233445566",
                        "source": "layout-lint",
                        "product": "android-desktop",
                        "config": "tab-landscape",
                        "journey": "J-AD-02",
                        "checkpoint": "three-up",
                        "rule": "occludes-frame",
                        "severity": "blocker",
                        "confidence": "high",
                        "region": [10, 20, 100, 80],
                        "message": "Sidebar overlaps current_frame",
                        "evidence": ["android-desktop/tab-landscape/J-AD-02/01_three-up.png"],
                        "new_since_last_run": True,
                    }
                ]
            ),
            encoding="utf-8",
        )

        md_path, html_path = generate_report(self.run_dir)
        self.assertTrue(md_path.is_file())
        self.assertTrue(html_path.is_file())
        html_text = html_path.read_text(encoding="utf-8")
        self.assertIn("526fb59", html_text)
        self.assertIn("Sidebar overlaps current_frame", html_text)
        self.assertIn("phone off", html_text)
        self.assertNotIn("http://", html_text)
        self.assertNotIn("https://", html_text)


if __name__ == "__main__":
    unittest.main()
