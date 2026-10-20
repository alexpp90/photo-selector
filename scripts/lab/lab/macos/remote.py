"""Remote LaunchAgent trigger for macOS lab runs from the Linux hub (C-32)."""

from __future__ import annotations

import datetime as dt
import json
import subprocess
import time
from pathlib import Path
from typing import Any, Callable


def preflight_remote_mac(
    host: str,
    user: str = "pst-lab",
    run_cmd: Callable[[list[str]], subprocess.CompletedProcess[str]] | None = None,
) -> tuple[bool, str | None]:
    """Check that the Mac is awake and pst-lab has an active GUI Aqua session."""
    runner = run_cmd or (lambda c: subprocess.run(c, capture_output=True, text=True, check=False))
    cmd = [
        "ssh",
        "-o",
        "ConnectTimeout=5",
        "-o",
        "BatchMode=yes",
        f"{user}@{host}",
        "launchctl print gui/$(id -u) >/dev/null && echo ok",
    ]
    res = runner(cmd)
    if res.returncode == 0 and "ok" in (res.stdout or ""):
        return True, None
    return False, "Mac asleep or pst-lab not logged in"


def remote_run_id(run_id: str, now: dt.datetime | None = None) -> str:
    """The Mac's run id: the hub run's name plus the trigger time, unique per trigger.

    The Mac skips a trigger whose `~/pst/out/<id>/done` exists, and the hub polls for that
    file. With the bare `--out` name a re-used name returned the old run's results at once,
    which looked like a fresh pass (LAB-21, 2026-10-19).
    """
    stamp = (now or dt.datetime.now(dt.timezone.utc)).strftime("%Y%m%dT%H%M%S")
    return f"{run_id}-{stamp}"


def trigger_remote_mac_run(
    host: str,
    run_id: str,
    commit: str,
    journeys: list[str],
    configs: list[str],
    local_run_dir: Path,
    user: str = "pst-lab",
    timeout_s: float = 1800.0,
    poll_interval_s: float = 5.0,
    run_cmd: Callable[[list[str]], subprocess.CompletedProcess[str]] | None = None,
    now: dt.datetime | None = None,
) -> tuple[bool, str | None]:
    """Write ~/pst/trigger/run.json on remote Mac, poll for ~/pst/out/<id>/done, and rsync back.

    `<id>` is `remote_run_id(run_id)`, never an earlier run's: a remote out dir that already
    exists is refused rather than read.
    """
    runner = run_cmd or (lambda c: subprocess.run(c, capture_output=True, text=True, check=False))
    ok, reason = preflight_remote_mac(host, user=user, run_cmd=runner)
    if not ok:
        return False, reason
    run_id = remote_run_id(run_id, now)
    exists = runner(["ssh", "-o", "ConnectTimeout=5", f"{user}@{host}", f"test -e ~/pst/out/{run_id} && echo exists"])
    if "exists" in (exists.stdout or ""):
        return False, f"~/pst/out/{run_id} already exists on {host}; refusing to read an earlier run's results"

    payload: dict[str, Any] = {
        "run_id": run_id,
        "commit": commit,
        "journeys": journeys,
        "configs": configs,
    }
    payload_str = json.dumps(payload)
    write_cmd = [
        "ssh",
        "-o",
        "ConnectTimeout=5",
        f"{user}@{host}",
        f"mkdir -p ~/pst/trigger && printf '%s\\n' '{payload_str}' > ~/pst/trigger/run.json",
    ]
    wres = runner(write_cmd)
    if wres.returncode != 0:
        return False, f"Failed to write trigger file on {host}: {wres.stderr}"

    deadline = time.monotonic() + timeout_s
    done_path = f"~/pst/out/{run_id}/done"
    while time.monotonic() < deadline:
        chk = runner(["ssh", "-o", "ConnectTimeout=5", f"{user}@{host}", f"test -f {done_path} && echo done"])
        if chk.returncode == 0 and "done" in (chk.stdout or ""):
            rsync_cmd = [
                "rsync",
                "-a",
                f"{user}@{host}:~/pst/out/{run_id}/macos/",
                str(local_run_dir / "macos") + "/",
            ]
            rres = runner(rsync_cmd)
            if rres.returncode != 0:
                return False, f"rsync from {host} failed: {rres.stderr}"
            # The Mac's deterministic findings, merged into the hub's findings by the orchestrator.
            runner(
                [
                    "rsync",
                    "-a",
                    f"{user}@{host}:~/pst/out/{run_id}/findings.det.json",
                    str(local_run_dir / "macos" / "findings.det.json"),
                ]
            )
            return True, None
        time.sleep(poll_interval_s)

    return False, f"Timed out after {timeout_s}s waiting for {done_path}"
