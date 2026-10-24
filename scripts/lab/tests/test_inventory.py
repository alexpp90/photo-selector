"""Tests for the multi-device inventory, device resolution and the Android fan-out."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lab.android.configs as android_configs  # noqa: E402
from lab.android.configs import (  # noqa: E402
    ConfigNotRunError,
    SavedDeviceState,
    apply_config,
    natural_size,
    record_device_state,
    reset_config,
    window_mode_mismatch,
    set_verified_rotation,
)
from lab.inventory import (  # noqa: E402
    parse_inventory,
    parse_mdns_services,
    resolve_android_device,
    select_android_devices,
)
from lab.orchestrate import _run_android_product  # noqa: E402

INVENTORY = {
    "android": {
        "pixel-4a": {"serial": "AAA111", "role": "phone"},
        "pixel-tablet": {"serial": "TTT222", "role": "tablet"},
        "s25-ultra": {"serial": "PPP333", "role": "phone", "personal": True},
        "tab-s11": {"serial": "SSS444", "role": "tablet", "personal": True, "configs": ["tab-landscape"]},
        "no-serial": {"role": "phone"},
    },
    "mac": {"host": "mac.local"},
}


def _proc(stdout: str = "", rc: int = 0) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess([], rc, stdout, "")


class InventoryTests(unittest.TestCase):
    def test_parse_defaults_products_by_role_and_skips_entries_without_serial(self) -> None:
        inv = parse_inventory(INVENTORY)
        self.assertEqual([d.name for d in inv.android], ["pixel-4a", "pixel-tablet", "s25-ultra", "tab-s11"])
        self.assertEqual(inv.find("pixel-tablet").products, ["android-desktop"])
        self.assertEqual(inv.find("AAA111").products, ["phototok"])
        self.assertTrue(inv.mac is not None and inv.mac.personal)

    def test_personal_devices_need_opt_in(self) -> None:
        inv = parse_inventory(INVENTORY)
        self.assertEqual([d.name for d in select_android_devices(inv, "phototok")], ["pixel-4a"])
        self.assertEqual(
            [d.name for d in select_android_devices(inv, "phototok", with_personal=True)],
            ["pixel-4a", "s25-ultra"],
        )
        # Naming a personal device opts that one in, and only that one.
        self.assertEqual([d.name for d in select_android_devices(inv, "phototok", only=["s25-ultra"])], ["s25-ultra"])

    def test_config_allow_list_and_label(self) -> None:
        dev = parse_inventory(INVENTORY).find("tab-s11")
        self.assertTrue(dev.allows_config("tab-landscape"))
        self.assertFalse(dev.allows_config("tab-native-landscape"))
        self.assertEqual(dev.label("tab-landscape"), "tab-landscape@tab-s11")

    def test_parse_mdns_services(self) -> None:
        text = (
            "List of discovered mdns services\n"
            "adb-R52Y90AF4LE-fAYuME\t_adb-tls-connect._tcp\t192.168.0.117:46567\n"
            "adb-3620105H804AS8-BswZ64\t_adb-tls-connect._tcp\t192.168.0.199:34661\n"
            "adb-XYZ-abc\t_adb-tls-pairing._tcp\t192.168.0.5:3333\n"
            "adb-16FE89264DD1E9CAC0AD\t_adb._tcp\t192.168.0.224:5555\n"
        )
        self.assertEqual(
            parse_mdns_services(text),
            {
                "R52Y90AF4LE": "192.168.0.117:46567",
                "3620105H804AS8": "192.168.0.199:34661",
                "16FE89264DD1E9CAC0AD": "192.168.0.224:5555",
            },
        )

    def test_resolve_connects_via_mdns_and_matches_serial(self) -> None:
        dev = parse_inventory(INVENTORY).find("pixel-tablet")
        connected: list[str] = []

        def run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if cmd[:2] == ["adb", "devices"]:
                rows = "".join(f"{c}\tdevice\n" for c in connected)
                return _proc("List of devices attached\n" + rows)
            if cmd[:3] == ["adb", "mdns", "services"]:
                return _proc("adb-TTT222-x\t_adb-tls-connect._tcp\t10.0.0.9:4444\n")
            if cmd[:2] == ["adb", "connect"]:
                connected.append(cmd[2])
                return _proc(f"connected to {cmd[2]}")
            if cmd[-2:] == ["getprop", "ro.serialno"]:
                return _proc("TTT222\n")
            if cmd[-1] == "get-state":
                return _proc("device\n")
            raise AssertionError(cmd)

        ok, reason = resolve_android_device(dev, run=run)
        self.assertTrue(ok, reason)
        self.assertEqual(dev.adb_serial, "10.0.0.9:4444")

    def test_resolve_prefers_the_mdns_transport_when_connected_twice(self) -> None:
        dev = parse_inventory(INVENTORY).find("pixel-tablet")

        def run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if cmd[:2] == ["adb", "devices"]:
                return _proc(
                    "List of devices attached\n10.0.0.9:4444\tdevice\n"
                    "adb-TTT222-x._adb-tls-connect._tcp\tdevice\n"
                )
            if cmd[-2:] == ["getprop", "ro.serialno"]:
                return _proc("TTT222\n")
            return _proc("device\n")

        ok, _ = resolve_android_device(dev, run=run)
        self.assertTrue(ok)
        self.assertEqual(dev.adb_serial, "adb-TTT222-x._adb-tls-connect._tcp")

    def test_resolve_prefers_usb_over_a_second_wireless_registration(self) -> None:
        # `adb devices` on the hub, 2026-10-19: the Moto G77 on USB and over wireless debugging,
        # the latter under a second mDNS name with a space in it.
        dev = parse_inventory({"android": {"moto-g77": {"serial": "ZY22MQCBL6", "role": "phone"}}}).find("moto-g77")

        def run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if cmd[:2] == ["adb", "devices"]:
                return _proc(
                    "List of devices attached\n13171JEC200817\tdevice\nZY22MQCBL6\tdevice\n"
                    "adb-ZY22MQCBL6-M2Jjof (2)._adb-tls-connect._tcp\tdevice\n"
                )
            return _proc("device\n")

        ok, _ = resolve_android_device(dev, run=run)
        self.assertTrue(ok)
        self.assertEqual(dev.adb_serial, "ZY22MQCBL6")

    def test_resolve_reports_unpaired_device(self) -> None:
        dev = parse_inventory(INVENTORY).find("pixel-tablet")

        def run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
            if cmd[:3] == ["adb", "mdns", "services"]:
                return _proc("adb-TTT222-x\t_adb-tls-connect._tcp\t10.0.0.9:4444\n")
            if cmd[:2] == ["adb", "connect"]:
                return _proc("failed to connect to 10.0.0.9:4444", rc=1)
            return _proc("List of devices attached\n")

        ok, reason = resolve_android_device(dev, run=run)
        self.assertFalse(ok)
        self.assertIn("pair this host", reason or "")


class _FakeAdb:
    """Minimal adb stand-in recording settings writes."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    def run(self, *args: str, timeout: int = 30) -> str:
        self.calls.append(args)
        answers = {
            ("shell", "wm", "size"): "Physical size: 1080x2340\nOverride size: 1080x2000\n",
            ("shell", "wm", "density"): "Physical density: 440\nOverride density: 480\n",
            ("shell", "settings", "get", "system", "font_scale"): "1.15\n",
            ("shell", "cmd", "uimode", "night"): "Night mode: yes\n",
            ("shell", "settings", "get", "global", "stay_on_while_plugged_in"): "0\n",
            ("shell", "settings", "get", "system", "user_rotation"): "0\n",
            ("shell", "settings", "get", "system", "accelerometer_rotation"): "1\n",
        }
        return answers.get(args, "")


