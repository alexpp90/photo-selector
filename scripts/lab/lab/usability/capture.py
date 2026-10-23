"""Run a product's usability scenarios on a device and write the walkthrough the panel reviews."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

import yaml

from ..android.adb import AdbClient
from ..paths import ensure_under, lab_tmp_root, repo_root
from .driver import (
    BaseDriver,
    check_device_free,
    device_lock,
    get_driver,
    push_fixture,
    set_app_locale,
)

ANDROID_PRODUCTS = {"phototok", "android-desktop"}
DEVICE_KIND = {"phototok": "phone", "android-desktop": "tablet", "macos": "Mac", "linux": "Linux desktop"}
DESKTOP_PRODUCTS = {"macos", "linux"}
TRANSLATED_PRODUCTS = {"phototok"}  # only Jackpix ships translations (res/values-de)
SUPPORTED_PRODUCTS = ANDROID_PRODUCTS | DESKTOP_PRODUCTS


def scenario_file(product: str, root: Path | None = None) -> Path:
    return (root or repo_root()) / "scripts" / "lab" / "usability" / f"{product}.yaml"


def load_scenarios(product: str, root: Path | None = None) -> dict[str, Any]:
    data = yaml.safe_load(scenario_file(product, root).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not data.get("scenarios"):
        raise ValueError(f"no scenarios for {product}")
    return data



def master_roll() -> Path:
    """The generated camera roll, regenerated whenever its recipe changes.

    A roll is only reused while its recipe stamp matches: an old roll kept because its folder
    was not empty is a different test than the one the scenarios were written for.
    """
    from .scenes import generate_roll, roll_signature  # needs real Pillow; imported only for a real run

    local = ensure_under(lab_tmp_root() / "usability" / "usability-trip", lab_tmp_root())
    stamp = local.parent / "usability-trip.roll.json"
    want = roll_signature()
    try:
        have = json.loads(stamp.read_text(encoding="utf-8")).get("signature")
    except (OSError, ValueError):
        have = None
    if have != want or not local.is_dir() or not any(local.iterdir()):
        shutil.rmtree(local, ignore_errors=True)
        generate_roll(local)
        stamp.write_text(json.dumps({"signature": want}), encoding="utf-8")
    return local


def desktop_working_copy(master: Path, run_tag: str) -> Path:
    """A fresh copy of the roll for one desktop walk: the apps move and trash files in it."""
    base = ensure_under(lab_tmp_root() / f"usability-work-{run_tag}", lab_tmp_root())
    shutil.rmtree(base, ignore_errors=True)
    work = base / master.name
    shutil.copytree(master, work)
    return work


def _device_facts(
    product: str, adb: AdbClient | None = None, driver_type: str = "auto", app_id: str = ""
) -> dict[str, Any]:
    if driver_type == "mock":
        return {"platform": product, "mock": True, "kind": DEVICE_KIND[product]}
    if product in ANDROID_PRODUCTS and adb is not None:
        def prop(name: str) -> str:
            return adb.run("shell", "getprop", name).strip()

        return {
            "platform": "android",
            "kind": DEVICE_KIND[product],
            "serial": adb.serial,
            "model": prop("ro.product.model"),
            "android": prop("ro.build.version.release"),
            "sdk": prop("ro.build.version.sdk"),
            "size": adb.run("shell", "wm", "size").strip().split(":")[-1].strip(),
            "density": adb.density(),
            "font_scale": adb.run("shell", "settings", "get", "system", "font_scale").strip(),
            "system_locale": prop("persist.sys.locale") or prop("ro.product.locale"),
            "app_locale": _app_locale(adb, app_id) if app_id else "",
        }
    import platform

    facts: dict[str, Any] = {"platform": product, "kind": DEVICE_KIND[product], "arch": platform.machine()}
    facts["os"] = f"macOS {platform.mac_ver()[0]}" if product == "macos" else f"{platform.system()} {platform.release()}"
    return facts


def _window_size(walkthrough: dict[str, Any], out_dir: Path) -> str | None:
    """`WxH` of the first desktop checkpoint's window, as its tree recorded it."""
    for sc in walkthrough["scenarios"]:
        for s in sc["screens"]:
            tree = out_dir / sc["scenario"] / (Path(s["image"]).stem + ".tree.json")
            try:
                win = json.loads(tree.read_text(encoding="utf-8")).get("window") or {}
            except (OSError, ValueError):
                continue
            if win.get("w") and win.get("h"):
                return f"{int(win['w'])}x{int(win['h'])} {win.get('unit', '')}".strip()
    return None


