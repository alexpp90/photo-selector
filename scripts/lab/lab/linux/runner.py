"""Linux Desktop journey runner under private Xvfb + D-Bus session (C-41, C-42)."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Callable

from ..fixtures import generate_fixtures
from ..fs_oracle import evaluate_fs_oracle, snapshot_directory
from ..layout_lint import lint, load_rules
from ..logscan import scan_linux_log
from ..model import Finding, Tree
from ..paths import ensure_under, lab_tmp_root, repo_root


# How long `await` waits for a checkpoint's fs_expect to hold (a journey may set
# `fs_timeout_ms`), and the settle time above which a satisfied wait is reported as slow.
FS_AWAIT_TIMEOUT_MS = 10_000
FS_SETTLE_BUDGET_MS = 2_000


def _finding(rule: str, severity: str, message: str, *, product: str, config: str, journey: str,
             checkpoint: str, evidence: list[str]) -> Finding:
    return Finding(
        id=Finding.make_id(product, journey, checkpoint, rule, [checkpoint]),
        source="fs-oracle",
        product=product,
        config=config,
        journey=journey,
        checkpoint=checkpoint,
        rule=rule,
        severity=severity,
        confidence="high",
        region=None,
        message=message,
        evidence=evidence,
        new_since_last_run=True,
    )


def evaluate_await(outcome: dict[str, Any], *, product: str, config: str, journey: str, checkpoint: str,
                   evidence: list[str]) -> list[Finding]:
    """A satisfied `await` whose state landed after `FS_SETTLE_BUDGET_MS` is a latency finding.

    An unsatisfied one needs no finding of its own: the fs oracle reports the state it saw.
    """
    landed = int(outcome.get("landed_ms", outcome.get("waited_ms", 0)))
    if outcome.get("satisfied") and landed > FS_SETTLE_BUDGET_MS:
        return [
            _finding(
                "fs-settle-slow",
                "minor",
                f"the file operation for {checkpoint!r} landed only after {landed} ms "
                f"(budget {FS_SETTLE_BUDGET_MS} ms)",
                product=product,
                config=config,
                journey=journey,
                checkpoint=checkpoint,
                evidence=evidence,
            )
        ]
    return []


def preflight_linux() -> tuple[bool, str | None]:
    """Verify Linux host prerequisites (Linux OS, Xvfb, xdotool, gapplication/gdbus)."""
    if sys.platform != "linux":
        return False, f"Linux Desktop requires Linux host (current: {sys.platform})"
    for binary in ("Xvfb", "xdotool", "dbus-run-session"):
        if shutil.which(binary) is None:
            return False, f"Missing required binary '{binary}' (install xvfb xdotool dbus-x11)"
    return True, None


def build_journey_commands(journey_manifest: dict[str, Any], out_dir: Path) -> list[str]:
    """Convert journey manifest steps into the line script `session.sh --script` executes.

    One command per line: `key <xdotool keysym>`, `sleep <seconds>`, `checkpoint <name>`, and
    the hub steps `await <stem>` / `fs <stem>` (`hub_step.py`). `session.sh` runs them inside its
    private Xvfb + D-Bus session, where the app's `lab-checkpoint` action is reachable; the hub's
    own session bus never sees them.

    Every checkpoint is bracketed by the file system: `await` first, so a checkpoint with an
    `fs_expect` is captured once the asynchronous file operation has landed (or its budget is
    spent), and `fs` right after, so each checkpoint carries the snapshot of its own moment.
    """
    from .keys import normalize_xdotool_key

    cmds: list[str] = []
    cp_index = 0
    for step in journey_manifest.get("steps", []):
        if "keys" in step:
            for k in step["keys"]:
                cmds.append(f"key {normalize_xdotool_key(str(k))}")
                cmds.append("sleep 0.2")
        elif "wait_ms" in step:
            sec = max(0.05, float(step["wait_ms"]) / 1000.0)
            cmds.append(f"sleep {sec:.2f}")
        elif "checkpoint" in step:
            cp_index += 1
            stem = checkpoint_stem(cp_index, str(step["checkpoint"]))
            cmds.append(f"await {stem}")
            cmds.append(f"checkpoint {step['checkpoint']}")
            cmds.append(f"fs {stem}")
            cmds.append("sleep 0.5")
    return cmds


def checkpoint_stem(index: int, name: str) -> str:
    """`NN_<name>`, the stem the app's `lab-checkpoint` action gives the same checkpoint."""
    return f"{index:02d}_{name}"


def lab_python() -> str:
    """A Python with PyGObject for the app: `$PST_LAB_PYTHON`, else Debian's `/usr/bin/python3`.

    The `python3` on PATH may be a Homebrew or venv interpreter without `gi`; the app needs the
    distribution's PyGObject + GTK4 + Libadwaita bindings.
    """
    explicit = os.environ.get("PST_LAB_PYTHON")
    if explicit:
        return explicit
    return "/usr/bin/python3" if Path("/usr/bin/python3").is_file() else (shutil.which("python3") or "python3")


def session_env(root: Path | None = None) -> dict[str, str]:
    """Environment for `session.sh` running the source tree: the app's interpreter and its `src`."""
    r = root or repo_root()
    env = dict(os.environ)
    env["PST_LAB_PYTHON"] = lab_python()
    src_dir = str(r / "products" / "linux-desktop" / "src")
    env["PYTHONPATH"] = src_dir + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
    return env