class SettingsRestoreTests(unittest.TestCase):
    def test_user_overrides_are_recorded_and_restored(self) -> None:
        adb = _FakeAdb()
        saved = record_device_state(adb)  # type: ignore[arg-type]
        self.assertEqual(
            saved,
            SavedDeviceState(
                user_rotation=0,
                accelerometer_rotation=1,
                size_override="1080x2000",
                density_override=480,
                font_scale="1.15",
                night_mode="yes",
                stay_on="0",
            ),
        )
        adb.calls.clear()
        reset_config(adb, saved)  # type: ignore[arg-type]
        self.assertIn(("shell", "wm", "size", "1080x2000"), adb.calls)
        self.assertIn(("shell", "wm", "density", "480"), adb.calls)
        self.assertIn(("shell", "settings", "put", "system", "font_scale", "1.15"), adb.calls)
        self.assertIn(("shell", "cmd", "uimode", "night", "yes"), adb.calls)
        self.assertIn(("shell", "settings", "put", "global", "stay_on_while_plugged_in", "0"), adb.calls)


class _DisplayAdb:
    """Fake display: portrait-natural panel; `locked` ignores every rotation request."""

    def __init__(self, locked: bool) -> None:
        self.locked = locked
        self.rotation = 0
        self.serial = "FAKE"

    def run(self, *args: str, timeout: int = 30) -> str:
        if args[:2] == ("shell", "wm") and args[2:] == ("size",):
            return "Physical size: 1600x2560\n"
        if args[-2:-1] == ("user_rotation",) and "put" in args and not self.locked:
            self.rotation = int(args[-1])
        return ""

    def window_dp(self) -> tuple[int, int]:
        return (1280, 800) if self.locked or self.rotation in (1, 3) else (800, 1280)


