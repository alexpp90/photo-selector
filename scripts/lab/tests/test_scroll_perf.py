"""Large-folder scroll check (2026-11-15, F-TOK-098): parsing, verdict and the run order, no device."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab import scroll_perf as sp  # noqa: E402

GFX = """
Stats since: 123456789ns
Total frames rendered: 684
Janky frames: 30 (4.39%)
Janky frames (legacy): 31 (4.53%)
50th percentile: 12ms
90th percentile: 20ms
95th percentile: 20ms
99th percentile: 61ms
Number Missed Vsync: 3
"""

DUMP = """<?xml version='1.0' encoding='UTF-8'?><hierarchy>
<node text="" bounds="[0,0][1080,2340]"><node text="3 / 3000" bounds="[870,1860][1040,1930]"/>
<node text="Show new" bounds="[100,200][300,260]"/></node></hierarchy>"""


def gfx(p99=20.0, pct=3.0, frames=600) -> sp.Gfx:
    return sp.Gfx(frames, int(frames * pct / 100), pct, 12.0, 18.0, 20.0, p99)


def measure(label="large", photos=3000, gfxv=None, skipped=(), start=1, end=16, swipes=15) -> sp.Measure:
    return sp.Measure(label, photos, swipes, start, end, gfxv or gfx(), list(skipped), 5.0, 60.0)


class ParsingTests(unittest.TestCase):
    def test_gfxinfo(self) -> None:
        g = sp.parse_gfxinfo(GFX)
        self.assertEqual((g.frames, g.janky, g.janky_pct, g.p50, g.p90, g.p99), (684, 30, 4.39, 12.0, 20.0, 61.0))

    def test_gfxinfo_decimal_comma_and_missing_field(self) -> None:
        self.assertEqual(sp.parse_gfxinfo(GFX.replace("4.39%", "4,39%")).janky_pct, 4.39)
        with self.assertRaises(sp.ParseError):
            sp.parse_gfxinfo("Total frames rendered: 3\n")

    def test_skipped(self) -> None:
        log = ("11-15 19:00:01 I/Choreographer( 1): Skipped 187 frames!  The application may be doing too much work\n"
               "11-15 19:00:09 I/Choreographer( 1): Skipped 45 frames!\nunrelated Skipped 9 frames\n")
        self.assertEqual(sp.parse_skipped(log), [187, 45])
        self.assertEqual(sp.parse_skipped(""), [])

    def test_counter_and_dialog_button(self) -> None:
        self.assertEqual(sp.parse_counter(DUMP), (3, 3000))
        self.assertIsNone(sp.parse_counter("<hierarchy><node text='Hello'/></hierarchy>"))
        self.assertEqual(sp.find_text_center(DUMP, r"Show new|Neue zeigen"), (200, 230))
        self.assertIsNone(sp.find_text_center(DUMP, r"Stay"))
        with self.assertRaises(sp.ParseError):
            sp.parse_counter("not xml")


class VerdictTests(unittest.TestCase):
    base = measure("baseline", 40, gfx(p99=22.0, pct=3.7))

    def test_pass(self) -> None:
        v = sp.judge(self.base, measure(gfxv=gfx(p99=30.0, pct=5.0)))
        self.assertTrue(v.ok, v.reasons)
        self.assertTrue(v.line.startswith("PASS"))

    def test_skipped_frames_fail_whatever_the_baseline(self) -> None:
        v = sp.judge(self.base, measure(skipped=[187]))
        self.assertFalse(v.ok)
        self.assertIn("skipped [187]", v.reasons[0])

    def test_logged_skip_below_threshold_is_not_counted(self) -> None:
        self.assertTrue(sp.judge(self.base, measure(skipped=[12])).ok)

    def test_p99_and_janky_are_relative_to_the_same_device(self) -> None:
        slow_base = measure("baseline", 40, gfx(p99=60.0, pct=3.0))
        self.assertTrue(sp.judge(slow_base, measure(gfxv=gfx(p99=100.0))).ok)  # a slow phone is not a failure
        self.assertFalse(sp.judge(self.base, measure(gfxv=gfx(p99=100.0))).ok)
        v = sp.judge(self.base, measure(gfxv=gfx(pct=20.0)))
        self.assertTrue(any("janky" in r for r in v.reasons))

    def test_zero_janky_baseline_has_a_floor(self) -> None:
        zero = measure("baseline", 40, gfx(pct=0.0))
        self.assertTrue(sp.judge(zero, measure(gfxv=gfx(pct=1.5))).ok)
        self.assertFalse(sp.judge(zero, measure(gfxv=gfx(pct=3.0))).ok)

    def test_counter_not_advancing_is_not_a_pass(self) -> None:
        v = sp.judge(self.base, measure(start=1, end=1))
        self.assertFalse(v.ok)
        self.assertIn("counter moved 1->1", v.reasons[0])

    def test_no_frames_is_not_a_pass(self) -> None:
        self.assertFalse(sp.judge(self.base, measure(gfxv=gfx(frames=0))).ok)


class FakeDevice:
    """Answers the adb calls of a run; the app's counter follows `swipes`/`input` calls."""

    def __init__(self, folder_files: int, shown_total: int | None = None, advance: bool = True, skipped: str = "",
                 blank_when_total_over: int | None = None) -> None:
        self.blank_over = blank_when_total_over
        self.files, self.staged = folder_files, 0
        self.shown_total, self.advance_ok, self.skipped = shown_total, advance, skipped
        self.pos, self.calls = 1, []

    def total(self) -> int:
        return self.shown_total if self.shown_total is not None else self.files + self.staged

    def __call__(self, *a: str, timeout: int = 30) -> str:
        self.calls.append(a)
        if a[:2] == ("shell", "uiautomator"):
            return ""
        if a[:2] == ("shell", "cat"):
            if self.blank_over is not None and self.total() > self.blank_over:
                return ""  # a frozen app cannot be dumped
            return f'<hierarchy><node text="{self.pos} / {self.total()}" bounds="[0,0][1,1]"/></hierarchy>'
        if a[0] == "push":
            self.staged = 1
        elif a[0] == "shell" and a[1].startswith("i=1; while"):
            self.staged = int(a[1].split("-le ")[1].split(" ")[0]) + 1
        elif a[0] == "shell" and a[1].startswith("rm -f /sdcard/PSTLab/work/PERF_"):
            self.staged = 0
        elif a[0] == "shell" and "wc -l" in a[1]:
            return str(self.files if "! -name" in a[1] else self.files + self.staged)
        elif a[:3] == ("shell", "monkey", "-p"):
            self.pos = 1
        elif a[:3] == ("shell", "input", "swipe") and self.advance_ok:
            self.pos += 1
        elif a[:2] == ("shell", "wm"):
            return "Physical size: 1080x2340\n"
        elif a[:3] == ("shell", "dumpsys", "display"):
            return "refreshRate=60.0"
        elif a[:3] == ("shell", "dumpsys", "gfxinfo") and "reset" not in a:
            return GFX
        elif a[0] == "logcat" and "-d" in a:
            return self.skipped
        return ""


