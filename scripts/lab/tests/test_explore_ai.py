"""AI providers: packets, CLI invocation, answer parsing, and failing loudly."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _explore_fakes import default_state, make_state  # noqa: E402

from lab.explore import ai_adapters as ai  # noqa: E402
from lab.explore.actions import Action  # noqa: E402
from lab.explore.expectations import Expectation  # noqa: E402

ACTION = Action("tap", target_id="btn_ok", label="OK")
EXPECTATION = Expectation(ACTION, "OK does nothing")


class AiTestCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.run_dir = Path(tmp.name) / "run"
        self.run_dir.mkdir()
        tmp_lab = Path(tmp.name) / "lab-tmp"
        env = patch.dict(os.environ, {"PST_LAB_TMP": str(tmp_lab)})
        env.start()
        self.addCleanup(env.stop)
        self.lab_tmp = tmp_lab

    def ctx(self, step: int = 1, timeout_s: float = 30.0, packet: Path | None = None) -> ai.JudgeContext:
        return ai.JudgeContext(
            product="phototok", config="default@pixel", checkpoint=f"step_{step:02d}", step_index=step,
            run_dir=self.run_dir, packet_dir=packet or self.run_dir / "packets" / f"step_{step:02d}", timeout_s=timeout_s,
        )

    def judge(self, provider: ai.AIProvider, ctx: ai.JudgeContext | None = None) -> ai.Judgement:
        return provider.judge(default_state(), ACTION, EXPECTATION, make_state(color=(200, 10, 10)), ctx or self.ctx())


class FactoryTest(unittest.TestCase):
    def test_names(self) -> None:
        self.assertIsInstance(ai.create_ai_adapter("none"), ai.NoneProvider)
        self.assertIsInstance(ai.create_ai_adapter("packet"), ai.PacketExportAdapter)
        claude, gemini = ai.create_ai_adapter("claude"), ai.create_ai_adapter("antigravity")
        self.assertEqual((claude.cli, gemini.cli), ("claude", "gemini"))

    def test_unknown_provider_raises(self) -> None:
        with self.assertRaises(ValueError):
            ai.create_ai_adapter("gpt")

    def test_availability_follows_the_binary(self) -> None:
        with patch("shutil.which", return_value=None):
            self.assertFalse(ai.create_ai_adapter("claude").is_available())
        with patch("shutil.which", return_value="/usr/bin/claude"):
            self.assertTrue(ai.create_ai_adapter("claude").is_available())
        self.assertTrue(ai.create_ai_adapter("packet").is_available())


class PacketTest(AiTestCase):
    def test_packet_holds_what_a_reviewer_needs(self) -> None:
        judgement = self.judge(ai.PacketExportAdapter())
        packet = judgement.packet_dir
        self.assertEqual(judgement.findings, [])
        self.assertEqual(packet, self.run_dir / "packets" / "step_01")
        for name in ("images/01_before.png", "images/01_after.png", "before.tree.json", "after.tree.json",
                     "context.md", "instructions.md", "schema.json", "manifest.json"):
            self.assertTrue((packet / name).is_file(), name)
        manifest = json.loads((packet / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual((manifest["checkpoint"], manifest["product"], manifest["config"]), ("step_01", "phototok", "default@pixel"))
        self.assertGreater(manifest["image_change_fraction"], 0.0)
        self.assertIn("OK does nothing", (packet / "context.md").read_text(encoding="utf-8"))

    def test_packets_stay_in_the_run_directory(self) -> None:
        with self.assertRaises(ValueError):
            self.judge(ai.PacketExportAdapter(), self.ctx(packet=Path(tempfile.gettempdir()) / "elsewhere"))

    def test_a_step_without_a_screenshot_cannot_be_reviewed(self) -> None:
        state = default_state()
        state.screenshot_bytes = b""
        with self.assertRaises(ai.AIJudgeError):
            ai.PacketExportAdapter().judge(state, ACTION, EXPECTATION, default_state(), self.ctx())

    def test_an_unreadable_screenshot_is_an_ai_error(self) -> None:
        state = default_state()
        state.screenshot_bytes = b"not a png"
        with self.assertRaises(ai.AIJudgeError):
            ai.PacketExportAdapter().judge(state, ACTION, EXPECTATION, default_state(), self.ctx())


def completed(stdout: str) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(["claude"], 0, stdout, "")


class ParseTest(AiTestCase):
    manifest = {"images": [{"role": "after", "orig_size": [1000, 2000], "bundle_size": [500, 1000]}]}

    def test_findings_take_product_and_checkpoint_from_the_run(self) -> None:
        raw = 'Here you go:\n[{"rule": "clipped-text", "severity": "major", "message": "Title clipped", "region": [10, 20, 30, 40], "product": "linux", "checkpoint": "step_99"}]'
        (finding,) = ai.parse_ai_findings(raw, self.ctx(step=4), self.manifest)
        self.assertEqual((finding.product, finding.checkpoint, finding.config), ("phototok", "step_04", "default@pixel"))
        self.assertEqual((finding.source, finding.rule, finding.severity), ("ai-explorer", "clipped-text", "major"))
        self.assertEqual(finding.region, [20.0, 40.0, 60.0, 80.0])  # review-image pixels scaled to the screenshot

    def test_an_empty_array_means_nothing_found(self) -> None:
        self.assertEqual(ai.parse_ai_findings("[]", self.ctx(), self.manifest), [])

    def test_unusable_answers_raise(self) -> None:
        for raw in ("I see no problems.", "[{broken", '["text"]', '[{"rule": "x", "severity": "catastrophic", "message": "m"}]'):
            with self.assertRaises(ai.AIJudgeError, msg=raw):
                ai.parse_ai_findings(raw, self.ctx(), self.manifest)


class CliJudgeTest(AiTestCase):
    def test_claude_command_uses_an_alias_and_prompt_first(self) -> None:
        cmd = ai.CliJudge("claude").build_command("PROMPT")
        self.assertEqual(cmd[:2], ["claude", "-p"])
        self.assertEqual(cmd[2], "PROMPT")
        self.assertEqual(cmd[cmd.index("--model") + 1], "sonnet")
        self.assertLess(cmd.index("-p"), cmd.index("--allowedTools"))
        self.assertNotRegex(" ".join(cmd), r"claude-\d")

    def test_gemini_command_names_its_inputs(self) -> None:
        cmd = ai.CliJudge("gemini").build_command("PROMPT")
        self.assertEqual(cmd[:2], ["gemini", "-p"])
        self.assertIn("@before.png", cmd[2])
        self.assertNotIn("-m", cmd)

    def test_a_good_answer_returns_findings_and_keeps_the_raw_reply(self) -> None:
        answer = '[{"rule": "low-contrast", "severity": "minor", "message": "Grey on grey"}]'
        with patch("subprocess.run", return_value=completed(answer)) as run:
            judgement = self.judge(ai.CliJudge("claude"), self.ctx(timeout_s=12.5))
        (finding,) = judgement.findings
        self.assertEqual(finding.rule, "low-contrast")
        self.assertEqual(run.call_args.kwargs["timeout"], 12.5)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual((judgement.packet_dir / "findings.raw.txt").read_text(encoding="utf-8"), answer)

    def test_the_cli_runs_in_a_sandbox_outside_the_run_and_the_sandbox_is_removed(self) -> None:
        seen = {}

        def fake_run(cmd, cwd, **kw):
            seen["cwd"] = Path(cwd)
            seen["files"] = sorted(p.name for p in Path(cwd).iterdir())
            return completed("[]")

        with patch("subprocess.run", side_effect=fake_run):
            self.judge(ai.CliJudge("claude"))
        self.assertTrue(str(seen["cwd"]).startswith(str(self.lab_tmp)))
        self.assertFalse(seen["cwd"].exists())
        for name in ("before.png", "after.png", "context.md", "schema.json"):
            self.assertIn(name, seen["files"])

    def test_cli_failures_raise_ai_errors(self) -> None:
        failures = [
            subprocess.CalledProcessError(1, ["claude"], "", "rate limited"),
            subprocess.TimeoutExpired(["claude"], 5),
            FileNotFoundError("claude"),
        ]
        for exc in failures:
            with patch("subprocess.run", side_effect=exc), self.assertRaises(ai.AIJudgeError, msg=repr(exc)):
                self.judge(ai.CliJudge("claude"))

    def test_an_unparseable_answer_raises(self) -> None:
        with patch("subprocess.run", return_value=completed("Looks fine to me.")), self.assertRaises(ai.AIJudgeError):
            self.judge(ai.CliJudge("claude"))


if __name__ == "__main__":
    unittest.main()