class _LaggingDisplayAdb(_DisplayAdb):
    """Landscape-natural emulator whose readout lags one reading behind a rotation change."""

    def __init__(self) -> None:
        super().__init__(locked=False)
        self.shown = 0  # what the display really shows
        self.puts: list[int] = []

    def run(self, *args: str, timeout: int = 30) -> str:
        if args[-2:-1] == ("user_rotation",) and "put" in args:
            self.puts.append(int(args[-1]))
            self.stale, self.shown = self.shown, int(args[-1])
            return ""
        if args[:3] == ("shell", "dumpsys", "window"):
            return f"  mRotation={self.shown}\n"
        return super().run(*args, timeout=timeout)

    def window_dp(self) -> tuple[int, int]:
        rot = getattr(self, "stale", None)
        self.stale = None
        rot = self.shown if rot is None else rot
        return (1480, 844) if rot in (0, 2) else (844, 1480)


class GeometryTests(unittest.TestCase):
    def test_a_display_already_in_landscape_is_locked_where_it_is(self) -> None:
        adb = _LaggingDisplayAdb()
        set_verified_rotation(adb, "landscape")  # type: ignore[arg-type]
        self.assertEqual(adb.puts, [0])
        self.assertEqual(adb.window_dp(), (1480, 844))

    def test_a_stale_reading_after_a_change_is_not_believed(self) -> None:
        adb = _LaggingDisplayAdb()
        set_verified_rotation(adb, "portrait")  # type: ignore[arg-type]
        self.assertIn(adb.shown, (1, 3))
        self.assertEqual(adb.window_dp(), (844, 1480))

    def setUp(self) -> None:
        self.addCleanup(setattr, android_configs, "ROTATION_POLL_S", android_configs.ROTATION_POLL_S)
        android_configs.ROTATION_POLL_S = 0.0

    def test_size_override_follows_natural_orientation(self) -> None:
        adb = _DisplayAdb(locked=False)
        self.assertEqual(natural_size(adb, "2960x1848"), "1848x2960")  # type: ignore[arg-type]

    def test_rotation_reaches_landscape_and_portrait(self) -> None:
        adb = _DisplayAdb(locked=False)
        set_verified_rotation(adb, "landscape")  # type: ignore[arg-type]
        self.assertIn(adb.rotation, (1, 3))
        set_verified_rotation(adb, "portrait")  # type: ignore[arg-type]
        self.assertIn(adb.rotation, (0, 2))

    def test_locked_rotation_is_not_run(self) -> None:
        adb = _DisplayAdb(locked=True)
        set_verified_rotation(adb, "landscape")  # type: ignore[arg-type]  # already landscape
        with self.assertRaises(ConfigNotRunError):
            set_verified_rotation(adb, "portrait")  # type: ignore[arg-type]


class _WindowAdb:
    """Fake device whose lab app opens in `mode` (fullscreen, or freeform under DeX / ARC)."""

    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.serial = "FAKE"
        self.calls: list[tuple[str, ...]] = []

    def run(self, *args: str, timeout: int = 30) -> str:
        self.calls.append(args)
        return ""

    def app_task(self, app_id: str):  # type: ignore[no-untyped-def]
        return 7, self.mode, (0, 0, 100, 100), (200, 200)


