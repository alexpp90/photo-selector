"""The smoke campaign (`run --smoke --since`), the Moto G77 preference and the walk order.

Decisions 12 and 39 of 2026-11-12. Everything runs on a fake inventory and a fake device list:
no adb, no device.
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lab.orchestrate as orch  # noqa: E402
from lab.cli import build_parser, main  # noqa: E402
from lab.inventory import PREFERRED_PHONE_SERIAL, order_by_preference, parse_inventory, select_android_devices  # noqa: E402
from lab.smoke import SmokeError, SmokePlan, changed_products, products_of  # noqa: E402
from lab.walkphones import walk_devices  # noqa: E402

REAL_ORDER = {
    "android": {
        "pixel-4a": {"serial": "13171JEC200817", "role": "phone", "model": "Pixel 4a"},
        "xperia-xa2": {"serial": "CQ30012CMV", "role": "phone"},
        "moto-g77": {"serial": PREFERRED_PHONE_SERIAL, "role": "phone", "model": "Moto G77"},
        "pixel-tablet": {"serial": "3620105H804AS8", "role": "tablet"},
        "tab-s11-ultra": {"serial": "R52Y90AF4LE", "role": "tablet", "personal": True, "supports": ["dex"]},
    }
}


def names(devs) -> list[str]:
    return [d.name for d in devs]


class PreferredPhoneTests(unittest.TestCase):
    def test_the_moto_g77_is_the_first_device_of_a_phone_run_whatever_the_inventory_order(self) -> None:
        inv = parse_inventory(REAL_ORDER)
        self.assertEqual(names(select_android_devices(inv, "phototok")), ["moto-g77", "pixel-4a", "xperia-xa2"])

    def test_the_inventory_order_stays_when_the_moto_is_not_in_the_run(self) -> None:
        inv = parse_inventory(REAL_ORDER)
        self.assertEqual(names(select_android_devices(inv, "phototok", only=["xperia-xa2", "pixel-4a"])), ["pixel-4a", "xperia-xa2"])

    def test_it_is_matched_by_serial_or_by_either_spelling_of_the_name(self) -> None:
        inv = parse_inventory({"android": {
            "a": {"serial": "A", "role": "phone"},
            "moto_g77": {"serial": "X", "role": "phone"},
        }})
        self.assertEqual(names(order_by_preference(inv.android)), ["moto_g77", "a"])
        inv = parse_inventory({"android": {"a": {"serial": "A"}, "m": {"serial": PREFERRED_PHONE_SERIAL}}})
        self.assertEqual(names(order_by_preference(inv.android)), ["m", "a"])

    def test_tablets_are_not_reordered(self) -> None:
        inv = parse_inventory({"android": {"t1": {"serial": "1", "role": "tablet"}, "t2": {"serial": "2", "role": "tablet"}}})
        self.assertEqual(names(order_by_preference(inv.android)), ["t1", "t2"])

    def test_a_phone_run_drives_the_moto_first(self) -> None:
        run_dir = Path(tempfile.mkdtemp(prefix="pstlab-smoke-order-"))
        self.addCleanup(shutil.rmtree, run_dir, True)
        order: list[str] = []

        def resolve(dev):  # type: ignore[no-untyped-def]
            dev.adb_serial = dev.serial
            return True, None

        def journey(**kw):  # type: ignore[no-untyped-def]
            order.append(kw["label"])
            return "pass", []

        with mock.patch.object(orch, "record_device_state", lambda adb: None), \
                mock.patch.object(orch, "reset_config", lambda adb, saved: None), \
                mock.patch.object(orch.AdbClient, "ensure_ready", lambda self: None):
            orch._run_android_product(
                "phototok", [{"id": "J-TOK-01", "configs": ["phone-portrait"]}], None, run_dir, parse_inventory(REAL_ORDER),
                with_personal=False, only_devices=None, build=False, build_state={},
                resolve_fn=resolve, journey_fn=journey, lock_dir=run_dir,
                apk_fn=lambda adb, product, user: (True, None),
            )
        self.assertEqual(order, ["phone-portrait@moto-g77", "phone-portrait@pixel-4a", "phone-portrait@xperia-xa2"])


class WalkOrderTests(unittest.TestCase):
    def resolver(self, attached: set[str]):
        def resolve(dev):  # type: ignore[no-untyped-def]
            if dev.name in attached:
                dev.adb_serial = f"adb-{dev.serial}"
                return True, None
            return False, f"{dev.name} not attached"
        return resolve

    def test_the_moto_walks_first_and_the_next_phone_second(self) -> None:
        inv = parse_inventory(REAL_ORDER)
        walks, not_run = walk_devices(inv, "phototok", resolve=self.resolver({"pixel-4a", "xperia-xa2", "moto-g77"}))
        self.assertEqual([(w.ordinal, w.device.name) for w in walks], [("first", "moto-g77"), ("second", "pixel-4a")])
        self.assertEqual(walks[0].serial, f"adb-{PREFERRED_PHONE_SERIAL}")
        self.assertEqual(not_run, [])

    def test_without_the_moto_the_existing_order_applies_and_the_moto_is_a_named_not_run(self) -> None:
        inv = parse_inventory(REAL_ORDER)
        walks, not_run = walk_devices(inv, "phototok", resolve=self.resolver({"pixel-4a", "xperia-xa2"}))
        self.assertEqual([(w.ordinal, w.device.name) for w in walks], [("first", "pixel-4a"), ("second", "xperia-xa2")])
        self.assertEqual([(d.name, r) for d, r in not_run], [("moto-g77", "moto-g77 not attached")])

    def test_one_attached_device_gives_one_walk_and_no_silent_second(self) -> None:
        walks, _ = walk_devices(parse_inventory(REAL_ORDER), "phototok", resolve=self.resolver({"xperia-xa2"}))
        self.assertEqual([w.ordinal for w in walks], ["first"])

    def test_personal_devices_walk_only_with_the_opt_in(self) -> None:
        inv = parse_inventory(REAL_ORDER)
        attached = {"pixel-tablet", "tab-s11-ultra"}
        walks, _ = walk_devices(inv, "android-desktop", resolve=self.resolver(attached))
        self.assertEqual([w.device.name for w in walks], ["pixel-tablet"])
        walks, _ = walk_devices(inv, "android-desktop", with_personal=True, resolve=self.resolver(attached))
        self.assertEqual([w.device.name for w in walks], ["pixel-tablet", "tab-s11-ultra"])

    def test_the_cli_prints_the_walk_order_for_the_fake_device_list(self) -> None:
        import io
        from contextlib import redirect_stdout

        inv = parse_inventory(REAL_ORDER)
        out = io.StringIO()
        with mock.patch("lab.inventory.load_inventory", return_value=inv), \
                mock.patch("lab.inventory.resolve_android_device", self.resolver({"moto-g77", "pixel-4a"})), \
                redirect_stdout(out):
            code = main(["usability-devices", "--product", "phototok"])
        self.assertEqual(code, 0)
        lines = out.getvalue().strip().splitlines()
        self.assertEqual(lines[0].split()[:2], ["first", "moto-g77"])
        self.assertEqual(lines[1].split()[:2], ["second", "pixel-4a"])
        self.assertTrue(any(line.startswith("not-run") and "xperia-xa2" in line for line in lines))


class SmokeSelectionTests(unittest.TestCase):
    JOURNEYS = [
        {"id": "J-A-01", "configs": ["tab-landscape", "dex-on-device"]},
        {"id": "J-A-05", "configs": ["dex-960x600", "tab-landscape"]},
        {"id": "J-A-06", "configs": ["chromebook-window", "dex-on-device"]},
        {"id": "SMOKE-a", "configs": ["tab-portrait"]},
    ]

    def test_paths_map_to_products(self) -> None:
        self.assertEqual(products_of(["products/android/phototok/src/A.kt"]), {"phototok"})
        self.assertEqual(products_of(["products/android/core/src/B.kt"]), {"phototok", "android-desktop"})
        self.assertEqual(products_of(["products/android/saf/src/C.kt", "products/linux-desktop/src/x.py"]), {"phototok", "android-desktop", "linux"})
        self.assertEqual(products_of(["products/macos-desktop/src/D.swift"]), {"macos"})
        self.assertEqual(products_of(["scripts/lab/lab/cli.py", "docs/README.md", "products/desktop/src/x.py"]), set())
        self.assertEqual(products_of(["products/android/build.gradle.kts"]), {"phototok", "android-desktop"})

    def test_changed_products_asks_git_since_the_ref_and_rejects_an_unknown_ref(self) -> None:
        seen: list[list[str]] = []

        def git(cmd, **kw):  # type: ignore[no-untyped-def]
            seen.append(cmd)
            return SimpleNamespace(returncode=0, stdout="products/linux-desktop/src/a.py\nREADME.md\n", stderr="")

        self.assertEqual(changed_products("origin/main", Path("/repo"), git=git), {"linux"})
        self.assertIn("origin/main", seen[0])
        with self.assertRaises(SmokeError):
            changed_products("nope", Path("/repo"), git=lambda cmd, **kw: SimpleNamespace(returncode=128, stdout="", stderr="bad revision"))

    def test_an_unchanged_product_runs_its_smoke_journey_only(self) -> None:
        plan = SmokePlan(changed=set())
        self.assertEqual([j["id"] for j in plan.journeys("android-desktop", self.JOURNEYS)], ["SMOKE-a"])
        without_smoke = self.JOURNEYS[:3]
        self.assertEqual([j["id"] for j in plan.journeys("android-desktop", without_smoke)], ["J-A-01"])

    def test_a_changed_product_runs_every_journey_and_no_since_means_all_changed(self) -> None:
        for plan in (SmokePlan(changed={"android-desktop"}), SmokePlan(changed=None)):
            self.assertEqual(len(plan.journeys("android-desktop", self.JOURNEYS)), 4)

    def test_each_device_runs_one_config_its_reference_and_a_fallback_keeps_every_journey(self) -> None:
        inv = parse_inventory({"android": {
            "tab": {"serial": "1", "role": "tablet"},
            "cb": {"serial": "2", "role": "chromebook", "supports": ["arc"]},
        }})
        tab, cb = inv.android
        plan = SmokePlan()
        by_journey = {j["id"]: j for j in self.JOURNEYS}
        # A tablet without DeX: its reference config where offered, else the first it allows.
        self.assertEqual(plan.configs(by_journey["J-A-01"], tab), ["tab-landscape"])
        self.assertEqual(plan.configs(by_journey["J-A-05"], tab), ["tab-landscape"])
        self.assertEqual(plan.configs(by_journey["SMOKE-a"], tab), ["tab-portrait"])
        self.assertEqual(plan.configs(by_journey["J-A-06"], tab), [])  # nothing the tablet may run
        self.assertEqual(plan.configs(by_journey["J-A-06"], cb), ["chromebook-window"])
        self.assertEqual(plan.configs(by_journey["J-A-01"], None), ["tab-landscape"])  # desktop: the first config

    def test_the_parser_takes_smoke_and_since(self) -> None:
        args = build_parser().parse_args(["run", "--all", "--smoke", "--since", "origin/main"])
        self.assertTrue(args.smoke)
        self.assertEqual(args.since, "origin/main")
        self.assertFalse(build_parser().parse_args(["run", "--all"]).smoke)

    def test_since_without_smoke_and_an_unknown_ref_are_refused_with_exit_2(self) -> None:
        self.assertEqual(main(["run", "--all", "--since", "origin/main"]), 2)
        with mock.patch("lab.smoke.changed_products", side_effect=SmokeError("unknown ref")):
            self.assertEqual(main(["run", "--all", "--smoke", "--since", "nope"]), 2)


class SmokeRunTests(unittest.TestCase):
    """`run_lab(smoke=...)` through the shipped orchestrator, with fake devices and runners."""

    def setUp(self) -> None:
        self.run_dir = Path(tempfile.mkdtemp(prefix="pstlab-smoke-run-"))
        self.addCleanup(shutil.rmtree, self.run_dir, True)

    def run_smoke(self, plan: SmokePlan, products: list[str]) -> list[tuple[str, str]]:
        ran: list[tuple[str, str]] = []

        def resolve(dev):  # type: ignore[no-untyped-def]
            dev.adb_serial = dev.serial
            return True, None

        def journey(**kw):  # type: ignore[no-untyped-def]
            ran.append((kw["manifest"]["id"], kw["label"]))
            return "pass", []

        with mock.patch.object(orch, "record_device_state", lambda adb: None), \
                mock.patch.object(orch, "reset_config", lambda adb, saved: None), \
                mock.patch.object(orch.AdbClient, "ensure_ready", lambda self: None), \
                mock.patch.dict(orch._run_android_product.__kwdefaults__, {  # defaults bind at import
                    "resolve_fn": resolve, "journey_fn": journey,
                    "apk_fn": lambda adb, product, user=0: (True, None),
                }):
            orch.run_lab(
                products=products, out_dir=self.run_dir, devices_inventory=REAL_ORDER, build=False,
                lock_dir=self.run_dir, smoke=plan, with_personal=False,
            )
        return ran

    def test_unchanged_products_run_one_journey_in_one_config_per_device(self) -> None:
        ran = self.run_smoke(SmokePlan(changed=set()), ["phototok", "android-desktop"])
        self.assertEqual(
            ran[:3],
            [("J-TOK-01", "phone-portrait@moto-g77"), ("J-TOK-01", "phone-portrait@pixel-4a"), ("J-TOK-01", "phone-portrait@xperia-xa2")],
        )
        self.assertEqual(ran[3:], [("SMOKE-android-desktop", "tab-landscape@pixel-tablet")])

    def test_a_changed_product_runs_each_of_its_journeys_once_per_device(self) -> None:
        ran = self.run_smoke(SmokePlan(changed={"phototok"}), ["phototok"])
        journeys = {j for j, _ in ran}
        self.assertEqual(journeys, {f"J-TOK-0{i}" for i in range(1, 9)})
        per_device = [lab for j, lab in ran if j == "J-TOK-02"]
        self.assertEqual(per_device, ["phone-portrait@moto-g77", "phone-portrait@pixel-4a", "phone-portrait@xperia-xa2"])
        run = json.loads((self.run_dir / "run.json").read_text())
        self.assertTrue(all(r["result"] == "pass" for r in run["journeys"]))

    def test_without_smoke_the_full_pass_keeps_every_config(self) -> None:
        ran = self.run_smoke(None, ["phototok"])  # type: ignore[arg-type]
        self.assertIn(("J-TOK-02", "phone-landscape@moto-g77"), ran)
        self.assertIn(("J-TOK-02", "phone-dark@xperia-xa2"), ran)


if __name__ == "__main__":
    unittest.main()
