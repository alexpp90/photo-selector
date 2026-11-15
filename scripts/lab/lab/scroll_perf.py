"""Large-folder scroll-performance check for the Android products (Jackpix, Frameduel).

Why (2026-11-15, F-TOK-098): Jackpix 0.7.0 froze 3-5 s per swipe in a camera roll of a few
thousand photos while every lab folder (12-32 photos) looked smooth. This stages a folder of
camera-size photos ON the device, opens it cold, swipes through it under `dumpsys gfxinfo`
and fails on device-relative thresholds: any logged `Choreographer: Skipped N frames`, or
p99 / janky share worse than the same device's small-folder baseline by a factor. Never a
fixed percentage: a 60 Hz and a 120 Hz phone have different frame budgets.

The module is the logic (parsing, verdict) plus `ScrollPerfRun`, which drives one device
through an injected `adb(*args) -> str`. Run it through `lab.py scroll-perf`, itself wrapped
in `scripts/android_test_device.py with-lock --serial <s> --` (README, "Large-folder scroll check").
"""

from __future__ import annotations

import json
import re
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Sequence

from .paths import ensure_android_lab_path

WORK_DIR = "/sdcard/PSTLab/work"
STAGE_PREFIX = "PERF_"
SOURCE_NAME = f"{STAGE_PREFIX}0000.jpg"

DEFAULT_SWIPES = 15
DEFAULT_LARGE = 2000  # conditions.json folder_sizes.typical; the measured freeze was at 3,000
DEFAULT_BASELINE = 40  # a small folder that still holds more photos than one run swipes
# Choreographer only logs at >= 30 skipped frames, so any line is a stall of 0.5 s or more.
DEFAULT_SKIPPED_THRESHOLD = 30
DEFAULT_FACTOR = 2.0
JANKY_FLOOR_PCT = 1.0  # a baseline of 0 % janky must not make any janky frame a failure of "x times"

PRODUCTS = {
    "phototok": {"package": "com.phototok", "nav": "swipe"},
    "android-desktop": {"package": "com.photoselectortoolbox", "nav": "key"},
}

Adb = Callable[..., str]


# ── parsing ───────────────────────────────────────────────────────────────────


@dataclass
class Gfx:
    frames: int
    janky: int
    janky_pct: float
    p50: float
    p90: float
    p95: float
    p99: float


class ParseError(ValueError):
    pass


def parse_gfxinfo(text: str) -> Gfx:
    """`dumpsys gfxinfo <pkg>`: frames, janky share, frame-time percentiles in ms."""
    def need(pattern: str) -> re.Match[str]:
        m = re.search(pattern, text)
        if not m:
            raise ParseError(f"gfxinfo has no {pattern!r}")
        return m

    frames = int(need(r"Total frames rendered:\s*(\d+)").group(1))
    j = need(r"Janky frames:\s*(\d+)\s*\(([\d.,]+)%\)")
    pct = {p: float(need(rf"{p}th percentile:\s*([\d.]+)ms").group(1)) for p in (50, 90, 95, 99)}
    return Gfx(frames, int(j.group(1)), float(j.group(2).replace(",", ".")),
               pct[50], pct[90], pct[95], pct[99])


def parse_skipped(logcat: str) -> list[int]:
    """The N of every `Choreographer: Skipped N frames!` line."""
    return [int(n) for n in re.findall(r"Choreographer.*?Skipped (\d+) frames", logcat)]


_COUNTER = re.compile(r"^\s*(\d+)\s*(?:/|of|von)\s*(\d+)\s*$")


def _nodes(dump_xml: str) -> list[ET.Element]:
    try:
        return list(ET.fromstring(dump_xml).iter("node"))
    except ET.ParseError as exc:
        raise ParseError(f"UI dump is not XML: {exc}") from exc