class DexTests(unittest.TestCase):
    def test_dex_config_only_on_devices_that_support_it(self) -> None:
        inv = parse_inventory(
            {"android": {"t": {"serial": "T", "role": "tablet"}, "s": {"serial": "S", "role": "tablet", "supports": ["dex"]}}}
        )
        self.assertFalse(inv.find("t").allows_config("dex-on-device"))
        self.assertTrue(inv.find("s").allows_config("dex-on-device"))

    def test_dex_is_read_from_the_app_window_never_switched(self) -> None:
        app = "com.photoselectortoolbox.lab"
        dex = {"dex": True}
        tab = {"size": "2960x1848", "rotation": "landscape"}
        self.assertIsNone(window_mode_mismatch(_WindowAdb("freeform"), app, dex))  # type: ignore[arg-type]
        self.assertIn("DeX is off", window_mode_mismatch(_WindowAdb("fullscreen"), app, dex) or "")  # type: ignore[arg-type]
        self.assertIsNone(window_mode_mismatch(_WindowAdb("fullscreen"), app, tab))  # type: ignore[arg-type]
        self.assertIn("switch DeX off", window_mode_mismatch(_WindowAdb("freeform"), app, tab) or "")  # type: ignore[arg-type]
        # ChromeOS ARC is always freeform and never DeX.
        self.assertIsNone(window_mode_mismatch(_WindowAdb("freeform"), app, {"native_display": True}))  # type: ignore[arg-type]

    def test_dex_config_touches_no_display_or_system_ui(self) -> None:
        adb = _WindowAdb("freeform")
        apply_config(adb, "dex-on-device")  # type: ignore[arg-type]
        self.assertFalse(any(c[:2] == ("shell", "wm") for c in adb.calls))
        self.assertFalse(any(c[:3] in (("shell", "cmd", "statusbar"), ("shell", "input", "tap")) for c in adb.calls))

    def test_chromebook_window_leaves_the_display_alone(self) -> None:
        adb = _WindowAdb("freeform")
        apply_config(adb, "chromebook-window")  # type: ignore[arg-type]
        self.assertFalse(any(c[:2] == ("shell", "wm") for c in adb.calls))


class AndroidFanOutTests(unittest.TestCase):
    def setUp(self) -> None:
        self.run_dir = Path(tempfile.mkdtemp(prefix="pstlab-fanout-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.run_dir, ignore_errors=True)

    def test_unreachable_device_is_not_run_and_others_still_run(self) -> None:
        inv = parse_inventory(
            {"android": {"a": {"serial": "A", "role": "tablet"}, "b": {"serial": "B", "role": "tablet"}}}
        )
        journeys = [{"id": "J-X", "configs": ["tab-landscape", "tab-portrait"]}]
        calls: list[str] = []

        def resolve(dev):  # type: ignore[no-untyped-def]
            if dev.name == "a":
                return False, "a offline"
            dev.adb_serial = "B"
            return True, None

        def journey(**kw):  # type: ignore[no-untyped-def]
            calls.append(kw["label"])
            if kw["config_name"] == "tab-portrait":
                raise RuntimeError("adb dropped")
            return "pass", []

        import lab.orchestrate as orch

        orig = (orch.record_device_state, orch.reset_config, orch.AdbClient.ensure_ready)
        orch.record_device_state = lambda adb: None  # type: ignore[assignment]
        orch.AdbClient.ensure_ready = lambda self: None  # type: ignore[method-assign]
        try:
            records, findings, meta = _run_android_product(
                "android-desktop",
                journeys,
                None,
                self.run_dir,
                inv,
                with_personal=False,
                only_devices=None,
                build=False,
                build_state={},
                resolve_fn=resolve,
                journey_fn=journey,
                lock_dir=self.run_dir,
                apk_fn=lambda adb, product, user: (True, None),
            )
        finally:
            orch.record_device_state, orch.reset_config, orch.AdbClient.ensure_ready = orig  # type: ignore[method-assign]

        self.assertEqual(calls, ["tab-landscape@b", "tab-portrait@b"])
        by_cfg = {r["config"]: r for r in records}
        self.assertEqual(by_cfg["tab-landscape@a"]["result"], "not-run")
        self.assertEqual(by_cfg["tab-landscape@b"]["result"], "pass")
        self.assertEqual(by_cfg["tab-portrait@b"]["result"], "error")
        self.assertIn("adb dropped", by_cfg["tab-portrait@b"]["reason"])
        err = json.loads((self.run_dir / "android-desktop" / "tab-portrait@b" / "J-X" / "result.json").read_text())
        self.assertEqual(err["status"], "error")
        self.assertEqual(meta["status"], "ran")
        self.assertEqual(meta["devices"]["a"]["status"], "not-run")

    def test_only_personal_devices_reports_the_opt_in(self) -> None:
        inv = parse_inventory({"android": {"p": {"serial": "P", "role": "phone", "personal": True}}})
        records, _, meta = _run_android_product(
            "phototok",
            [{"id": "J-T", "configs": ["phone-portrait"]}],
            None,
            self.run_dir,
            inv,
            with_personal=False,
            only_devices=None,
            build=False,
            build_state={},
        )
        self.assertEqual(meta["status"], "not-run")
        self.assertIn("--with-personal", meta["reason"])
        self.assertEqual(records[0]["result"], "not-run")


if __name__ == "__main__":
    unittest.main()
