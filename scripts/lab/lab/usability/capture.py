"""Run a product's usability scenarios on a device and write the walkthrough the panel reviews."""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

from ..android.adb import AdbClient
from ..paths import ensure_under, lab_tmp_root, repo_root
from .driver import (
    BaseDriver,
    ScenarioDriver,
    check_device_free,
    device_lock,
    get_driver,
    push_fixture,
    set_app_locale,
)

ANDROID_PRODUCTS = {"phototok", "android-desktop"}
DESKTOP_PRODUCTS = {"macos", "linux"}
SUPPORTED_PRODUCTS = ANDROID_PRODUCTS | DESKTOP_PRODUCTS


def scenario_file(product: str, root: Path | None = None) -> Path:
    return (root or repo_root()) / "scripts" / "lab" / "usability" / f"{product}.yaml"


def load_scenarios(product: str, root: Path | None = None) -> dict[str, Any]:
    data = yaml.safe_load(scenario_file(product, root).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not data.get("scenarios"):
        raise ValueError(f"no scenarios for {product}")
    return data


def _prepare_fixture(product: str, spec: dict[str, Any], adb: AdbClient | None = None) -> Path:
    from .scenes import generate_roll  # needs real Pillow; imported only when a fixture run starts

    remote_spec = str(spec.get("remote", "/tmp/pstlab/usability-trip"))
    if product in ANDROID_PRODUCTS:
        local = lab_tmp_root() / "usability" / Path(remote_spec).name
        ensure_under(local, lab_tmp_root())
        if not local.is_dir() or not any(local.iterdir()):
            generate_roll(local)
        if adb is not None:
            push_fixture(adb, local, remote_spec)
        return local
    else:
        local = Path(remote_spec)
        ensure_under(local, lab_tmp_root())
        local.mkdir(parents=True, exist_ok=True)
        if not any(local.iterdir()):
            generate_roll(local)
        return local


def _device_facts(
    product: str, adb: AdbClient | None = None, driver_type: str = "auto", app_id: str = ""
) -> dict[str, Any]:
    if driver_type == "mock":
        return {
            "platform": product,
            "mock": True,
            "size": "1440x900" if product in DESKTOP_PRODUCTS else "1080x1920",
        }
    if product in ANDROID_PRODUCTS and adb is not None:
        def prop(name: str) -> str:
            return adb.run("shell", "getprop", name).strip()

        return {
            "platform": "android",
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
    if product == "macos":
        import platform

        return {
            "platform": "macos",
            "os": f"macOS {platform.mac_ver()[0]}",
            "arch": platform.machine(),
            "size": "1440x900",
            "scale": 2.0,
        }
    if product == "linux":
        import platform

        return {
            "platform": "linux",
            "os": f"{platform.system()} {platform.release()}",
            "arch": platform.machine(),
            "size": "1280x800",
            "scale": 1.0,
        }
    return {"platform": product}


def _app_locale(adb: AdbClient, app_id: str) -> str:
    out = adb.run("shell", "cmd", "locale", "get-app-locales", app_id).strip()
    # "Locales for com.x for user 0 are [de-DE]"
    m = out[out.rfind("[") + 1 : out.rfind("]")] if "[" in out else ""
    return m.strip()


def _restore_file(serial: str) -> Path:
    return lab_tmp_root() / f"device-{serial}.restore.json"


def _original_font_scale(client: AdbClient, serial: str) -> str:
    """The font scale to put back. A walk that died before restoring (USB dropped) left its
    original in a file; reading the device then would return the walk's own 2.0."""
    path = _restore_file(serial)
    if path.is_file():
        try:
            return str(json.loads(path.read_text(encoding="utf-8"))["font_scale"])
        except (ValueError, KeyError):
            pass
    current = client.run("shell", "settings", "get", "system", "font_scale").strip()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"font_scale": current}), encoding="utf-8")
    return current