def _app_locale(adb: AdbClient, app_id: str) -> str:
    try:
        out = adb.run("shell", "cmd", "locale", "get-app-locales", app_id).strip()
    except subprocess.CalledProcessError:
        return ""  # Android < 13 has no per-app languages: the app follows the system's
    # "Locales for com.x for user 0 are [de-DE]"
    m = out[out.rfind("[") + 1 : out.rfind("]")] if "[" in out else ""
    return m.strip()




def _restore_file(serial: str) -> Path:
    return lab_tmp_root() / f"device-{serial}.restore.json"


_SAVED = (("font_scale", "system", "font_scale"), ("user_rotation", "system", "user_rotation"),
          ("accelerometer_rotation", "system", "accelerometer_rotation"))


def _original_settings(client: AdbClient, serial: str) -> dict[str, str]:
    """The settings to put back. A walk that died before restoring (USB dropped) left its
    originals in a file; reading the device then would return the walk's own values."""
    path = _restore_file(serial)
    saved: dict[str, str] = {}
    if path.is_file():
        try:
            saved = {k: str(v) for k, v in json.loads(path.read_text(encoding="utf-8")).items()}
        except ValueError:
            saved = {}
    missing = [s for s in _SAVED if s[0] not in saved]
    for key, ns, name in missing:
        saved[key] = client.run("shell", "settings", "get", ns, name).strip()
    if missing:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(saved), encoding="utf-8")
    return saved


def _original_font_scale(client: AdbClient, serial: str) -> str:
    return _original_settings(client, serial)["font_scale"]


def _restore(client: AdbClient, app_id: str, saved: dict[str, str], *, font: bool, rotation: bool,
             reset_locale: bool) -> bool:
    """Put back what the walk changed; a device that dropped off USB must not hide the walk's
    own error, so a failed restore is reported, not raised."""
    import sys

    def put(name: str, value: str, default: str) -> None:
        client.run("shell", "settings", "put", "system", name, value if value not in ("", "null") else default)

    steps = []
    if font:
        steps.append(("font scale", lambda: put("font_scale", saved.get("font_scale", ""), "1.0")))
    if rotation:
        steps.append(("rotation", lambda: (put("user_rotation", saved.get("user_rotation", ""), "0"),
                                           put("accelerometer_rotation", saved.get("accelerometer_rotation", ""), "1"))))
    if reset_locale:
        steps.append(("app language", lambda: set_app_locale(client, app_id, None)))
    ok = True
    for what, step in steps:
        try:
            step()
        except Exception as exc:  # noqa: BLE001 - reported below; the walk's own result must survive
            ok = False
            print(f"warning: could not restore the {what} on {client.serial}: {exc}; the next walk restores it", file=sys.stderr)
    return ok


def variant_label(locale: str | None, font_scale: float | None) -> str:
    """`en@1.0`, `de-DE@2.0`: the key a persona's setup and the report use."""
    return f"{locale or 'en'}@{font_scale if font_scale is not None else 1.0:.1f}"


