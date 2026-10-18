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
from .driver import ScenarioDriver, check_device_free, device_lock, push_fixture


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


def _device_facts(adb: AdbClient) -> dict[str, Any]:
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
    }


def run_capture(
    product: str,
    serial: str,
    out_dir: Path,
    *,
    only: list[str] | None = None,
    adb: AdbClient | None = None,
) -> dict[str, Any]:
    """Execute every scenario (or `only`) in order; returns and writes `walkthrough.json`."""
    spec = load_scenarios(product)
    client = adb or AdbClient(serial=serial)
    with device_lock(serial, f"lab usability-capture {product}"):
        check_device_free(client, str(spec["app_id"]))
        return _capture_locked(spec, product, client, out_dir, only)


def _capture_locked(
    spec: dict[str, Any], product: str, client: AdbClient, out_dir: Path, only: list[str] | None
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)

    # Portrait, locked: a phone walkthrough the reviewers can compare screen by screen.
    client.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    client.run("shell", "settings", "put", "system", "user_rotation", "0")
    if spec.get("fixture", {}).get("kind") == "scenes":
        _prepare_fixture(client, spec["fixture"])

    driver = ScenarioDriver(client, str(spec["app_id"]), out_dir)
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
        "device": _device_facts(client),
        "scenarios": results,
    }
    (out_dir / "walkthrough.json").write_text(json.dumps(walkthrough, indent=2), encoding="utf-8")
    return walkthrough
