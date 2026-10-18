"""Unit tests for C-31, C-32, C-41, C-60 macOS/Linux runners and lab orchestrator."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.linux.keys import normalize_xdotool_key, send_keys_xdotool  # noqa: E402
from lab.linux.runner import build_journey_commands  # noqa: E402
from lab.macos.remote import preflight_remote_mac  # noqa: E402
from lab.macos.runner import default_app_binary, run_macos_journey  # noqa: E402
from lab.orchestrate import run_lab  # noqa: E402
from PIL import Image  # noqa: E402


class TestMacosLinuxAndOrchestrate(unittest.TestCase):
    def setUp(self) -> None:
        self.run_dir = Path("/tmp/pstlab/test_orchestrate_run")
        if self.run_dir.exists():
            shutil.rmtree(self.run_dir)

    def tearDown(self) -> None:
        if self.run_dir.exists():
            shutil.rmtree(self.run_dir)

    def test_linux_xdotool_keys_and_command_builder(self) -> None:
        self.assertEqual(normalize_xdotool_key("Right"), "Right")
        self.assertEqual(normalize_xdotool_key("ctrl+z"), "ctrl+z")
        self.assertEqual(normalize_xdotool_key("Delete"), "Delete")

        recorded = send_keys_xdotool(
            ["Right", "m"],
            display=":99",
            run_cmd=lambda c, e: subprocess.CompletedProcess(c, 0, stdout="", stderr=""),
            delay_s=0.0,
        )
        self.assertEqual(len(recorded), 3)
        self.assertEqual(recorded[1], ["xdotool", "key", "--clearmodifiers", "Right"])

        cmds = build_journey_commands(
            {"steps": [{"keys": ["Right"]}, {"checkpoint": "opened"}]},
            Path("/tmp/pstlab"),
        )
        self.assertEqual(cmds[0], "key Right")
        self.assertIn("checkpoint opened", cmds)

    def test_default_app_binary_only_picks_builds_that_contain_the_ui_test_hooks(self) -> None:
        """Q-2: a distributable `.build/app` bundle or `.build/release` binary rejects `--ui-scenario`."""
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build = root / "products" / "macos-desktop" / ".build"
            distributable = build / "app" / "PhotoSelector.app" / "Contents" / "MacOS" / "PhotoSelectorApp"
            plain_release = build / "release" / "PhotoSelectorApp"
            for path in (distributable, plain_release):
                path.parent.mkdir(parents=True)
                path.write_text("#!/bin/sh\n")

            self.assertNotIn(default_app_binary(root), (distributable, plain_release))

            hooks = build / "hooks" / "app" / "PhotoSelector.app" / "Contents" / "MacOS" / "PhotoSelectorApp"
            hooks.parent.mkdir(parents=True)
            hooks.write_text("#!/bin/sh\n")
            self.assertEqual(default_app_binary(root), hooks)

    def test_remote_mac_preflight(self) -> None:
        ok, reason = preflight_remote_mac(
            "mac.local",
            run_cmd=lambda c: subprocess.CompletedProcess(c, 0, stdout="ok\n", stderr=""),
        )
        self.assertTrue(ok)
        self.assertIsNone(reason)

        ok_fail, reason_fail = preflight_remote_mac(
            "mac.local",
            run_cmd=lambda c: subprocess.CompletedProcess(c, 255, stdout="", stderr="Connection timed out"),
        )
        self.assertFalse(ok_fail)
        self.assertIn("Mac asleep", reason_fail or "")

    def test_macos_runner_with_fake_exec_and_tally_oracle(self) -> None:
        manifest = {
            "id": "J-MAC-03",
            "product": "macos",
            "fixture": "smoke",
            "configs": ["w1440"],
            "steps": [{"checkpoint": "last"}],
            "tally_expect": {"last": {"moved": 2}},
        }

        scen_path = Path(__file__).resolve().parents[3] / "products/macos-desktop/tests/e2e/J-MAC-03.scenario.json"
        created_temp_scen = False
        if not scen_path.is_file():
            scen_path.parent.mkdir(parents=True, exist_ok=True)
            scen_path.write_text('{"name":"J-MAC-03","steps":[{"checkpoint":"last"}]}\n', encoding="utf-8")
            created_temp_scen = True

        try:
            def fake_exec(cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
                art_idx = cmd.index("--artifacts") + 1
                art_dir = Path(cmd[art_idx])
                Image.new("RGB", (200, 120), (30, 30, 30)).save(art_dir / "01_last.png", format="PNG")
                tree_payload = {
                    "platform": "macos",
                    "window": {"w": 1440, "h": 900, "scale": 2.0, "unit": "pt"},
                    "nodes": [],
                }
                (art_dir / "01_last.tree.json").write_text(json.dumps(tree_payload), encoding="utf-8")
                (art_dir / "01_last.state.json").write_text(
                    json.dumps({"currentIndex": 5, "totalCount": 6, "tally": {"moved": 1}}),
                    encoding="utf-8",
                )
                return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

            status, findings = run_macos_journey(
                manifest,
                "w1440",
                {"size": "1440x900"},
                self.run_dir,
                exec_fn=fake_exec,
            )
            self.assertEqual(status, "fail")
            self.assertTrue(any(f.rule == "fs-tally-mismatch" for f in findings))
        finally:
            if created_temp_scen and scen_path.is_file():
                scen_path.unlink()

    def test_orchestrate_records_not_run_when_devices_unavailable(self) -> None:
        out = run_lab(
            products=["phototok", "linux"],
            journey_filter="J-TOK-01",
            out_dir=self.run_dir,
            preflight_overrides={
                "phototok": (False, "phone device offline"),
                "linux": (False, "Linux host required"),
            },
        )
        self.assertTrue((out / "report.html").is_file())
        res_file = out / "phototok" / "phone-portrait" / "J-TOK-01" / "result.json"
        self.assertTrue(res_file.is_file())
        res_data = json.loads(res_file.read_text(encoding="utf-8"))
        self.assertEqual(res_data["status"], "not-run")
        self.assertEqual(res_data["reason"], "phone device offline")


if __name__ == "__main__":
    unittest.main()
