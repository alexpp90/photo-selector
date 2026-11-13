"""Linux journeys pin the view order the app opens in (`linux_settings`).

2026-11-13: a fresh Blitzlens profile opens newest first, by EXIF capture time (plan 2.2), and
every lab run starts with an empty XDG_CONFIG_HOME. `m` on photo 3 of J-LIN-01 filed DSC_0022,
not DSC_0003, and the fs oracle reported 6 blockers against an app that was right. A journey
that names files by number now asks for file-name order, and the runner seeds it.

The same run showed two more lab defects: every Trash rolled back, because GIO refuses to trash a
/tmp file whose $HOME is on another device, and a repeated `--journey` kept only the last one.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.linux import hub_step  # noqa: E402
from lab.linux.runner import LINUX_VIEW_ORDERS, run_linux_journey, seed_linux_settings  # noqa: E402
from lab.orchestrate import load_journeys_for_product  # noqa: E402
from lab.paths import repo_root  # noqa: E402
from lab.schema import validate  # noqa: E402
import yaml  # noqa: E402

JOURNEYS_DIR = repo_root() / "scripts" / "lab" / "journeys"
LINUX_SRC = repo_root() / "products" / "linux-desktop" / "src"
SESSION_SH = repo_root() / "products" / "linux-desktop" / "tests" / "e2e" / "session.sh"


def _linux_journeys() -> list[tuple[str, dict]]:
    out = []
    for ypath in sorted(JOURNEYS_DIR.glob("*.yaml")):
        data = yaml.safe_load(ypath.read_text(encoding="utf-8"))
        if isinstance(data, dict) and data.get("product") == "linux":
            out.append((ypath.name, data))
    return out


def _names_files(fs_expect: dict) -> bool:
    for expect in (fs_expect or {}).values():
        if not isinstance(expect, dict):
            continue
        for key in ("present", "absent", "trashed"):
            if any("DSC_" in str(p) for p in expect.get(key, []) or []):
                return True
    return False


class TestSeedLinuxSettings(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-linux-seed-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_the_requested_order_lands_where_the_app_reads_its_settings(self) -> None:
        path = seed_linux_settings(self.tmp / "xdg-config", {"view_order": "natural"})
        self.assertEqual(path, self.tmp / "xdg-config" / "photo-selector-linux" / "settings.json")
        self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {"view_order": "natural"})

    def test_no_settings_writes_nothing(self) -> None:
        self.assertIsNone(seed_linux_settings(self.tmp / "xdg-config", None))
        self.assertFalse((self.tmp / "xdg-config" / "photo-selector-linux").exists())

    def test_an_order_the_app_does_not_persist_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            seed_linux_settings(self.tmp / "xdg-config", {"view_order": "lowest_sharpness_first"})
        self.assertNotEqual(
            validate({"id": "x", "product": "linux", "title": "t", "fixture": "smoke", "configs": ["fhd"],
                      "steps": [{"wait_ms": 1}], "linux_settings": {"view_order": "name"}}, "journey"),
            [],
        )

    def test_the_app_opens_in_the_seeded_order(self) -> None:
        # The contract with the product: the file and key the runner writes are the ones
        # Blitzlens' SettingsStore reads, and the runner knows exactly the orders it persists.
        sys.path.insert(0, str(LINUX_SRC))
        self.addCleanup(sys.path.remove, str(LINUX_SRC))
        from photo_selector_linux.core.settings import PERSISTED_ORDERS, SettingsStore, default_settings_path

        self.assertEqual(set(LINUX_VIEW_ORDERS), set(PERSISTED_ORDERS))
        xdg = self.tmp / "xdg-config"
        for order in LINUX_VIEW_ORDERS:
            path = seed_linux_settings(xdg, {"view_order": order})
            self.assertEqual(SettingsStore(path=path).view_order, order)
        with mock.patch.dict("os.environ", {"XDG_CONFIG_HOME": str(xdg)}):
            self.assertEqual(default_settings_path(), xdg / "photo-selector-linux" / "settings.json")

    def test_the_runner_seeds_the_private_config_before_the_session_starts(self) -> None:
        self.addCleanup(setattr, hub_step, "DEFAULT_SETTLE_MS", hub_step.DEFAULT_SETTLE_MS)
        hub_step.DEFAULT_SETTLE_MS = 0
        seen: dict[str, object] = {}

        def session(cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
            lab_dir = Path(cmd[cmd.index("--lab-dir") + 1])
            settings = lab_dir / "xdg-config" / "photo-selector-linux" / "settings.json"
            seen["settings"] = json.loads(settings.read_text(encoding="utf-8")) if settings.is_file() else None
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        manifest = {
            "id": "J-LIN-SEED", "product": "linux", "fixture": "smoke", "configs": ["fhd"],
            "linux_settings": {"view_order": "natural"},
            "steps": [{"wait_ms": 100}],
        }
        run_dir = self.tmp / "run"
        run_linux_journey(manifest, "fhd", {"size": "1920x1080", "scale": 1}, run_dir,
                          fixture_base_dir=self.tmp / "fixtures", exec_fn=session)
        self.assertEqual(seen["settings"], {"view_order": "natural"})
        kept = run_dir / "linux" / "fhd" / "J-LIN-SEED" / "seeded_settings.json"
        self.assertEqual(json.loads(kept.read_text(encoding="utf-8")), {"view_order": "natural"})


class TestLinuxJourneysPinTheirOrder(unittest.TestCase):
    def test_every_journey_that_names_files_pins_a_view_order(self) -> None:
        journeys = _linux_journeys()
        self.assertTrue(journeys)
        unpinned = [
            name for name, data in journeys
            if _names_files(data.get("fs_expect", {}))
            and (data.get("linux_settings") or {}).get("view_order") not in LINUX_VIEW_ORDERS
        ]
        self.assertEqual(unpinned, [], "these journeys name DSC_ files but leave the order to the app's default")

    def test_no_checkpoint_at_photo_1_requires_the_previous_slot(self) -> None:
        # Decision #32: in 3-Up a missing neighbour draws nothing, so photo 1 has no slot_previous.
        # A checkpoint reached with no Right key since launch is at photo 1.
        for name, data in _linux_journeys():
            rights = 0
            for step in data.get("steps", []):
                rights += sum(1 for k in step.get("keys", []) if str(k) == "Right")
                cp = step.get("checkpoint")
                if cp and rights == 0:
                    expect = (data.get("layout_expect") or {}).get(cp) or {}
                    needed = [str(x) for x in expect.get("required", []) if not isinstance(x, list)]
                    needed += [str(x) for x in expect.get("frames", [])]
                    self.assertNotIn("slot_previous", needed, f"{name}: checkpoint {cp!r} is at photo 1")



class TestLabSessionHome(unittest.TestCase):
    def test_the_app_runs_with_a_home_beside_the_fixture(self) -> None:
        # GIO trashes into $XDG_DATA_HOME/Trash only for a file on the device of $HOME; with the
        # real HOME on another disk it refused every /tmp fixture, the app rolled the Trash back,
        # and J-LIN-03's Ctrl+Z undid the Move instead.
        text = SESSION_SH.read_text(encoding="utf-8")
        self.assertIn('LAB_HOME="${LAB_DIR}/home"', text)
        self.assertIn('"$LAB_DIR/home"', text.split("mkdir -p", 1)[1].splitlines()[0])
        launch = [ln for ln in text.splitlines() if '"$@" >' in ln and "journey.log" in ln]
        self.assertEqual(len(launch), 1, launch)
        self.assertTrue(launch[0].strip().startswith('HOME="\'"${LAB_HOME}"\'"'), launch[0])


class TestJourneyFilter(unittest.TestCase):
    def test_a_repeated_journey_flag_runs_each_of_them(self) -> None:
        ids = [j["id"] for j in load_journeys_for_product("linux", ["J-LIN-01", "J-LIN-03"])]
        self.assertEqual(ids, ["J-LIN-01", "J-LIN-03"])
        self.assertEqual([j["id"] for j in load_journeys_for_product("linux", "J-LIN-02")], ["J-LIN-02"])
        self.assertGreaterEqual(len(load_journeys_for_product("linux", None)), 4)

    def test_the_cli_keeps_every_journey_flag(self) -> None:
        from lab import cli, orchestrate

        seen: dict[str, object] = {}
        with mock.patch.object(orchestrate, "run_lab", side_effect=lambda **kw: seen.update(kw)):
            cli.main(["run", "--product", "linux", "--journey", "J-LIN-01", "--journey", "J-LIN-03"])
        self.assertEqual(seen.get("journey_filter"), ["J-LIN-01", "J-LIN-03"])


if __name__ == "__main__":
    unittest.main()
