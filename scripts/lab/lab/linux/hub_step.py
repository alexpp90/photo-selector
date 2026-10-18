"""Hub-side steps `session.sh` runs between the app's checkpoints (LAB-1, LAB-2).

`session.sh` owns the private Xvfb + D-Bus session and executes the journey's step script; the
file system is the hub's, so the two file-system steps run here, at the moment the script
reaches them:

- `await <stem>`: poll the fixture folder (and the lab Trash) until the checkpoint's
  `fs_expect` holds, or until the time budget runs out. File operations are asynchronous in the
  app, so a fixed sleep either wastes time or checks too early (2026-10-17: every Linux fs
  verdict was taken before the Move had landed).
- `fs <stem>`: write `<stem>.fs.json`, the snapshot taken at this checkpoint. One end-of-run
  snapshot copied into every checkpoint made intermediate expectations unpassable.

The context (fixture root, Trash, expectations, budget) comes from the JSON file named by
`PST_LAB_HUB_CTX`, written by `runner.run_linux_journey`. Run as a script:
`python3 hub_step.py await 05_after-trash` / `python3 hub_step.py fs 05_after-trash`, where
`<stem>` is `NN_<checkpoint>` and the context maps stems to checkpoint names.
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path
from typing import Any, Callable

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from lab.fs_oracle import evaluate_fs_oracle, snapshot_directory  # noqa: E402
else:
    from ..fs_oracle import evaluate_fs_oracle, snapshot_directory

DEFAULT_TIMEOUT_MS = 10_000
# The folder must stay unchanged this long with the expectation holding: an expectation that an
# intermediate state already meets (J-LIN-03 expects DSC_0002.JPG present, true before the queued
# Delete and its Undo have run) would otherwise be captured mid-sequence.
DEFAULT_SETTLE_MS = 1_000
POLL_S = 0.1


def load_context(path: Path | str) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def snapshot(ctx: dict[str, Any]) -> dict[str, Any]:
    return snapshot_directory(ctx["fixture"], ctx["base"], trash_dir=ctx["trash"])


def await_expectation(
    ctx: dict[str, Any],
    checkpoint: str,
    *,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    snap: Callable[[dict[str, Any]], dict[str, Any]] = snapshot,
) -> dict[str, Any]:
    """Poll until `fs_expect[checkpoint]` holds on a settled folder, or the budget is spent.

    Returns `landed_ms` (when the final state first appeared), `waited_ms`, `satisfied` and the
    budget. The runner reports a satisfied but late landing as a latency finding; an unsatisfied
    wait leaves the verdict to the fs oracle on the snapshot taken right after the checkpoint.
    """
    expect = (ctx.get("fs_expect") or {}).get(checkpoint)
    timeout_ms = int(ctx.get("timeout_ms", DEFAULT_TIMEOUT_MS))
    settle_ms = int(ctx.get("settle_ms", DEFAULT_SETTLE_MS))
    t0 = clock()
    if not expect:
        return {"checkpoint": checkpoint, "landed_ms": 0, "waited_ms": 0, "satisfied": True, "timeout_ms": timeout_ms}
    last = None
    changed_at = t0
    satisfied = False
    while True:
        now = clock()
        cur = snap(ctx)
        if cur != last:
            last, changed_at = cur, now
        holds = not evaluate_fs_oracle(cur, expect, initial_fs=ctx.get("initial_fs"))
        if holds and (now - changed_at) * 1000.0 >= settle_ms:
            satisfied = True
            break
        if (now - t0) * 1000.0 >= timeout_ms:
            break
        sleep(POLL_S)
    return {
        "checkpoint": checkpoint,
        "landed_ms": int(round((changed_at - t0) * 1000.0)),
        "waited_ms": int(round((clock() - t0) * 1000.0)),
        "satisfied": satisfied,
        "timeout_ms": timeout_ms,
    }


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in ("await", "fs"):
        print("usage: hub_step.py await|fs <stem>", file=sys.stderr)
        return 2
    ctx_path = os.environ.get("PST_LAB_HUB_CTX")
    if not ctx_path:
        print("hub_step.py: PST_LAB_HUB_CTX is not set", file=sys.stderr)
        return 2
    ctx = load_context(ctx_path)
    out_dir = Path(ctx["out_dir"])
    cmd, arg = argv
    if cmd == "await":
        outcome = await_expectation(ctx, str(ctx.get("stems", {}).get(arg, arg)))
        (out_dir / f"{arg}.await.json").write_text(json.dumps(outcome, indent=2) + "\n", encoding="utf-8")
    else:
        (out_dir / f"{arg}.fs.json").write_text(json.dumps(snapshot(ctx), indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
