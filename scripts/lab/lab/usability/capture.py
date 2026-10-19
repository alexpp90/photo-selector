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
from .driver import ScenarioDriver, check_device_free, device_lock, push_fixture, set_app_locale


def scenario_file(product: str, root: Path | None = None) -> Path:
    return (root or repo_root()) / "scripts" / "lab" / "usability" / f"{product}.yaml"


def load_scenarios(product: str, root: Path | None = None) -> dict[str, Any]:
    data = yaml.safe_load(scenario_file(product, root).read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not data.get("scenarios"):
        raise ValueError(f"no scenarios for {product}")
    return data


def _prepare_fixture(adb: AdbClient, spec: dict[str, Any]) -> None:
    from .scenes import generate_roll  # needs real Pillow; imported only when a device run starts

    local = lab_tmp_root() / "usability" / Path(str(spec["remote"])).name
    ensure_under(local, lab_tmp_root())
    if not local.is_dir() or not any(local.iterdir()):
        generate_roll(local)
    push_fixture(adb, local, str(spec["remote"]))


def _device_facts(adb: AdbClient, app_id: str = "") -> dict[str, Any]:
    def prop(name: str) -> str:
        return adb.run("shell", "getprop", name).strip()

    return {
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
    serial: str,
    out_dir: Path,
    *,
    only: list[str] | None = None,
    adb: AdbClient | None = None,
    locale: str | None = None,
    font_scale: float | None = None,
) -> dict[str, Any]:
    """Execute every scenario (or `only`) in order; returns and writes `walkthrough.json`.

    `locale` sets the app's own language (`cmd locale set-app-locales`, so the system picker keeps
    the device language); `font_scale` sets the system font scale. Both are restored afterwards.
    """
    spec = load_scenarios(product)
    client = adb or AdbClient(serial=serial)
    app_id = str(spec["app_id"])
    with device_lock(serial, f"lab usability-capture {product}"):
        check_device_free(client, app_id)
        old_scale = _original_font_scale(client, serial)
        try:
            if font_scale is not None:
                client.run("shell", "settings", "put", "system", "font_scale", f"{font_scale:g}")
            if locale:
                set_app_locale(client, app_id, locale)
            walkthrough = _capture_locked(spec, product, client, out_dir, only, locale)
            walkthrough["variant"] = variant_label(locale, font_scale)
            (out_dir / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
            return walkthrough
        finally:
            if _restore(client, app_id, old_scale if font_scale is not None else None, bool(locale)):
                _restore_file(serial).unlink(missing_ok=True)


def _capture_locked(
    spec: dict[str, Any], product: str, client: AdbClient, out_dir: Path, only: list[str] | None,
    locale: str | None = None,
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)

    # Portrait, locked: a phone walkthrough the reviewers can compare screen by screen.
    client.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    client.run("shell", "settings", "put", "system", "user_rotation", "0")
    if spec.get("fixture", {}).get("kind") == "scenes":
        _prepare_fixture(client, spec["fixture"])

    driver = ScenarioDriver(client, str(spec["app_id"]), out_dir, app_locale=locale)
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
        "device": _device_facts(client, str(spec["app_id"])),
        "scenarios": results,
    }
    (out_dir / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
    return walkthrough
