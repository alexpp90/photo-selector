"""macOS Desktop journey runner (C-31)."""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any, Callable

from ..fixtures import generate_fixtures
from ..fs_oracle import evaluate_fs_oracle, snapshot_directory
from ..layout_lint import lint, load_rules
from ..logscan import scan_macos_crash_reports
from ..model import Finding, Tree
from ..paths import ensure_under, lab_tmp_root, repo_root


def default_app_binary(root: Path | None = None) -> Path:
    r = root or repo_root()
    base = r / "products" / "macos-desktop" / ".build"
    # Only builds that contain the UI test hooks can run a journey: the `--test-hooks` build
    # (`.build/hooks`) and debug builds. A distributable `.build/app` bundle rejects the flags.
    candidates = [
        base / "hooks" / "app" / "PhotoSelector.app" / "Contents" / "MacOS" / "PhotoSelectorApp",
        base / "hooks" / "release" / "PhotoSelectorApp",
        base / "debug" / "PhotoSelectorApp",
        base / "out" / "Products" / "Release" / "PhotoSelectorApp",
        base / "out" / "Products" / "Debug" / "PhotoSelectorApp",
    ]
    for cand in candidates:
        if cand.is_file():
            return cand
    return candidates[0]


def run_macos_journey(
    journey_manifest: dict[str, Any],
    config_name: str,
    config_spec: dict[str, Any],
    run_dir: Path,
    fixture_base_dir: Path | None = None,
    app_binary: Path | None = None,
    exec_fn: Callable[[list[str], dict[str, str]], subprocess.CompletedProcess[str]] | None = None,
) -> tuple[str, list[Finding]]:
    """Run a macOS journey scenario and collect checkpoints + oracle findings."""
    product = "macos"
    r = repo_root()
    jid = str(journey_manifest["id"])
    fset = str(journey_manifest.get("fixture") or journey_manifest.get("fixture_set") or "cull-basic")
    j_dir = run_dir / product / config_name / jid
    ensure_under(j_dir, run_dir)
    j_dir.mkdir(parents=True, exist_ok=True)

    fbase = (fixture_base_dir or lab_tmp_root()).resolve()
    fbase.mkdir(parents=True, exist_ok=True)
    work_fixture = fbase / f"work_{jid}_{config_name}" / fset
    ensure_under(work_fixture, fbase)
    generate_fixtures(fset, work_fixture, root=r)

    initial_fs = snapshot_directory(work_fixture, fbase)
    (j_dir / "00_initial.fs.json").write_text(
        json.dumps(initial_fs, indent=2) + "\n",
        encoding="utf-8",
    )

    scenario_rel = None
    for step in journey_manifest.get("steps", []):
        if "scenario" in step:
            scenario_rel = str(step["scenario"])
            break
    if not scenario_rel:
        scenario_rel = f"products/macos-desktop/tests/e2e/{jid}.scenario.json"

    scenario_src = r / scenario_rel
    scenario_data = json.loads(scenario_src.read_text(encoding="utf-8"))
    if "size" in config_spec:
        m = re.match(r"^(\d+)x(\d+)$", str(config_spec["size"]))
        if m:
            scenario_data["window"] = {"w": int(m.group(1)), "h": int(m.group(2))}

    scenario_tmp = j_dir / "scenario.effective.json"
    scenario_tmp.write_text(json.dumps(scenario_data, indent=2) + "\n", encoding="utf-8")

    binary = app_binary or default_app_binary(r)
    cmd = [
        str(binary),
        "--open",
        str(work_fixture),
        "--ui-scenario",
        str(scenario_tmp),
        "--artifacts",
        str(j_dir),
    ]
    if config_spec.get("dark"):
        cmd.extend(["-AppleInterfaceStyle", "Dark"])

    env = dict(os.environ)
    started_ts = time.time()
    t0 = time.monotonic()
    if exec_fn is not None:
        proc = exec_fn(cmd, env)
    else:
        proc = subprocess.run(cmd, env=env, capture_output=True, text=True, check=False)
    duration_s = round(time.monotonic() - t0, 2)

    log_text = (proc.stdout or "") + "\n" + (proc.stderr or "")
    (j_dir / "journey.log").write_text(log_text, encoding="utf-8")

    after_fs = snapshot_directory(work_fixture, fbase)
    checkpoints: list[str] = []
    for png in sorted(j_dir.glob("[0-9][0-9]_*.png")):
        m = re.match(r"^(\d{2})_(.+)\.png$", png.name)
        if not m:
            continue
        prefix, cp_name = m.group(1), m.group(2)
        checkpoints.append(cp_name)
        fs_path = j_dir / f"{prefix}_{cp_name}.fs.json"
        if not fs_path.exists():
            fs_path.write_text(json.dumps(after_fs, indent=2) + "\n", encoding="utf-8")

    status = "pass" if proc.returncode == 0 else "flow-broken"
    rules_cfg = load_rules(product)
    layout_expect_all = journey_manifest.get("layout_expect", {})
    fs_expect_all = journey_manifest.get("fs_expect", {})
    tally_expect_all = journey_manifest.get("tally_expect", {})

    findings: list[Finding] = []
    for idx, cp_name in enumerate(checkpoints, start=1):
        prefix = f"{idx:02d}"
        rel_shot = f"{product}/{config_name}/{jid}/{prefix}_{cp_name}.png"
        tree_p = j_dir / f"{prefix}_{cp_name}.tree.json"
        fs_p = j_dir / f"{prefix}_{cp_name}.fs.json"
        state_p = j_dir / f"{prefix}_{cp_name}.state.json"

        if tree_p.is_file():
            tree_obj = Tree.from_dict(json.loads(tree_p.read_text(encoding="utf-8")))
            findings.extend(
                lint(
                    tree_obj,
                    rules_cfg,
                    layout_expect_all.get(cp_name),
                    product=product,
                    config=config_name,
                    journey=jid,
                    checkpoint=cp_name,
                    evidence=[rel_shot],
                )
            )
        if fs_p.is_file() and cp_name in fs_expect_all:
            cur_fs = json.loads(fs_p.read_text(encoding="utf-8"))
            findings.extend(
                evaluate_fs_oracle(
                    cur_fs,
                    fs_expect_all[cp_name],
                    initial_fs=initial_fs,
                    product=product,
                    config=config_name,
                    journey=jid,
                    checkpoint=cp_name,
                    evidence=[rel_shot],
                )
            )
        if state_p.is_file() and cp_name in tally_expect_all:
            state_data = json.loads(state_p.read_text(encoding="utf-8"))
            tally = state_data.get("tally", {})
            exp_tally = tally_expect_all[cp_name]
            for k, exp_v in exp_tally.items():
                if tally.get(k) != exp_v:
                    fid = Finding.make_id(product, jid, cp_name, "fs-tally-mismatch", [str(k)])
                    findings.append(
                        Finding(
                            id=fid,
                            source="fs-oracle",
                            product=product,
                            config=config_name,
                            journey=jid,
                            checkpoint=cp_name,
                            rule="fs-tally-mismatch",
                            severity="blocker",
                            confidence="high",
                            region=None,
                            message=f"Tally {k!r} is {tally.get(k)}, expected {exp_v}",
                            evidence=[rel_shot],
                        )
                    )

    diag_dir = Path.home() / "Library" / "Logs" / "DiagnosticReports"
    findings.extend(
        scan_macos_crash_reports(diag_dir, started_ts, product=product, config=config_name, journey=jid)
    )

    if status == "pass" and any(f.severity in ("blocker", "major") for f in findings):
        status = "fail"

    res_obj = {
        "id": jid,
        "product": product,
        "config": config_name,
        "status": status,
        "duration_s": duration_s,
        "checkpoints": checkpoints,
        "findings": len(findings),
    }
    (j_dir / "result.json").write_text(json.dumps(res_obj, indent=2) + "\n", encoding="utf-8")
    return status, findings