def run_capture(
    product: str,
    serial: str | None = None,
    out_dir: Path | str | None = None,
    *,
    only: list[str] | None = None,
    adb: AdbClient | None = None,
    locale: str | None = None,
    font_scale: float | None = None,
    driver: BaseDriver | None = None,
    driver_type: str = "auto",
    exec_fn: Any | None = None,
) -> dict[str, Any]:
    """Execute every scenario (or `only`) in order; returns and writes `walkthrough.json`.

    Android: `locale` sets the app's own language (`cmd locale set-app-locales`, so the system
    picker keeps the device language), `font_scale` the system font scale, and the scenario
    file's `orientation` the rotation; all are restored afterwards. Linux: `font_scale` is the
    session's text scaling. Lensjet has neither a translation nor text scaling, so a variant
    there would only relabel the same screenshots and is refused.
    """
    if product not in SUPPORTED_PRODUCTS:
        raise ValueError(f"unsupported product '{product}'; choices are {sorted(SUPPORTED_PRODUCTS)}")
    if out_dir is None:
        raise ValueError("run_capture needs an out_dir")
    spec = load_scenarios(product)
    app_id = str(spec["app_id"])
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    mock = driver_type == "mock" or driver is not None
    # A variant the app cannot show would only relabel the same screenshots for a persona.
    if product == "macos" and (locale or font_scale is not None) and not mock:
        raise ValueError("Lensjet has no translation and no text scaling: capture it without --locale/--font-scale")
    if locale and product not in TRANSLATED_PRODUCTS and not mock:
        raise ValueError(f"{product} is English-only: capture it without --locale")

    if product in ANDROID_PRODUCTS and not mock:
        if not serial and adb is None:
            raise ValueError(f"serial is required for Android product '{product}' on device")
        client = adb or AdbClient(serial=str(serial))
        return _capture_android(spec, product, client, out, only, locale, font_scale, exec_fn)

    active = driver
    work: Path | None = None
    if active is None:
        fixture = None
        if not mock and spec.get("fixture"):
            work = desktop_working_copy(master_roll(), f"{product}-{os.getpid()}")
            fixture = work
        active = get_driver(product, app_id, out, fixture_dir=fixture, driver_type=driver_type, exec_fn=exec_fn,
                            text_scaling=font_scale)
    try:
        walkthrough = _execute_scenarios(spec, product, active, out, only, None, "mock" if mock else driver_type)
    finally:
        if work is not None:
            shutil.rmtree(work.parent, ignore_errors=True)
    if product in DESKTOP_PRODUCTS and not mock:
        walkthrough["device"]["size"] = _window_size(walkthrough, out) or ""
        if product == "linux" and font_scale is not None:
            walkthrough["device"]["font_scale"] = f"{font_scale:g}"
    walkthrough["variant"] = variant_label(locale, font_scale)
    (out / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
    return walkthrough


@contextmanager
def android_session(client: AdbClient, spec: dict[str, Any], product: str, *, locale: str | None = None,
                    font_scale: float | None = None, purpose: str = "usability-capture") -> Iterator[None]:
    """Own the device for a walk: lock, refuse a busy device, set the walk's language, font and
    rotation, push a fresh fixture, and put every setting back afterwards."""
    from ..android.configs import set_verified_rotation

    app_id = str(spec["app_id"])
    orientation = spec.get("orientation")
    with device_lock(client.serial, f"lab {purpose} {product}"):
        check_device_free(client, app_id)
        saved = _original_settings(client, client.serial)
        try:
            if font_scale is not None:
                client.run("shell", "settings", "put", "system", "font_scale", f"{font_scale:g}")
            if locale:
                set_app_locale(client, app_id, locale)
            if orientation:
                # Phones are walked in portrait, tablets in landscape, checked on the display.
                set_verified_rotation(client, str(orientation))
            if spec.get("fixture"):
                push_fixture(client, master_roll(), str(spec["fixture"]["remote"]))
            yield
        finally:
            if _restore(client, app_id, saved, font=font_scale is not None, rotation=bool(orientation),
                        reset_locale=bool(locale)):
                _restore_file(client.serial).unlink(missing_ok=True)


def _capture_android(spec: dict[str, Any], product: str, client: AdbClient, out: Path, only: list[str] | None,
                     locale: str | None, font_scale: float | None, exec_fn: Any | None) -> dict[str, Any]:
    app_id = str(spec["app_id"])
    with android_session(client, spec, product, locale=locale, font_scale=font_scale):
        driver = get_driver(product, app_id, out, adb=client, exec_fn=exec_fn, app_locale=locale)
        walkthrough = _execute_scenarios(spec, product, driver, out, only, client, "auto")
        walkthrough["variant"] = variant_label(locale, font_scale)
        (out / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
        return walkthrough


def _execute_scenarios(
    spec: dict[str, Any],
    product: str,
    driver: BaseDriver,
    out_dir: Path,
    only: list[str] | None,
    client: AdbClient | None,
    driver_type: str,
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    results: list[dict[str, Any]] = []
    started = time.monotonic()
    for scenario in spec["scenarios"]:
        if only and scenario["id"] not in only:
            continue
        driver.out_dir = out_dir / scenario["id"]
        driver.screens = []
        driver._actions = []
        result = driver.run(scenario)
        results.append(result)
        if result["status"] != "pass" and product in ANDROID_PRODUCTS:
            break  # one app session: later scenarios build on this one's end state

    walkthrough = {
        "product": product,
        "captured_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "duration_s": round(time.monotonic() - started, 1),
        "device": _device_facts(product, adb=client, driver_type=driver_type, app_id=str(spec["app_id"])),
        "scenarios": results,
    }
    (out_dir / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
    return walkthrough
