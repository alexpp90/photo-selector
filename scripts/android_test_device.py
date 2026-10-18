#!/usr/bin/env python3
"""Pick, prepare and stock the dedicated Android test device.

A workstation often has several Android devices attached over adb — personal phones and
tablets on wireless debugging next to the one device that exists only for testing. Gradle's
``connectedDebugAndroidTest`` installs on, and afterwards uninstalls from, *every* attached
device, so ``scripts/run_tests.sh`` pins the instrumented tests to one designated device
(via ``ANDROID_SERIAL``, which the Android Gradle plugin honours) and never touches the rest.

The designated device is, in order:

1. ``$ANDROID_SERIAL``, if set;
2. ``pst.testDevice.serial`` in ``products/android/local.properties`` (machine-local, ignored
   by git — the place for a device that stays connected to this machine);
3. the only attached device, when exactly one is attached.

Subcommands::

    python3 scripts/android_test_device.py serial       # print the test device serial (exit 1 if none)
    python3 scripts/android_test_device.py prepare      # make the device test-ready (idempotent)
    python3 scripts/android_test_device.py load-images  # push fixture photos to /sdcard/PSTLab/
    python3 scripts/android_test_device.py status       # what is attached, designated, configured
    python3 scripts/android_test_device.py with-lock [--timeout S] -- <cmd...>  # run cmd holding the device lock
    python3 scripts/android_test_device.py lock-holder  # who holds the device lock (exit 1 if free)

``prepare`` mirrors what CI's emulator gets (animations off) and keeps a device that is
plugged in permanently usable: screen on while powered, keyguard off, fixed portrait
rotation, default display size, density and font scale, no package-verifier prompt on adb
installs. It uses ``adb root`` when the build allows it and works without it otherwise.

``load-images`` generates the device lab's deterministic fixture sets
(``scripts/lab/fixtures/spec.yaml``; needs Pillow and PyYAML) plus the supported-format
corpus, and pushes them to ``/sdcard/PSTLab/<set>/`` — the only device path the lab writes.

``with-lock`` holds the per-device lock the device lab's usability walkthroughs take
(the lab's ``device_lock()``: an ``fcntl.flock`` on ``<lab tmp root>/device-<serial>.lock``,
``/tmp/pstlab`` unless ``PST_LAB_TMP`` moves it) while it runs a command, so two sessions never drive the test
device at once. It waits for a held lock up to ``--timeout`` seconds, then names the holder
and exits with ``LOCK_BUSY_EXIT``.
"""

from __future__ import annotations

import argparse
import fcntl
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
LOCAL_PROPERTIES = REPO_ROOT / "products" / "android" / "local.properties"
SERIAL_PROPERTY = "pst.testDevice.serial"
DEVICE_ROOT = "/sdcard/PSTLab"
HOST_STAGING = Path("/tmp/pstlab")
LAB_DIR = REPO_ROOT / "scripts" / "lab"
# EX_TEMPFAIL: the device is held by another session; distinct from any failure of the command.
LOCK_BUSY_EXIT = 75
DEFAULT_LOCK_TIMEOUT = 300.0

Runner = Callable[[Sequence[str]], Tuple[int, str]]


