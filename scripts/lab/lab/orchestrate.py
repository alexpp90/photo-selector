"""End-to-end device lab orchestrator across Android, macOS, and Linux (C-60)."""

from __future__ import annotations

import contextlib
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

import yaml

from .android.adb import AdbClient
from .android.build import build_lab_apks, ensure_current_lab_apk, install_lab_apk
from .android.configs import record_device_state, reset_config
from .android.runner import run_android_journey
from .inventory import (
    ANDROID_PRODUCTS,
    AndroidDevice,
    Inventory,
    load_inventory,
    parse_inventory,
    resolve_android_device,
    select_android_devices,
)
from .linux.runner import preflight_linux, run_linux_journey
from .locks import DeviceBusy, device_lock_path, hub_lock_path, waiting_lock
from .macos.remote import preflight_remote_mac, trigger_remote_mac_run
from .macos.runner import default_app_binary, run_macos_journey
from .model import Finding
from .paths import ensure_under, new_run_dir, repo_root
from .report import generate_report

ALL_PRODUCTS = ["android-desktop", "phototok", "macos", "linux"]

# A second `lab.py run` waits this long for the first to finish with the hub's Android devices
# (LAB-24); a full Android pass takes 30-90 min.
HUB_WAIT_S = 3 * 3600.0
# How long a run waits for a device that an instrumented test run or a usability walk holds.
DEVICE_WAIT_S = 15 * 60.0


def _say(msg: str) -> None:
    print(f"[lab] {msg}", file=sys.stderr, flush=True)