def run_linux_journey(
    journey_manifest: dict[str, Any],
    config_name: str,
    config_spec: dict[str, Any],
    run_dir: Path,
    fixture_base_dir: Path | None = None,
    installed: bool = False,
    exec_fn: Callable[[list[str], dict[str, str]], subprocess.CompletedProcess[str]] | None = None,
) -> tuple[str, list[Finding]]:
    """Run a Linux Desktop journey inside a private Xvfb + D-Bus session."""
    product = "linux"
    r = repo_root()
    session_sh = r / "products" / "linux-desktop" / "tests" / "e2e" / "session.sh"
    jid = str(journey_manifest["id"])
    fset = str(journey_manifest.get("fixture") or journey_manifest.get("fixture_set") or "cull-basic")
    j_dir = run_dir / product / config_name / jid
    ensure_under(j_dir, run_dir)
    j_dir.mkdir(parents=True, exist_ok=True)

    fbase = (fixture_base_dir or lab_tmp_root()).resolve()
    fbase.mkdir(parents=True, exist_ok=True)
    lab_work = fbase / f"linux_{jid}_{config_name}"
    ensure_under(lab_work, fbase)
    if lab_work.exists():
        shutil.rmtree(lab_work)
    work_fixture = lab_work / fset
    generate_fixtures(fset, work_fixture, root=r)

    xdg_data = lab_work / "xdg-data"
    xdg_config = lab_work / "xdg-config"
    xdg_data.mkdir(parents=True, exist_ok=True)
    xdg_config.mkdir(parents=True, exist_ok=True)
    trash_files = xdg_data / "Trash" / "files"

    initial_fs = snapshot_directory(work_fixture, fbase, trash_dir=trash_files)
    (j_dir / "00_initial.fs.json").write_text(
        json.dumps(initial_fs, indent=2) + "\n",
        encoding="utf-8",
    )

    checkpoint_names = [str(st["checkpoint"]) for st in journey_manifest.get("steps", []) if "checkpoint" in st]
    hub_ctx = {
        "fixture": str(work_fixture),
        "base": str(fbase),
        "trash": str(trash_files),
        "out_dir": str(j_dir),
        "initial_fs": initial_fs,
        "fs_expect": journey_manifest.get("fs_expect", {}),
        "timeout_ms": int(journey_manifest.get("fs_timeout_ms", FS_AWAIT_TIMEOUT_MS)),
        "stems": {checkpoint_stem(i, n): n for i, n in enumerate(checkpoint_names, start=1)},
    }
    hub_ctx_path = j_dir / "hub_ctx.json"
    hub_ctx_path.write_text(json.dumps(hub_ctx, indent=2) + "\n", encoding="utf-8")

    steps_script = j_dir / "steps.txt"
    step_lines = [f"# {jid} @ {config_name}"] + build_journey_commands(journey_manifest, j_dir)
    steps_script.write_text("\n".join(step_lines) + "\n", encoding="utf-8")

    cmd = [
        str(session_sh),
        "--fixture",
        str(work_fixture),
        "--artifacts",
        str(j_dir),
        "--lab-dir",
        str(lab_work),
        "--script",
        str(steps_script),
        "--size",
        str(config_spec.get("size", "1920x1080")),
        "--scale",
        str(config_spec.get("scale", 1)),
    ]
    if config_spec.get("color_scheme"):
        cmd.extend(["--color-scheme", str(config_spec["color_scheme"])])
    if config_spec.get("text_scaling"):
        cmd.extend(["--text-scaling", str(config_spec["text_scaling"])])
    if installed:
        cmd.append("--installed")

    env = session_env(r)
    env["PST_LAB_HUB_PYTHON"] = sys.executable
    env["PST_LAB_HUB_STEP"] = str(Path(__file__).resolve().parent / "hub_step.py")
    env["PST_LAB_HUB_CTX"] = str(hub_ctx_path)
    t0 = time.monotonic()
    if exec_fn is not None:
        proc = exec_fn(cmd, env)
    else:
        proc = subprocess.run(cmd, env=env, capture_output=True, text=True, check=False)
    duration_s = round(time.monotonic() - t0, 2)

    log_path = j_dir / "journey.log"
    log_text = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if log_path.is_file():
        log_text = log_path.read_text(encoding="utf-8") + "\n" + log_text
    log_path.write_text(log_text, encoding="utf-8")

    checkpoints = checkpoint_names
    status = "pass" if proc.returncode == 0 else "flow-broken"
    rules_cfg = load_rules(product)
    layout_expect_all = journey_manifest.get("layout_expect", {})
    fs_expect_all = journey_manifest.get("fs_expect", {})

    findings: list[Finding] = []
    for idx, cp_name in enumerate(checkpoints, start=1):
        prefix = f"{idx:02d}"
        rel_shot = f"{product}/{config_name}/{jid}/{prefix}_{cp_name}.png"
        tree_p = j_dir / f"{prefix}_{cp_name}.tree.json"
        fs_p = j_dir / f"{prefix}_{cp_name}.fs.json"
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
        await_p = j_dir / f"{prefix}_{cp_name}.await.json"
        if await_p.is_file():
            findings.extend(
                evaluate_await(
                    json.loads(await_p.read_text(encoding="utf-8")),
                    product=product,
                    config=config_name,
                    journey=jid,
                    checkpoint=cp_name,
                    evidence=[rel_shot],
                )
            )
        if cp_name in fs_expect_all and not fs_p.is_file():
            findings.append(
                _finding(
                    "fs-snapshot-missing",
                    "major",
                    f"no file-system snapshot was taken at checkpoint {cp_name!r}; its fs_expect was not checked",
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

    findings.extend(scan_linux_log(log_text, product=product, config=config_name, journey=jid))
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