def run(cmd: Sequence[str], timeout: int = 120) -> Tuple[int, str]:
    try:
        out = subprocess.run(list(cmd), capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 1, str(exc)
    return out.returncode, (out.stdout + out.stderr).strip()


# ── Selection ─────────────────────────────────────────────────────────────────


def read_properties(path: Path) -> Dict[str, str]:
    """Parse a Java ``.properties`` file (the simple ``key=value`` subset local.properties uses)."""
    props: Dict[str, str] = {}
    if not path.is_file():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def attached_serials(listing: str) -> List[str]:
    """Serials in state ``device`` from ``adb devices`` output (offline/unauthorized are not usable)."""
    serials = []
    for line in listing.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            serials.append(fields[0])
    return serials


def resolve_serial(
    env: Dict[str, str], props: Dict[str, str], attached: List[str]
) -> Tuple[Optional[str], bool, str]:
    """Return ``(serial, designated, reason)``.

    ``designated`` is True when the serial was named explicitly (environment or
    local.properties) rather than inferred from a single attached device. ``serial`` is None
    when no device may be used, and ``reason`` then says why.
    """
    for source, value in (("ANDROID_SERIAL", env.get("ANDROID_SERIAL", "")),
                          (f"{SERIAL_PROPERTY} in local.properties", props.get(SERIAL_PROPERTY, ""))):
        value = value.strip()
        if value:
            if value in attached:
                return value, True, f"designated by {source}"
            return None, True, f"designated test device {value} ({source}) is not attached"
    if len(attached) == 1:
        return attached[0], False, "only attached device"
    if not attached:
        return None, False, "no device/emulator attached"
    return None, False, (
        f"{len(attached)} devices attached and none designated — refusing to install tests on all "
        f"of them; set {SERIAL_PROPERTY}=<serial> in products/android/local.properties"
    )


def select(runner: Runner = run) -> Tuple[Optional[str], bool, str]:
    if not shutil.which("adb"):
        return None, False, "adb not found — install Android SDK platform-tools"
    code, listing = runner(["adb", "devices"])
    if code != 0:
        return None, False, f"adb devices failed: {listing}"
    return resolve_serial(dict(os.environ), read_properties(LOCAL_PROPERTIES), attached_serials(listing))


# ── Preparation ───────────────────────────────────────────────────────────────

# (description, shell command). Each is idempotent and safe to repeat before every run.
PREPARE_STEPS: List[Tuple[str, str]] = [
    # CI's emulator runs with `disable-animations: true`; match it.
    ("animations off", "settings put global window_animation_scale 0 && "
                       "settings put global transition_animation_scale 0 && "
                       "settings put global animator_duration_scale 0"),
    ("screen stays on while powered", "svc power stayon true"),
    ("screen-off timeout at maximum", "settings put system screen_off_timeout 2147483647"),
    ("keyguard disabled", "locksettings set-disabled true"),
    ("fixed portrait rotation", "settings put system accelerometer_rotation 0 && "
                                "settings put system user_rotation 0"),
    ("default display size and density", "wm size reset && wm density reset"),
    ("default font scale", "settings put system font_scale 1.0"),
    ("light theme", "cmd uimode night no"),
    ("no verifier prompt on adb installs", "settings put global verifier_verify_adb_installs 0 && "
                                           "settings put global package_verifier_enable 0"),
    ("low fixed brightness", "settings put system screen_brightness_mode 0 && "
                             "settings put system screen_brightness 40"),
    ("screen awake and unlocked", "input keyevent KEYCODE_WAKEUP && wm dismiss-keyguard"),
]


def ensure_root(serial: str, runner: Runner = run) -> bool:
    """Restart adbd as root when the build allows it; True if the shell now runs as uid 0."""
    code, uid = runner(["adb", "-s", serial, "shell", "id", "-u"])
    if code == 0 and uid.strip() == "0":
        return True
    code, out = runner(["adb", "-s", serial, "root"])
    if code != 0 or "cannot run as root" in out:
        return False
    runner(["adb", "-s", serial, "wait-for-device"])
    code, uid = runner(["adb", "-s", serial, "shell", "id", "-u"])
    return code == 0 and uid.strip() == "0"


def may_reconfigure(serial: str, designated: bool) -> bool:
    """Persistent settings change only on a device someone named, or on an emulator.

    The only attached device is good enough to run tests on, but it may be a personal phone
    on wireless debugging: disabling its keyguard or Play Protect is not ours to do.
    """
    return designated or serial.startswith("emulator-")


def prepare(serial: str, runner: Runner = run) -> int:
    """Apply PREPARE_STEPS; return the number of steps that failed."""
    root = ensure_root(serial, runner)
    print(f"  device {serial}: adb {'root' if root else 'shell (no root)'}")
    failures = 0
    for name, command in PREPARE_STEPS:
        code, out = runner(["adb", "-s", serial, "shell", command])
        ok = code == 0 and "Exception" not in out and "Error" not in out
        failures += 0 if ok else 1
        print(f"  {'✓' if ok else '✗'} {name}" + ("" if ok else f" — {out.splitlines()[-1] if out else code}"))
    return failures


# ── Images ────────────────────────────────────────────────────────────────────


def device_set_path(name: str) -> str:
    """The device directory for one image set, guaranteed to sit inside /sdcard/PSTLab/."""
    if not name or "/" in name or name in (".", ".."):
        raise ValueError(f"invalid image set name {name!r}")
    return f"{DEVICE_ROOT}/{name}"


def stage_images(sets: List[str], staging: Path) -> Dict[str, Path]:
    """Generate the requested fixture sets and the format corpus under ``staging``.

    Returns ``{set name: host directory}``. Sets whose inputs are unavailable are reported
    and left out rather than failing the whole load.
    """
    staged: Dict[str, Path] = {}
    _use_lab_package()
    try:
        from lab.fixtures import CorpusMissingError, generate_fixtures, load_spec
    except ImportError as exc:
        print(f"  ✗ fixture sets — {exc}; run with an interpreter that has Pillow and PyYAML "
              f"(e.g. /usr/bin/python3)")
    else:
        available = sorted(load_spec().get("sets", {}))
        for name in sets or available:
            if name == "format-corpus":
                continue
            if name not in available:
                print(f"  ✗ {name} — unknown set; available: {', '.join(available)}, format-corpus")
                continue
            try:
                files = generate_fixtures(name, staging / name)
            except CorpusMissingError as exc:
                print(f"  ✗ {name} — {exc} (python3 scripts/fetch_format_corpus.py)")
                continue
            staged[name] = staging / name
            print(f"  ✓ {name}: {len(files)} files generated")

    if not sets or "format-corpus" in sets:
        corpus_dir = staging / "format-corpus"
        corpus_dir.mkdir(parents=True, exist_ok=True)
        sources = [REPO_ROOT / "testdata" / "formats" / "bitmaps"]
        cache = Path(os.environ.get("PST_FORMAT_CORPUS", "~/.cache/photo-selector-toolbox/format-corpus"))
        sources.append(cache.expanduser())
        count = 0
        for src in sources:
            for f in sorted(src.glob("*")) if src.is_dir() else []:
                if f.is_file() and not f.name.startswith("."):
                    shutil.copy2(f, corpus_dir / f.name)
                    count += 1
        staged["format-corpus"] = corpus_dir
        print(f"  ✓ format-corpus: {count} files (RAW samples need python3 scripts/fetch_format_corpus.py)")
    return staged


def push_images(serial: str, staged: Dict[str, Path], runner: Runner = run) -> int:
    """Replace each set on the device and trigger a media scan; return the number of failures."""
    failures = 0
    for name, host_dir in sorted(staged.items()):
        remote = device_set_path(name)
        runner(["adb", "-s", serial, "shell", "rm", "-rf", remote])
        runner(["adb", "-s", serial, "shell", "mkdir", "-p", DEVICE_ROOT])
        code, out = runner(["adb", "-s", serial, "push", f"{host_dir}/.", remote])
        failures += 0 if code == 0 else 1
        print(f"  {'✓' if code == 0 else '✗'} {remote}" + ("" if code == 0 else f" — {out}"))
    # MediaStore picks the files up for the system photo picker; SAF sees them regardless.
    runner(["adb", "-s", serial, "shell", "content", "call", "--uri", "content://media",
            "--method", "scan_volume", "--arg", "external_primary"])
    return failures


# ── Device lock ───────────────────────────────────────────────────────────────
#
# The lock itself is the device lab's `device_lock()` (scripts/lab/lab/usability/driver.py),
# so a walkthrough and an instrumented run agree on file, flock semantics and holder text by
# construction. The lab refuses a held lock at once; with-lock polls it until a timeout.


def _use_lab_package() -> None:
    if str(LAB_DIR) not in sys.path:
        sys.path.insert(0, str(LAB_DIR))


def device_lock_path(serial: str, base: Optional[Path] = None) -> Path:
    """``<base>/device-<serial>.lock``; ``base`` defaults to the lab's ``lab_tmp_root()``."""
    if not serial or "/" in serial or serial in (".", ".."):
        raise ValueError(f"invalid device serial {serial!r}")
    if base is None:
        _use_lab_package()
        from lab.paths import lab_tmp_root

        base = lab_tmp_root()
    return base / f"device-{serial}.lock"


def lock_holder(path: Path) -> Optional[str]:
    """The holder text if someone holds the lock at ``path``, else None (a free lock is left free)."""
    try:
        fd = os.open(path, os.O_RDONLY)
    except FileNotFoundError:
        return None
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            return os.pread(fd, 400, 0).decode("utf-8", errors="replace").strip() or "unknown holder"
        return None  # the text left behind by an earlier holder is stale
    finally:
        os.close(fd)


def _run_to_completion(cmd: Sequence[str]) -> int:
    forwarded = (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)
    previous = {sig: signal.getsignal(sig) for sig in forwarded}
    try:
        try:
            proc = subprocess.Popen(list(cmd))
        except OSError as exc:
            print(f"cannot run {cmd[0]}: {exc}", file=sys.stderr)
            return 127
        # Keep the lock until the command has really ended: pass SIGTERM/SIGHUP on instead of
        # dying, and sit out Ctrl-C, which the terminal already delivers to the command's group.
        for sig in forwarded:
            signal.signal(sig, (lambda signum, _frame: None) if sig == signal.SIGINT
                          else (lambda signum, _frame: proc.send_signal(signum)))
        code = proc.wait()
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    if code < 0:
        return 128 - code
    # A command that itself exits 75 must not be mistaken for a busy device.
    return 1 if code == LOCK_BUSY_EXIT else code


def with_lock(
    cmd: Sequence[str],
    serial: str,
    label: str,
    timeout: float,
    lock_dir: Optional[Path] = None,
    poll: float = 2.0,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> int:
    """Run ``cmd`` holding the device lock; return its exit status, or ``LOCK_BUSY_EXIT`` when
    the lock is still held after ``timeout`` seconds (the command then never runs).

    The lab opens the lock with ``os.open``, which is not inherited (PEP 446), so a Gradle
    daemon started by the command never keeps the lock alive after the run.
    """
    device_lock_path(serial, lock_dir)  # validates the serial before it becomes a file name
    _use_lab_package()
    from lab.usability.driver import DeviceBusy, device_lock

    deadline = clock() + timeout
    announced = None
    while True:
        try:
            with device_lock(serial, f"instrumented-tests {label}", lock_dir):
                return _run_to_completion(cmd)
        except DeviceBusy as exc:
            remaining = deadline - clock()
            if remaining <= 0:
                print(f"test device busy after waiting {timeout:.0f}s — {exc}", file=sys.stderr)
                return LOCK_BUSY_EXIT
            if str(exc) != announced:
                print(f"  waiting up to {remaining:.0f}s for the test device — {exc}", file=sys.stderr, flush=True)
                announced = str(exc)
            sleep(min(poll, remaining))


# ── CLI ───────────────────────────────────────────────────────────────────────


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p_serial = sub.add_parser("serial", help="print the test device serial")
    p_serial.add_argument("--designated", action="store_true",
                          help="fail unless the device was named explicitly (not just the only one attached)")
    sub.add_parser("status", help="show attached devices and which one is the test device")
    p_prep = sub.add_parser("prepare", help="configure the test device for instrumented tests")
    p_prep.add_argument("--serial")
    p_img = sub.add_parser("load-images", help="push fixture photos to /sdcard/PSTLab/")
    p_img.add_argument("--serial")
    p_img.add_argument("--sets", default="",
                       help="comma-separated set names (default: every set in spec.yaml + format-corpus)")
    p_lock = sub.add_parser("with-lock", help="run a command while holding the test device lock")
    p_lock.add_argument("--serial")
    p_lock.add_argument("--timeout", type=float, default=DEFAULT_LOCK_TIMEOUT,
                        help=f"seconds to wait for a held lock (default {DEFAULT_LOCK_TIMEOUT:.0f})")
    p_lock.add_argument("--label", default=Path.cwd().name,
                        help="who is running, written into the lock (default: the current directory's name)")
    p_lock.add_argument("cmd", nargs=argparse.REMAINDER, help="-- command [args...]")
    p_holder = sub.add_parser("lock-holder", help="print who holds the test device lock (exit 1 if free)")
    p_holder.add_argument("--serial")
    args = parser.parse_args(argv)

    if args.command == "status":
        code, listing = run(["adb", "devices", "-l"]) if shutil.which("adb") else (1, "adb not found")
        print(listing)
        serial, designated, reason = select()
        print(f"\ntest device: {serial or 'none'} ({reason})")
        if serial:
            print(f"device lock: {lock_holder(device_lock_path(serial)) or 'free'}")
        return 0 if serial else 1

    if getattr(args, "serial", None):
        serial, designated, reason = args.serial, True, "--serial"
    else:
        serial, designated, reason = select()
    if args.command == "serial":
        if serial and args.designated and not designated:
            print(f"{serial} is attached but not designated as the test device", file=sys.stderr)
            return 1
        if serial:
            print(serial)
            return 0
        print(reason, file=sys.stderr)
        return 1
    if not serial:
        print(f"no test device: {reason}", file=sys.stderr)
        return 1

    if args.command == "with-lock":
        cmd = args.cmd[1:] if args.cmd[:1] == ["--"] else args.cmd
        if not cmd:
            parser.error("with-lock needs a command after --")
        return with_lock(cmd, serial, args.label, args.timeout)

    if args.command == "lock-holder":
        held_by = lock_holder(device_lock_path(serial))
        print(held_by or f"device lock for {serial} is free", file=sys.stdout if held_by else sys.stderr)
        return 0 if held_by else 1

    if args.command == "prepare":
        if not may_reconfigure(serial, designated):
            print(f"  not preparing {serial}: it is not designated as the test device, and prepare "
                  f"changes persistent settings (keyguard, verifier, display). Set {SERIAL_PROPERTY} "
                  f"in products/android/local.properties or pass --serial.", file=sys.stderr)
            return 1
        return 1 if prepare(serial) else 0

    sets = [s.strip() for s in args.sets.split(",") if s.strip()]
    HOST_STAGING.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="device-images-", dir=HOST_STAGING) as tmp:
        staged = stage_images(sets, Path(tmp))
        if not staged:
            return 1
        return 1 if push_images(serial, staged) else 0


if __name__ == "__main__":
    sys.exit(main())