def get_git_commit() -> str:
    try:
        res = subprocess.run(
            ["git", "-C", str(repo_root()), "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            check=False,
        )
        commit = (res.stdout or "").strip()
        if len(commit) >= 7:
            return commit
    except Exception:
        pass
    return "0000000"


def load_configs_for_product(product: str) -> dict[str, dict[str, Any]]:
    cfg_dir = repo_root() / "scripts" / "lab" / "configs"
    cfg_file = cfg_dir / ("android.yaml" if product in ("android-desktop", "phototok") else f"{product}.yaml")
    if not cfg_file.is_file():
        return {}
    data = yaml.safe_load(cfg_file.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def load_journeys_for_product(
    product: str,
    journey_filter: str | None = None,
) -> list[dict[str, Any]]:
    journeys: list[dict[str, Any]] = []
    j_dir = repo_root() / "scripts" / "lab" / "journeys"
    if not j_dir.is_dir():
        return journeys
    for ypath in sorted(j_dir.glob("*.yaml")):
        data = yaml.safe_load(ypath.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or data.get("product") != product:
            continue
        jid = str(data.get("id", ""))
        if journey_filter and jid != journey_filter and not jid.startswith(journey_filter):
            continue
        journeys.append(data)
    return journeys


def _record_not_run(
    run_dir: Path,
    product: str,
    journeys: list[dict[str, Any]],
    config_filter: str | None,
    reason: str,
    device: AndroidDevice | None = None,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for j in journeys:
        jid = str(j["id"])
        for base_cfg in j.get("configs", []):
            if config_filter and base_cfg != config_filter:
                continue
            if device is not None and not device.allows_config(base_cfg):
                continue
            cfg = device.label(base_cfg) if device is not None else base_cfg
            j_dir = run_dir / product / cfg / jid
            ensure_under(j_dir, run_dir)
            j_dir.mkdir(parents=True, exist_ok=True)
            res = {
                "id": jid,
                "product": product,
                "config": cfg,
                "status": "not-run",
                "reason": reason,
                "duration_s": 0.0,
                "checkpoints": [],
            }
            (j_dir / "result.json").write_text(json.dumps(res, indent=2) + "\n", encoding="utf-8")
            records.append(
                {
                    "product": product,
                    "config": cfg,
                    "journey": jid,
                    "result": "not-run",
                    "reason": reason,
                    "dir": f"{product}/{cfg}/{jid}",
                }
            )
    return records


def _load_devices_inventory(devices_yaml: Path | None = None) -> dict[str, Any]:
    path = devices_yaml or (repo_root() / "private" / "lab" / "devices.yaml")
    if not path.is_file():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def _run_android_product(
    product: str,
    journeys: list[dict[str, Any]],
    config_filter: str | None,
    run_dir: Path,
    inventory: Inventory,
    *,
    with_personal: bool,
    only_devices: list[str] | None,
    build: bool,
    build_state: dict[str, tuple[bool, str | None]],
    resolve_fn: Callable[[AndroidDevice], tuple[bool, str | None]] = resolve_android_device,
    journey_fn: Callable[..., tuple[str, list[Finding]]] = run_android_journey,
    lock_dir: Path | None = None,
    device_wait_s: float = DEVICE_WAIT_S,
    apk_fn: Callable[..., tuple[bool, str | None]] = ensure_current_lab_apk,
) -> tuple[list[dict[str, Any]], list[Finding], dict[str, Any]]:
    """Run every journey of one Android product on each selected, reachable device.

    Each device is driven under its device lock (shared with instrumented test runs and
    usability walks); a device still held after `device_wait_s` is not run.
    """
    records: list[dict[str, Any]] = []
    findings: list[Finding] = []
    devices = select_android_devices(inventory, product, with_personal=with_personal, only=only_devices)
    if not devices:
        reason = "no lab device for this product in devices.yaml"
        skipped_personal = [d.name for d in inventory.android if d.runs(product) and d.personal]
        if skipped_personal and not only_devices:
            reason += f" (personal devices skipped: {', '.join(skipped_personal)}; add --with-personal)"
        records.extend(_record_not_run(run_dir, product, journeys, config_filter, reason))
        return records, findings, {"status": "not-run", "reason": reason, "devices": {}}

    dev_meta: dict[str, Any] = {}
    for dev in devices:
        with contextlib.ExitStack() as held:
            _run_android_device(
                dev, product, journeys, config_filter, run_dir, held,
                build=build, build_state=build_state, resolve_fn=resolve_fn, journey_fn=journey_fn,
                lock_dir=lock_dir, device_wait_s=device_wait_s, apk_fn=apk_fn,
                records=records, findings=findings, dev_meta=dev_meta,
            )

    ran = [n for n, m in dev_meta.items() if m["status"] == "ran"]
    meta: dict[str, Any] = {"status": "ran" if ran else "not-run", "devices": dev_meta}
    if not ran:
        meta["reason"] = "; ".join(str(m.get("reason")) for m in dev_meta.values())
    return records, findings, meta


def _run_android_device(
    dev: AndroidDevice,
    product: str,
    journeys: list[dict[str, Any]],
    config_filter: str | None,
    run_dir: Path,
    held: contextlib.ExitStack,
    *,
    build: bool,
    build_state: dict[str, tuple[bool, str | None]],
    resolve_fn: Callable[[AndroidDevice], tuple[bool, str | None]],
    journey_fn: Callable[..., tuple[str, list[Finding]]],
    lock_dir: Path | None,
    device_wait_s: float,
    apk_fn: Callable[..., tuple[bool, str | None]],
    records: list[dict[str, Any]],
    findings: list[Finding],
    dev_meta: dict[str, Any],
) -> None:
    """One device's part of `_run_android_product`; locks taken go on `held`."""
    ok, reason = resolve_fn(dev)
    if ok:
        try:
            held.enter_context(
                waiting_lock(
                    device_lock_path(dev.serial, lock_dir),
                    f"lab run {run_dir.name} ({product})",
                    device_wait_s,
                    on_wait=lambda msg: _say(f"{dev.name}: {msg}"),
                )
            )
        except DeviceBusy as exc:
            ok, reason = False, f"{dev.name} busy: {exc}"
    adb = AdbClient(serial=dev.adb_serial or dev.serial)
    saved = None
    if ok and dev.personal:
        try:
            busy = adb.in_use_by()
        except Exception:  # noqa: BLE001 - an unreadable state is not proof of use
            busy = None
        if busy:
            ok, reason = False, (
                f"{dev.name} is in use ({busy} on screen); go to the home screen or lock it, then re-run"
            )
    if ok:
        try:
            saved = record_device_state(adb)
            adb.ensure_ready()
        except Exception as exc:  # noqa: BLE001 - any adb failure means "not run" for this device
            ok, reason = False, f"{dev.name}: {exc}"
    if ok and build:
        if "apks" not in build_state:
            build_state["apks"] = build_lab_apks(list(ANDROID_PRODUCTS), run_dir / "gradle-assembleLab.log")
        ok, reason = build_state["apks"]
    if ok and build:
        try:
            install_lab_apk(adb, product, user=dev.lab_user_id)
        except Exception as exc:  # noqa: BLE001
            ok, reason = False, f"{dev.name}: install failed: {exc}"
    if ok and not build:
        ok, reason = apk_fn(adb, product, user=dev.lab_user_id)
        if not ok:
            reason = f"{dev.name}: {reason}"
    if not ok:
        if saved is not None:
            _safe_reset(adb, saved)
        dev_meta[dev.name] = {"status": "not-run", "reason": reason, "model": dev.model}
        records.extend(_record_not_run(run_dir, product, journeys, config_filter, reason or "unreachable", dev))
        return

    dev_meta[dev.name] = {"status": "ran", "model": dev.model, "adb": dev.adb_serial}
    try:
        for j in journeys:
            jid = str(j["id"])
            for cfg_name in j.get("configs", []):
                if config_filter and cfg_name != config_filter:
                    continue
                if not dev.allows_config(cfg_name):
                    continue
                label = dev.label(cfg_name)
                try:
                    status, f_list = journey_fn(
                        manifest=j,
                        config_name=cfg_name,
                        run_dir=run_dir,
                        adb=adb,
                        user_id=dev.lab_user_id,
                        label=label,
                    )
                    err = _result_reason(run_dir / product / label / jid)
                except Exception as exc:  # noqa: BLE001 - one broken journey must not end the run
                    status, f_list, err = "error", [], f"{type(exc).__name__}: {exc}"
                    _write_error_result(run_dir / product / label / jid, err)
                findings.extend(f_list)
                rec = {
                    "product": product,
                    "config": label,
                    "device": dev.name,
                    "journey": jid,
                    "result": status,
                    "dir": f"{product}/{label}/{jid}",
                }
                if err:
                    rec["reason"] = err
                records.append(rec)
    finally:
        if saved is not None:
            _safe_reset(adb, saved)


def _load_findings(path: Path) -> list[Finding]:
    """Findings another host wrote (the Mac's `findings.det.json`), or none."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return [Finding.from_dict(d) for d in data if isinstance(d, dict)]


def _result_reason(j_dir: Path) -> str | None:
    try:
        return json.loads((j_dir / "result.json").read_text(encoding="utf-8")).get("reason")
    except (OSError, ValueError):
        return None


def _safe_reset(adb: AdbClient, saved: Any) -> None:
    try:
        reset_config(adb, saved)
    except Exception as exc:  # noqa: BLE001
        print(f"[lab] warning: could not restore settings on {adb.serial}: {exc}", file=sys.stderr)


def _write_error_result(j_dir: Path, err: str) -> None:
    j_dir.mkdir(parents=True, exist_ok=True)
    (j_dir / "result.json").write_text(
        json.dumps({"status": "error", "reason": err, "findings": 0}, indent=2) + "\n", encoding="utf-8"
    )


def run_lab(
    products: list[str],
    journey_filter: str | None = None,
    config_filter: str | None = None,
    out_dir: Path | None = None,
    installed: bool = False,
    devices_inventory: dict[str, Any] | None = None,
    preflight_overrides: dict[str, tuple[bool, str | None]] | None = None,
    runner_overrides: dict[str, Callable[..., tuple[str, list[Finding]]]] | None = None,
    with_personal: bool = False,
    only_devices: list[str] | None = None,
    build: bool = True,
    lock_dir: Path | None = None,
    hub_wait_s: float = HUB_WAIT_S,
    device_wait_s: float = DEVICE_WAIT_S,
) -> Path:
    """Orchestrate a lab run across the requested products and generate report.html.

    Android products fan out over every selected, reachable inventory device; their results
    live under `<product>/<config>@<device>/<journey>/`. Personal devices join only with
    `with_personal=True` or when named in `only_devices`. Android products run under the hub
    lock: a second run on the same host waits up to `hub_wait_s` for the first (LAB-24).
    """
    with contextlib.ExitStack() as held:
        return _run_lab(
            products, journey_filter, config_filter, out_dir, installed, devices_inventory,
            preflight_overrides, runner_overrides, with_personal, only_devices, build,
            held=held, lock_dir=lock_dir, hub_wait_s=hub_wait_s, device_wait_s=device_wait_s,
        )


def _run_lab(
    products: list[str],
    journey_filter: str | None,
    config_filter: str | None,
    out_dir: Path | None,
    installed: bool,
    devices_inventory: dict[str, Any] | None,
    preflight_overrides: dict[str, tuple[bool, str | None]] | None,
    runner_overrides: dict[str, Callable[..., tuple[str, list[Finding]]]] | None,
    with_personal: bool,
    only_devices: list[str] | None,
    build: bool,
    *,
    held: contextlib.ExitStack,
    lock_dir: Path | None,
    hub_wait_s: float,
    device_wait_s: float,
) -> Path:
    now = dt.datetime.now(dt.timezone.utc)
    commit = get_git_commit()

    if out_dir is None:
        run_dir = new_run_dir(commit, now=now)
    else:
        run_dir = out_dir.resolve()
        run_dir.mkdir(parents=True, exist_ok=True)

    raw_inventory = devices_inventory if devices_inventory is not None else _load_devices_inventory()
    inventory = parse_inventory(raw_inventory)
    build_state: dict[str, tuple[bool, str | None]] = {}
    remote_mac: tuple[bool, str | None] | None = None
    mac_opted_in = with_personal or bool(only_devices and "mac" in only_devices)
    all_findings: list[Finding] = []
    products_meta: dict[str, Any] = {}
    journey_records: list[dict[str, Any]] = []
    hub: dict[str, str | None] = {}

    def hub_busy() -> str | None:
        """Take the hub lock once, for the rest of the run; the reason if it stays held."""
        if "state" not in hub:
            try:
                held.enter_context(
                    waiting_lock(
                        hub_lock_path(lock_dir), f"lab run {run_dir.name}", hub_wait_s,
                        on_wait=lambda msg: _say(f"another lab run drives this hub's Android devices; {msg}"),
                    )
                )
                hub["state"] = None
            except DeviceBusy as exc:
                hub["state"] = f"another lab run drives this hub's Android devices: {exc}"
        return hub["state"]

    for product in products:
        journeys = load_journeys_for_product(product, journey_filter)
        configs_map = load_configs_for_product(product)
        if not journeys:
            continue

        if preflight_overrides and product in preflight_overrides:
            ok, reason = preflight_overrides[product]
        elif product in ANDROID_PRODUCTS and hub_busy():
            ok, reason = False, hub_busy()
        elif product in ANDROID_PRODUCTS:
            records, a_findings, meta = _run_android_product(
                product,
                journeys,
                config_filter,
                run_dir,
                inventory,
                with_personal=with_personal,
                only_devices=only_devices,
                build=build,
                build_state=build_state,
                lock_dir=lock_dir,
                device_wait_s=device_wait_s,
            )
            journey_records.extend(records)
            all_findings.extend(a_findings)
            products_meta[product] = meta
            continue
        elif product == "macos":
            if sys.platform == "darwin":
                bin_path = default_app_binary()
                if bin_path.is_file():
                    ok, reason = True, None
                else:
                    ok, reason = False, f"macOS app binary not built at {bin_path}"
            else:
                mac_cfg = raw_inventory.get("mac", {})
                host = str(mac_cfg.get("host", ""))
                if inventory.mac and inventory.mac.personal and not mac_opted_in:
                    host = ""
                user = str(mac_cfg.get("user", "pst-lab"))
                if not host:
                    ok, reason = False, (
                        "Mac is a personal device; add --with-personal"
                        if inventory.mac and inventory.mac.personal and not mac_opted_in
                        else "Mac host not configured in devices.yaml"
                    )
                else:
                    ok, reason = preflight_remote_mac(host, user=user)
        elif product == "linux":
            ok, reason = preflight_linux()
        else:
            ok, reason = False, f"Unknown product '{product}'"

        if not ok:
            products_meta[product] = {"status": "not-run", "reason": reason or "preflight failed"}
            journey_records.extend(
                _record_not_run(run_dir, product, journeys, config_filter, reason or "preflight failed")
            )
            continue

        products_meta[product] = {"status": "ran"}
        for j in journeys:
            jid = str(j["id"])
            for cfg_name in j.get("configs", []):
                if config_filter and cfg_name != config_filter:
                    continue
                cfg_spec = configs_map.get(cfg_name, {})

                if runner_overrides and product in runner_overrides:
                    status, f_list = runner_overrides[product](j, cfg_name, cfg_spec, run_dir)
                    all_findings.extend(f_list)
                elif product == "macos":
                    if sys.platform == "darwin":
                        status, f_list = run_macos_journey(j, cfg_name, cfg_spec, run_dir)
                        all_findings.extend(f_list)
                    else:
                        # The Mac runs every macOS journey in one go; trigger it once, then
                        # read each journey's own result from the synced directory.
                        if remote_mac is None:
                            mac_cfg = raw_inventory.get("mac", {})
                            remote_mac = trigger_remote_mac_run(
                                host=str(mac_cfg.get("host", "")),
                                run_id=run_dir.name,
                                commit=commit,
                                journeys=[str(x["id"]) for x in journeys],
                                configs=sorted({c for x in journeys for c in x.get("configs", [])}),
                                local_run_dir=run_dir,
                                user=str(mac_cfg.get("user", "pst-lab")),
                            )
                            if remote_mac[0]:
                                all_findings.extend(_load_findings(run_dir / "macos" / "findings.det.json"))
                        ok_rem, rem_reason = remote_mac
                        res_path = run_dir / "macos" / cfg_name / jid / "result.json"
                        if ok_rem and res_path.is_file():
                            status = str(json.loads(res_path.read_text(encoding="utf-8")).get("status", "error"))
                        else:
                            status = "not-run"
                elif product == "linux":
                    status, f_list = run_linux_journey(
                        j,
                        cfg_name,
                        cfg_spec,
                        run_dir,
                        installed=installed,
                    )
                    all_findings.extend(f_list)
                else:
                    status = "not-run"

                journey_records.append(
                    {
                        "product": product,
                        "config": cfg_name,
                        "journey": jid,
                        "result": status,
                        "dir": f"{product}/{cfg_name}/{jid}",
                    }
                )

    run_meta = {
        "started": now.isoformat(),
        "finished": dt.datetime.now(dt.timezone.utc).isoformat(),
        "commit": commit,
        "products": products_meta,
        "tools": {"python": sys.version.split()[0]},
        "journeys": journey_records,
    }
    (run_dir / "run.json").write_text(json.dumps(run_meta, indent=2) + "\n", encoding="utf-8")
    (run_dir / "findings.det.json").write_text(
        json.dumps([f.to_dict() for f in all_findings], indent=2) + "\n",
        encoding="utf-8",
    )

    _, html_path = generate_report(run_dir)
    print(str(html_path))
    return run_dir