def _restore(client: AdbClient, app_id: str, old_scale: str | None, reset_locale: bool) -> bool:
    """Put the font scale and app language back; a device that dropped off USB must not hide
    the walk's own error, so a failed restore is reported, not raised."""
    import sys

    steps = []
    if old_scale is not None:
        steps.append(("font scale", lambda: client.run(
            "shell", "settings", "put", "system", "font_scale", old_scale if old_scale not in ("", "null") else "1.0")))
    if reset_locale:
        steps.append(("app language", lambda: set_app_locale(client, app_id, None)))
    ok = True
    for what, step in steps:
        try:
            step()
        except Exception as exc:  # noqa: BLE001
            ok = False
            print(f"warning: could not restore the {what} on {client.serial}: {exc}; the next walk restores it", file=sys.stderr)
    return ok


def variant_label(locale: str | None, font_scale: float | None) -> str:
    """`en@1.0`, `de-DE@2.0`: the key a persona's setup and the report use."""
    return f"{locale or 'en'}@{font_scale if font_scale is not None else 1.0:.1f}"


def run_capture(
    product: str,
    serial: str | None = None,
    out_dir: Path | str = ...,
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

    `locale` sets the app's own language (`cmd locale set-app-locales`, so the system picker keeps
    the device language); `font_scale` sets the system font scale. Both are restored afterwards
    and only apply to a live Android walk.
    """
    if product not in SUPPORTED_PRODUCTS:
        raise ValueError(f"unsupported product '{product}'; choices are {sorted(SUPPORTED_PRODUCTS)}")

    spec = load_scenarios(product)
    app_id = str(spec["app_id"])
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    client = None
    if product in ANDROID_PRODUCTS and driver_type != "mock" and driver is None:
        if not serial and not adb:
            raise ValueError(f"serial is required for Android product '{product}' on device")
        client = adb or AdbClient(serial=str(serial))

    if client is not None:
        with device_lock(client.serial, f"lab usability-capture {product}"):
            check_device_free(client, app_id)
            old_scale = _original_font_scale(client, client.serial)
            try:
                if font_scale is not None:
                    client.run("shell", "settings", "put", "system", "font_scale", f"{font_scale:g}")
                if locale:
                    set_app_locale(client, app_id, locale)
                # Portrait, locked: a phone walkthrough the reviewers can compare screen by screen.
                client.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
                client.run("shell", "settings", "put", "system", "user_rotation", "0")
                fixture_spec = spec.get("fixture", {})
                fixture_path = _prepare_fixture(product, fixture_spec, adb=client) if fixture_spec else None
                active_driver = get_driver(
                    product,
                    app_id,
                    out,
                    serial=serial,
                    adb=client,
                    fixture_dir=fixture_path,
                    driver_type=driver_type,
                    exec_fn=exec_fn,
                    app_locale=locale,
                )
                walkthrough = _execute_scenarios(spec, product, active_driver, out, only, client, driver_type)
                walkthrough["variant"] = variant_label(locale, font_scale)
                (out / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
                return walkthrough
            finally:
                if _restore(client, app_id, old_scale if font_scale is not None else None, bool(locale)):
                    _restore_file(client.serial).unlink(missing_ok=True)

    fixture_spec = spec.get("fixture", {})
    fixture_path = _prepare_fixture(product, fixture_spec) if fixture_spec else None
    if driver is not None:
        active_driver = driver
    else:
        active_driver = get_driver(
            product,
            app_id,
            out,
            serial=serial,
            adb=adb,
            fixture_dir=fixture_path,
            driver_type=driver_type,
            exec_fn=exec_fn,
            app_locale=locale,
        )
    walkthrough = _execute_scenarios(spec, product, active_driver, out, only, adb, driver_type)
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
        result = driver.run(scenario)
        results.append(result)
        if result["status"] != "pass":
            break  # later scenarios build on this one's end state

    walkthrough = {
        "product": product,
        "captured_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "duration_s": round(time.monotonic() - started, 1),
        "device": _device_facts(product, adb=client, driver_type=driver_type, app_id=str(spec["app_id"])),
        "scenarios": results,
    }
    (out_dir / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
    return walkthrough
