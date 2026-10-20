"""Build the `.lab` APKs once per run and install them on each Android lab device."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Callable

from ..paths import repo_root
from .adb import AdbClient

# Gradle module and lab application id per Android product.
MODULES = {
    "phototok": (":phototok", "com.phototok.lab"),
    "android-desktop": (":android-desktop", "com.photoselectortoolbox.lab"),
}

RunFn = Callable[[list[str], Path], subprocess.CompletedProcess[str]]

# Written next to each APK by `build_lab_apks`: which tree it was built from and its hash.
STAMP_NAME = "lab-build.json"
# What the `.lab` APKs are built from: the Android tree without the modules' test sources.
APK_PATHSPEC = ("products/android", ":(exclude)products/android/*/tests/*")


def android_sdk_env() -> dict[str, str]:
    """Environment with `ANDROID_HOME` set when neither it nor `local.properties` provides one.

    Fresh worktrees and the systemd timer have no `local.properties`; the SDK is found from the
    `adb` on PATH (`<sdk>/platform-tools/adb`) or the Android Studio default `~/Android/Sdk`.
    """
    env = dict(os.environ)
    if env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT"):
        return env
    adb = shutil.which("adb")
    candidates = [Path(adb).resolve().parent.parent] if adb else []
    candidates.append(Path.home() / "Android" / "Sdk")
    for sdk in candidates:
        if (sdk / "platform-tools").is_dir():
            env["ANDROID_HOME"] = str(sdk)
            break
    return env


def _default_run(cmd: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, check=False, env=android_sdk_env())


def apk_path(product: str, root: Path | None = None) -> Path:
    """Expected location of the assembled `.lab` APK for `product`."""
    module = MODULES[product][0].lstrip(":")
    out = (root or repo_root()) / "products" / "android" / module / "build" / "outputs" / "apk" / "lab"
    matches = sorted(out.glob("*-lab.apk")) if out.is_dir() else []
    return matches[0] if matches else out / f"{module}-lab.apk"


def tree_fingerprint(root: Path | None = None, run: RunFn | None = None) -> str:
    """products/android as it is on disk (index, unstaged changes, untracked files), as one hash.

    Two trees with the same fingerprint build the same `.lab` APKs; the build stamp records it.
    A change outside what goes into the APK (docs, the lab, the modules' `tests/`, which hold the
    e2e flows) keeps the fingerprint.
    """
    r = root or repo_root()
    git = run or (lambda cmd, cwd: subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, check=False))
    head = git(["git", "ls-files", "-s", "--", *APK_PATHSPEC], r).stdout  # the index: committed + staged
    diff = git(["git", "diff", "--binary", "--", *APK_PATHSPEC], r).stdout  # unstaged changes
    untracked = git(["git", "ls-files", "--others", "--exclude-standard", "--", *APK_PATHSPEC], r).stdout
    h = hashlib.sha256()
    for part in (head, diff, untracked):
        h.update(part.encode("utf-8", errors="replace") + b"\0")
    for rel in sorted(untracked.split()):
        p = r / rel
        if p.is_file():
            h.update(p.read_bytes())
    return h.hexdigest()[:24]


def _file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_build_stamp(product: str, fingerprint: str, root: Path | None = None) -> None:
    apk = apk_path(product, root)
    stamp = {"fingerprint": fingerprint, "apk": apk.name, "sha256": _file_sha256(apk)}
    (apk.parent / STAMP_NAME).write_text(json.dumps(stamp, indent=2) + "\n", encoding="utf-8")


def read_build_stamp(product: str, root: Path | None = None) -> dict | None:
    try:
        return json.loads((apk_path(product, root).parent / STAMP_NAME).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def build_lab_apks(
    products: list[str],
    log_path: Path,
    *,
    root: Path | None = None,
    run: RunFn | None = None,
) -> tuple[bool, str | None]:
    """Run `./gradlew :<module>:assembleLab` for every requested Android product."""
    wanted = [p for p in products if p in MODULES]
    if not wanted:
        return True, None
    r = root or repo_root()
    android_root = r / "products" / "android"
    fingerprint = tree_fingerprint(r)  # before the build: a change made during it is not in the APK
    tasks = [f"{MODULES[p][0]}:assembleLab" for p in wanted]
    res = (run or _default_run)(["./gradlew", "--console=plain", *tasks], android_root)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(f"$ ./gradlew {' '.join(tasks)}\n{res.stdout}\n{res.stderr}", encoding="utf-8")
    if res.returncode != 0:
        return False, f"gradle assembleLab failed (rc={res.returncode}); see {log_path.name}"
    missing = [p for p in wanted if not apk_path(p, r).is_file()]
    if missing:
        return False, f"assembleLab succeeded but no APK for {', '.join(missing)}"
    for p in wanted:
        write_build_stamp(p, fingerprint, r)
    return True, None


def install_lab_apk(adb: AdbClient, product: str, *, user: int = 0, root: Path | None = None) -> None:
    """Install (or update in place) the `.lab` APK; the persisted SAF grant survives `-r`.

    A `.lab` build signed with another machine's debug key cannot be updated in place; only
    that lab package (never the user's Play install) is uninstalled and installed fresh.
    """
    apk = apk_path(product, root)
    try:
        adb.install(apk, user=user)
    except subprocess.CalledProcessError as exc:
        text = (exc.stdout or b"").decode(errors="replace") + (exc.stderr or b"").decode(errors="replace")
        if "UPDATE_INCOMPATIBLE" not in text:
            raise RuntimeError(f"adb install {apk.name} failed: {text.strip()[-300:]}") from exc
        adb.run("uninstall", MODULES[product][1], timeout=60)
        adb.install(apk, user=user)


def ensure_current_lab_apk(
    adb: AdbClient,
    product: str,
    *,
    user: int = 0,
    root: Path | None = None,
    fingerprint: str | None = None,
) -> tuple[bool, str | None]:
    """For `--no-build`: make sure the device runs this tree's `.lab` APK, or say why not.

    A `--no-build` pass on the Pixel Tablet tested the APK of the day before without a word
    (LAB-27, 2026-10-19). The last build's stamp must match the tree as it is now; the device's
    installed `base.apk` must hash to that build's APK, else that APK is installed (no Gradle).
    """
    app_id = MODULES[product][1]
    stamp = read_build_stamp(product, root)
    if stamp is None:
        return False, f"--no-build: no {app_id} built from this checkout yet; run without --no-build"
    current = fingerprint or tree_fingerprint(root)
    if stamp.get("fingerprint") != current:
        return False, (
            f"--no-build: the last {app_id} build is of {stamp.get('fingerprint')}, the tree is now "
            f"{current}; run without --no-build"
        )
    apk = apk_path(product, root)
    if not apk.is_file() or _file_sha256(apk) != stamp.get("sha256"):
        return False, f"--no-build: {apk.name} changed after its build stamp; run without --no-build"
    installed = adb.package_path(app_id, user=user)
    if installed and adb.sha256(installed) == stamp["sha256"]:
        return True, None
    try:
        install_lab_apk(adb, product, user=user, root=root)
    except Exception as exc:  # noqa: BLE001
        return False, f"--no-build: installing this tree's {apk.name} failed: {exc}"
    return True, None
