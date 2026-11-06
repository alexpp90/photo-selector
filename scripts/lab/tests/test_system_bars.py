"""System-bar rectangles from the app dump and from `dumpsys window` insets, and the app-left verdicts."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.android.system_bars import SystemBar, bars_from_dump, bars_from_insets  # noqa: E402
from lab.explore.appleft import EXPECTED, LEAD, OUTBOUND_LINK, classify_app_left, top_task_package  # noqa: E402

DATA_DIR = Path(__file__).resolve().parent / "data"


class TestSystemBars(unittest.TestCase):
    def test_the_real_portrait_dump_lists_both_bars(self) -> None:
        # Pixel 4a, J-TOK-02 portrait, 2026-11-05: real uiautomator nodes.
        bars = bars_from_dump((DATA_DIR / "jackpix_feed_portrait_pixel4a.xml").read_text(encoding="utf-8"))
        self.assertEqual(bars, [SystemBar("status", (0, 0, 1080, 136)), SystemBar("navigation", (0, 2208, 1080, 2340))])

    def test_the_real_landscape_dump_lists_none_so_the_insets_are_needed(self) -> None:
        # The reason `bars_from_insets` exists: the same screen rotated has no bar node at all.
        self.assertEqual(bars_from_dump((DATA_DIR / "jackpix_feed_landscape_pixel4a.xml").read_text(encoding="utf-8")), [])

    def test_the_real_pixel_4a_landscape_insets(self) -> None:
        # Pixel 4a, Android 13, rotation 1, 3-button navigation, captured 2026-11-05 (trimmed to its
        # InsetsSource and cutout lines). The status and navigation bars are bars; the display cutout
        # (a tap there reaches the app), the gesture and tappable-element sources, the empty invisible
        # extra navigation bar and the IME are not.
        text = (DATA_DIR / "pixel4a_landscape_dumpsys_window_insets.txt").read_text(encoding="utf-8")
        self.assertIn("ITYPE_LEFT_DISPLAY_CUTOUT frame=[0,0][136,1080]", text)
        self.assertIn("ITYPE_EXTRA_NAVIGATION_BAR frame=[0,0][0,0] visible=false", text)
        self.assertEqual(bars_from_insets(text), [SystemBar("status", (0, 0, 2340, 77)), SystemBar("navigation", (2208, 0, 2340, 1080))])

    # The one form not captured: AOSP's `InsetsSource.toString()` for Android 12, which no lab device runs.
    def test_insets_android_12_braced_form_from_aosp(self) -> None:
        text = (
            "mInsetsState=InsetsState: {mDisplayFrame=Rect(0, 0 - 2340, 1080), mSources= { "
            "InsetsSource: {mType=ITYPE_STATUS_BAR, mFrame=[0,0][2340,54], mVisible=true, mInsetsRoundedCornerFrame=false}, "
            "InsetsSource: {mType=ITYPE_NAVIGATION_BAR, mFrame=[2208,0][2340,1080], mVisible=true}, "
            "InsetsSource: {mType=ITYPE_IME, mFrame=[0,0][0,0], mVisible=false} }"
        )
        self.assertEqual(bars_from_insets(text), [SystemBar("status", (0, 0, 2340, 54)), SystemBar("navigation", (2208, 0, 2340, 1080))])

    def test_the_real_xperia_xa2_landscape_insets_with_camel_case_types(self) -> None:
        # Xperia XA2, LineageOS, API 37, rotation 1, 3-button navigation, captured 2026-11-05 (trimmed):
        # `InsetsSource id=… type=statusBars frame=… visible=true flags= sideHint=TOP`.
        text = (DATA_DIR / "xperia_xa2_landscape_dumpsys_window_insets.txt").read_text(encoding="utf-8")
        self.assertIn("type=ime frame=[0,0][0,0] visible=false", text)
        self.assertEqual(bars_from_insets(text), [SystemBar("status", (0, 0, 1920, 74)), SystemBar("navigation", (1794, 0, 1920, 1080))])

    def test_bars_without_area_are_dropped(self) -> None:
        self.assertEqual(bars_from_insets("InsetsSource: {mType=ITYPE_NAVIGATION_BAR, mFrame=[0,0][0,0], mVisible=true}"), [])

    def test_contains_is_half_open(self) -> None:
        bar = SystemBar("navigation", (2208, 0, 2340, 1080))
        self.assertTrue(bar.contains(2208, 0))
        self.assertFalse(bar.contains(2207, 100))
        self.assertFalse(bar.contains(2340, 100))


class TestAppLeftVerdict(unittest.TestCase):
    LAUNCHER = frozenset({"com.google.android.apps.nexuslauncher"})
    CHROME = frozenset({"com.android.chrome"})

    def verdict(self, action: str, mode: str, front: frozenset, product: str = "phototok") -> str:
        return classify_app_left(action, mode, product, front, self.LAUNCHER, self.CHROME).kind

    def test_back_from_the_root_to_the_launcher_is_expected(self) -> None:
        self.assertEqual(self.verdict("back", "viewer", self.LAUNCHER), EXPECTED)
        self.assertEqual(self.verdict("back", "selector", self.LAUNCHER, "android-desktop"), EXPECTED)

    def test_back_from_anywhere_else_is_a_lead(self) -> None:
        for mode in ("settings", "selection", "dialog", "unknown"):
            self.assertEqual(self.verdict("back", mode, self.LAUNCHER), LEAD, mode)
        self.assertEqual(self.verdict("back", "viewer", self.CHROME), LEAD)  # not the launcher
        self.assertEqual(self.verdict("back", "viewer", self.LAUNCHER | self.CHROME), LEAD)
        self.assertEqual(self.verdict("back", "viewer", frozenset()), LEAD)  # nothing known about the window
        self.assertEqual(self.verdict("back", "viewer", self.LAUNCHER, "linux-desktop"), LEAD)  # no root modes known

    def test_the_top_resumed_activity_decides_when_the_launcher_hosts_widgets(self) -> None:
        # Pixel Tablet, 2026-11-06: the launcher's window named its widgets' apps too.
        top = top_task_package(
            "topResumedActivity=ActivityRecord{81126719 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t2}"
        )
        self.assertEqual(top, "com.google.android.apps.nexuslauncher")
        widgets = self.LAUNCHER | {"com.google.android.deskclock", "com.google.android.keep"}
        verdict = classify_app_left("back", "selector", "android-desktop", widgets, self.LAUNCHER, self.CHROME, top)
        self.assertEqual(verdict.kind, EXPECTED)
        self.assertEqual(classify_app_left("back", "selector", "android-desktop", widgets, self.LAUNCHER, self.CHROME).kind, LEAD)
        # An overlay above the selector is no root: Back must close it (Frameduel's layout guide).
        for mode in ("guide", "maximised", "fullscreen"):
            self.assertEqual(
                classify_app_left("back", mode, "android-desktop", widgets, self.LAUNCHER, self.CHROME, top).kind, LEAD, mode
            )
        self.assertEqual(top_task_package(""), None)

    def test_a_tap_that_brings_the_default_browser_is_an_outbound_link(self) -> None:
        self.assertEqual(self.verdict("tap", "settings", self.CHROME), OUTBOUND_LINK)
        self.assertEqual(self.verdict("tap", "settings", frozenset({"com.whatsapp"})), LEAD)
        self.assertEqual(self.verdict("tap", "settings", self.LAUNCHER), LEAD)  # a tap to the launcher is not a link
        self.assertEqual(self.verdict("swipe", "viewer", self.CHROME), LEAD)


if __name__ == "__main__":
    unittest.main()