def run_with(dev: FakeDevice, photos=3000, baseline=40):
    with tempfile.TemporaryDirectory() as tmp:
        jpeg = Path(tmp) / "src.jpg"
        jpeg.write_bytes(b"x")
        run = sp.ScrollPerfRun(dev, "phototok", Path(tmp) / "out", swipes=15, settle_s=0, sleep=lambda s: None)
        return run.run(photos, baseline, jpeg)


class RunTests(unittest.TestCase):
    def test_stages_measures_and_cleans_up(self) -> None:
        dev = FakeDevice(12)
        v, base, large = run_with(dev)
        self.assertEqual((base.photos, large.photos), (40, 3000))
        self.assertEqual((large.start_pos, large.end_pos), (1, 16))
        self.assertEqual(dev.staged, 0)
        self.assertEqual(sum(1 for c in dev.calls if c[:3] == ("shell", "input", "swipe")), 30)
        self.assertTrue(v.ok, v.reasons)  # identical gfx for baseline and large

    def test_skipped_frames_fail_the_run(self) -> None:
        v, _b, _l = run_with(FakeDevice(12, skipped="I/Choreographer(1): Skipped 150 frames!"))
        self.assertFalse(v.ok)

    def test_wrong_folder_open_is_refused_and_still_cleans_up(self) -> None:
        dev = FakeDevice(12, shown_total=17)
        with self.assertRaisesRegex(RuntimeError, "another folder"):
            run_with(dev)
        self.assertEqual(dev.staged, 0)

    def test_app_that_never_shows_a_photo_fails_the_verdict_and_cleans_up(self) -> None:
        dev = FakeDevice(12, blank_when_total_over=100)
        old, sp.FIRST_PHOTO_TIMEOUT_S = sp.FIRST_PHOTO_TIMEOUT_S, 0.05
        try:
            v, base, large = run_with(dev)
        finally:
            sp.FIRST_PHOTO_TIMEOUT_S = old
        self.assertFalse(v.ok)
        self.assertIsNone(large)
        self.assertIn("no page counter", v.line)
        self.assertEqual(dev.staged, 0)

    def test_swipes_that_do_not_land_fail_the_verdict(self) -> None:
        v, _b, _l = run_with(FakeDevice(12, advance=False))
        self.assertFalse(v.ok)

    def test_keyboard_navigation_for_frameduel(self) -> None:
        dev = FakeDevice(12)
        with tempfile.TemporaryDirectory() as tmp:
            run = sp.ScrollPerfRun(dev, "android-desktop", Path(tmp), swipes=3, settle_s=0, sleep=lambda s: None)
            run.advance()
        self.assertIn(("shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT"), dev.calls)


if __name__ == "__main__":
    unittest.main()