def parse_counter(dump_xml: str) -> tuple[int, int] | None:
    """`(position, total)` of the page counter ("3 / 3000"), or None when none is on screen."""
    for node in _nodes(dump_xml):
        m = _COUNTER.match(node.get("text", "") or "")
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def find_text_center(dump_xml: str, pattern: str) -> tuple[int, int] | None:
    """Centre of the first node whose text matches `pattern` (a dialog button), or None."""
    rx = re.compile(pattern)
    for node in _nodes(dump_xml):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m and rx.fullmatch(node.get("text", "") or ""):
            x1, y1, x2, y2 = map(int, m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


# ── verdict ───────────────────────────────────────────────────────────────────


@dataclass
class Measure:
    label: str
    photos: int
    swipes: int
    start_pos: int
    end_pos: int
    gfx: Gfx
    skipped: list[int]
    first_photo_s: float
    refresh_hz: float
    screens: list[str] = field(default_factory=list)


@dataclass
class Verdict:
    ok: bool
    reasons: list[str]
    line: str


def judge(base: Measure, large: Measure, factor: float = DEFAULT_FACTOR,
          skipped_threshold: int = DEFAULT_SKIPPED_THRESHOLD) -> Verdict:
    """PASS/FAIL of `large` against the same device's `base`. A run that did not measure what
    it claims (counter did not advance by the swipe count) fails with that reason."""
    reasons: list[str] = []
    for m in (base, large):
        moved = m.end_pos - m.start_pos
        if moved != m.swipes:
            reasons.append(f"{m.label}: counter moved {m.start_pos}->{m.end_pos}, expected +{m.swipes}")
        if m.gfx.frames == 0:
            reasons.append(f"{m.label}: no frames rendered, nothing measured")
    stalls = [n for n in large.skipped if n >= skipped_threshold]
    if stalls:
        reasons.append(f"Choreographer skipped {stalls} frames (>= {skipped_threshold}) during the swipes")
    if large.gfx.p99 > factor * base.gfx.p99:
        reasons.append(f"p99 {large.gfx.p99:.0f} ms > {factor:g} x baseline {base.gfx.p99:.0f} ms")
    allowed = factor * max(base.gfx.janky_pct, JANKY_FLOOR_PCT)
    if large.gfx.janky_pct > allowed:
        reasons.append(f"janky {large.gfx.janky_pct:.1f} % > {factor:g} x baseline "
                       f"{max(base.gfx.janky_pct, JANKY_FLOOR_PCT):.1f} %")
    ok = not reasons
    line = (f"{'PASS' if ok else 'FAIL'} {large.label} photos={large.photos} frames={large.gfx.frames} "
            f"janky={large.gfx.janky_pct:.1f}% p99={large.gfx.p99:.0f}ms skipped={large.skipped or 'none'} | "
            f"baseline photos={base.photos} janky={base.gfx.janky_pct:.1f}% p99={base.gfx.p99:.0f}ms "
            f"@{large.refresh_hz:g}Hz first-photo={large.first_photo_s:.1f}s")
    if reasons:
        line += " :: " + "; ".join(reasons)
    return Verdict(ok, reasons, line)


# ── the run ───────────────────────────────────────────────────────────────────


def jpeg_12mp(path: Path, megapixels: int = 12) -> Path:
    """One camera-size JPEG (4000x3000 for 12 MP) with texture, so it weighs what a camera file does."""
    import random

    from PIL import Image  # real Pillow only: the lab fallback writes undecodable files

    if getattr(Image, "__lab_fallback__", False) or not hasattr(Image, "new"):
        raise RuntimeError("real Pillow needed (lab.py re-runs under /usr/bin/python3 with python3-pil)")

    w, h = {12: (4000, 3000), 24: (6000, 4000)}.get(megapixels, (4000, 3000))
    rnd = random.Random(2026)
    small = Image.new("RGB", (w // 8, h // 8))
    small.putdata([(rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)) for _ in range(small.width * small.height)])
    img = small.resize((w, h), Image.BICUBIC)
    img.save(path, "JPEG", quality=92)
    return path


class NoFirstPhoto(RuntimeError):
    """The app never showed a page counter after the cold start: the folder is too heavy for it (a
    frozen app also fails `uiautomator dump`), or it is not on a photo. Not a crash of the check."""


FIRST_PHOTO_TIMEOUT_S = 180


class ScrollPerfRun:
    """One device, one product. `adb(*args, timeout=)` runs `adb -s <serial> *args`, returns stdout text."""

    def __init__(self, adb: Adb, product: str, out_dir: Path, *, package: str | None = None,
                 swipes: int = DEFAULT_SWIPES, settle_s: float = 1.5, sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], float] = time.monotonic, screencap: Callable[[], bytes] | None = None) -> None:
        self.adb, self.product = adb, product
        self.package = package or PRODUCTS[product]["package"]
        self.nav = PRODUCTS[product]["nav"]
        self.out, self.swipes, self.settle_s = out_dir, swipes, settle_s
        self.sleep, self.clock, self._screencap = sleep, clock, screencap
        self.out.mkdir(parents=True, exist_ok=True)
        self._size: tuple[int, int] | None = None

    # device helpers
    def dump(self) -> str:
        remote = ensure_android_lab_path("/sdcard/PSTLab/pst_dump.xml")
        try:
            self.adb("shell", "uiautomator", "dump", remote, timeout=30)
            return self.adb("shell", "cat", remote)
        finally:
            self.adb("shell", "rm", "-f", remote)

    def screen(self, name: str) -> str:
        if self._screencap is None:
            return ""
        path = self.out / f"{name}.png"
        path.write_bytes(self._screencap())
        return str(path)

    def size(self) -> tuple[int, int]:
        if self._size is None:
            m = re.search(r"(\d+)x(\d+)", self.adb("shell", "wm", "size").splitlines()[-1])
            if not m:
                raise RuntimeError("cannot read `wm size`")
            self._size = (int(m.group(1)), int(m.group(2)))
        return self._size

    def refresh_hz(self) -> float:
        m = re.search(r"refreshRate=([\d.]+)|fps=([\d.]+)", self.adb("shell", "dumpsys", "display"))
        return float(m.group(1) or m.group(2)) if m else 0.0

    # staging
    def stage(self, total: int, local_jpeg: Path) -> int:
        """Bring the work folder to `total` photos; return the folder's photo count."""
        existing = self.folder_count(exclude_staged=True)
        extra = max(0, total - existing)
        self.unstage()
        if extra:
            ensure_android_lab_path(f"{WORK_DIR}/{SOURCE_NAME}")
            self.adb("push", str(local_jpeg), f"{WORK_DIR}/{SOURCE_NAME}", timeout=120)
            loop = (f"i=1; while [ $i -le {extra - 1} ]; do "
                    f"cp {WORK_DIR}/{SOURCE_NAME} {WORK_DIR}/{STAGE_PREFIX}$(printf %05d $i).jpg; i=$((i+1)); done")
            self.adb("shell", loop, timeout=900)
        return self.folder_count()

    def unstage(self) -> None:
        self.adb("shell", f"rm -f {WORK_DIR}/{STAGE_PREFIX}*", timeout=300)

    def folder_count(self, exclude_staged: bool = False) -> int:
        pat = f"! -name '{STAGE_PREFIX}*'" if exclude_staged else ""
        out = self.adb("shell", f"find {WORK_DIR} -maxdepth 1 -type f {pat} | wc -l", timeout=120)
        return int(out.strip() or 0)

    # one measured run
    def cold_start(self) -> tuple[tuple[int, int], float]:
        self.adb("shell", "am", "force-stop", self.package)
        self.sleep(1)
        t0 = self.clock()
        self.adb("shell", "monkey", "-p", self.package, "-c", "android.intent.category.LAUNCHER", "1")
        deadline = t0 + FIRST_PHOTO_TIMEOUT_S
        counter = None
        while self.clock() < deadline:
            self.sleep(1)
            try:
                xml = self.dump()
                tap = find_text_center(xml, r"Show new|Neue zeigen|Start with Newest")
                if tap:  # "N new photos": show them, as _ensure_folder.yaml does
                    self.adb("shell", "input", "tap", str(tap[0]), str(tap[1]))
                    continue
                counter = parse_counter(xml)
            except (ParseError, OSError):
                continue  # a busy app often cannot be dumped; keep waiting for its first photo
            if counter:
                break
        if not counter:
            raise NoFirstPhoto(f"no page counter within {FIRST_PHOTO_TIMEOUT_S} s of the cold start")
        return counter, self.clock() - t0

    def read_counter(self, attempts: int = 10) -> tuple[int, int] | None:
        for _ in range(attempts):
            try:
                found = parse_counter(self.dump())
            except (ParseError, OSError):
                found = None
            if found:
                return found
            self.sleep(1)
        return None

    def advance(self) -> None:
        w, h = self.size()
        if self.nav == "swipe":
            x, y1, y2 = w // 2, int(h * 0.7), int(h * 0.3)
            self.adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "250")
        else:
            self.adb("shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT")

    def measure(self, label: str, expected_total: int) -> Measure:
        start, first = self.cold_start()
        shot0 = self.screen(f"{label}-start")
        if start[1] != expected_total:
            raise RuntimeError(f"{label}: the app shows {start[0]} / {start[1]} but the staged folder holds "
                               f"{expected_total}: it has another folder open, so this would measure the wrong one")
        if start[0] + self.swipes > start[1]:
            raise RuntimeError(f"{label}: start at {start[0]} / {start[1]} leaves no room for {self.swipes} swipes")
        self.adb("shell", "dumpsys", "gfxinfo", self.package, "reset")
        self.adb("logcat", "-c")
        for _ in range(self.swipes):
            self.advance()
            self.sleep(self.settle_s)
        gfx_text = self.adb("shell", "dumpsys", "gfxinfo", self.package, timeout=60)
        log = self.adb("logcat", "-d", "-s", "Choreographer:*", timeout=60)
        end = self.read_counter() or (0, 0)
        shot1 = self.screen(f"{label}-end")
        return Measure(label, start[1], self.swipes, start[0], end[0], parse_gfxinfo(gfx_text),
                       parse_skipped(log), first, self.refresh_hz(), [shot0, shot1])

    def run(self, photos: int, baseline_photos: int, local_jpeg: Path, factor: float = DEFAULT_FACTOR) -> tuple[Verdict, Measure, Measure | None]:
        try:
            base_total = self.stage(baseline_photos, local_jpeg)
            base = self.measure("baseline", base_total)
            large_total = self.stage(photos, local_jpeg)
            try:
                large = self.measure("large", large_total)
            except NoFirstPhoto as exc:
                reason = f"{exc} with {large_total} photos (baseline: first photo after {base.first_photo_s:.1f} s)"
                verdict = Verdict(False, [reason], f"FAIL {self.package} photos={large_total} :: {reason}")
                self._write(verdict, base, None)
                return verdict, base, None
        finally:
            self.unstage()
        verdict = judge(base, large, factor)
        self._write(verdict, base, large)
        return verdict, base, large

    def _write(self, verdict: Verdict, base: Measure, large: Measure | None) -> None:
        (self.out / "result.json").write_text(json.dumps(
            {"product": self.product, "package": self.package, "ok": verdict.ok, "line": verdict.line,
             "reasons": verdict.reasons, "baseline": asdict(base),
             "large": asdict(large) if large else None}, indent=2))
