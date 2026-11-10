"""macOS driver: Lensjet, held open and driven one action at a time through the app's live-control folder.

The test-hook build runs a whole `--ui-scenario` per launch and exits, so exploration could not
hold the app open: replaying the history of earlier steps into a fresh launch on every capture gave
duplicate checkpoint names (`UIScenario.validate()` throws `duplicateCheckpoint`), re-fired `m` and
Delete on other photos, dropped key modifiers (Cmd+O, Cmd+Z), and saw nothing of a remote run.

This driver launches the app once on a staged copy of the fixture with

    PhotoSelectorApp --open <work> --ui-live <control dir> --artifacts <artifacts dir>

and talks to it over that folder, one file per command, in the vocabulary of the scenario runner:

- the app writes `ready.json` when its window is up;
- the lab writes `cmd-<seq>.json`: `{"seq": n, "step": {"key": "m", "modifiers": ["command"]}}`,
  `{"seq": n, "step": {"click": "toolbar_zoom"}}` or `{"seq": n, "step": {"checkpoint": "s3"}}`
  (the same steps a scenario file holds, a checkpoint name unique per capture);
- the app performs it and writes `done-<seq>.json` (`{"ok": true}` or `{"ok": false, "error": "..."}`);
  a checkpoint also writes `<NN>_<name>.png`, `.tree.json` and `.state.json` under `--artifacts`.

A build that lacks `--ui-live` exits at once and `prepare()` says so (the flag is the app side of
backlog `2026-10-25-lab-explore-lensjet-has-no-live-app-driver`; nothing here claims a run that
was not done). The driver runs where the app runs: on the Mac, as `pst-lab` does for journeys.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, Callable, List, Optional

from ...fixtures import generate_fixtures
from ...fs_oracle import snapshot_directory
from ...macos.runner import default_app_binary
from ...model import Tree
from ...paths import ensure_under, lab_tmp_root, repo_root
from ..actions import Action, ScreenState
from ..discovery import classify_screen
from .base import (
    AppCrashedError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverTimeoutError,
    _ensure_dir,
    _key_spec,
    _scratch_path,
)

# The scenario runner's key names for what the lab calls `Ctrl`/`Meta`/`Alt`.
_MODIFIER_NAMES = {"ctrl": "control", "control": "control", "cmd": "command", "meta": "command", "super": "command",
                   "command": "command", "alt": "option", "option": "option", "shift": "shift"}

Spawn = Callable[[List[str], Path], Any]


def _spawn(cmd: List[str], log_path: Path) -> subprocess.Popen[Any]:
    log = open(log_path, "ab")
    return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)


class MacOSDeviceDriver(DeviceDriver):
    """Lensjet held open on a staged fixture and driven through `--ui-live`."""

    NOT_ON_A_MAC = (
        "Lensjet exploration runs on the Mac itself (the app is driven through its --ui-live folder); "
        "run `lab.py explore --product macos-desktop` there"
    )

    def __init__(
        self,
        app_id: str = "app.alpatix.lensjet",
        fixture_dir: Path | str | None = None,
        out_dir: Optional[Path] = None,
        settle_ms: int = 250,
        fixture_set: str = "smoke",
        app_binary: Path | None = None,
        spawn: Spawn | None = None,
        start_timeout_s: float = 30.0,
        command_timeout_s: float = 15.0,
        poll_s: float = 0.05,
        **_ignored: Any,
    ) -> None:
        root = lab_tmp_root()
        self.lab_dir = ensure_under(root / f"explore-macos-{time.time_ns()}", root)
        work = ensure_under(fixture_dir, root) if fixture_dir is not None else self.lab_dir / "work"
        super().__init__("macos-desktop", app_id, work, out_dir or _scratch_path("explore-macos-out"), settle_ms)
        self._owns_out_dir = out_dir is None
        self.fixture_set = fixture_set
        self.app_binary = app_binary
        self._spawn = spawn or _spawn
        self.start_timeout_s = start_timeout_s
        self.command_timeout_s = command_timeout_s
        self.poll_s = poll_s
        self.control_dir = self.lab_dir / "control"
        self.artifacts_dir = self.out_dir / "artifacts"
        self._proc: Any = None
        self._seq = 0
        self._captures = 0
        self._log_offset = 0

    @property
    def log_path(self) -> Path:
        return self.artifacts_dir / "app.log"

    # -- life cycle ---------------------------------------------------------------
    def setup(self) -> None:
        """Check the app build exists. Starts nothing (and never needs the Mac's GUI to answer)."""
        binary = self.app_binary or default_app_binary(repo_root())
        if not Path(binary).is_file():
            raise DriverError(f"no Lensjet test-hook build at {binary} (build with `--test-hooks`, see playbook-run-device-lab)")
        self.app_binary = Path(binary)

    def prepare(self) -> None:
        try:
            _ensure_dir(self.artifacts_dir)
            work = ensure_under(self.working_dir, lab_tmp_root())
            if not work.is_dir() or not any(work.iterdir()):
                generate_fixtures(self.fixture_set, work, root=repo_root())
            self.working_dir = work
            self._launch()
        except BaseException:
            self.teardown()
            raise

    def _launch(self) -> None:
        self._stop_app()
        shutil.rmtree(self.control_dir, ignore_errors=True)
        _ensure_dir(self.control_dir)
        self._seq = 0
        self._log_offset = self.log_path.stat().st_size if self.log_path.is_file() else 0
        cmd = [str(self.app_binary), "--open", str(self.working_dir), "--ui-live", str(self.control_dir),
               "--artifacts", str(self.artifacts_dir)]
        self._proc = self._spawn(cmd, self.log_path)
        deadline = time.monotonic() + self.start_timeout_s
        while not (self.control_dir / "ready.json").is_file():
            if self._proc.poll() is not None:
                raise DriverError(
                    f"{self.app_id} exited ({self._proc.poll()}) before it was ready; a build without `--ui-live` does that: "
                    f"{self._log_tail()}"
                )
            if time.monotonic() > deadline:
                raise DriverTimeoutError(f"no ready.json from {self.app_id} after {self.start_timeout_s:.0f}s")
            time.sleep(self.poll_s)

    def _log_tail(self) -> str:
        return self.log_path.read_text(encoding="utf-8", errors="replace")[-300:].strip() if self.log_path.is_file() else ""

    def _stop_app(self) -> None:
        proc, self._proc = self._proc, None
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=3)

    def teardown(self) -> None:
        self._stop_app()
        shutil.rmtree(ensure_under(self.lab_dir, lab_tmp_root()), ignore_errors=True)
        if self._owns_out_dir:
            shutil.rmtree(self.out_dir, ignore_errors=True)

    def restart_app(self) -> None:
        """Stop the app and start it again on the same folder."""
        self._launch()
        self.last_state = None

    # -- the control folder -------------------------------------------------------
    def _send(self, step: dict[str, Any]) -> dict[str, Any]:
        """Write one command, wait for its answer; `AppCrashedError` when the app died meanwhile."""
        if self._proc is None or self._proc.poll() is not None:
            raise AppCrashedError(f"{self.app_id} is not running (exit {None if self._proc is None else self._proc.poll()})")
        self._seq += 1
        seq = self._seq
        tmp = self.control_dir / f".cmd-{seq}.tmp"
        tmp.write_text(json.dumps({"seq": seq, "step": step}), encoding="utf-8")
        tmp.rename(self.control_dir / f"cmd-{seq}.json")  # the app never reads a half-written command
        done = self.control_dir / f"done-{seq}.json"
        deadline = time.monotonic() + self.command_timeout_s
        while not done.is_file():
            if self._proc.poll() is not None:
                raise AppCrashedError(f"{self.app_id} exited ({self._proc.poll()}) during {step}")
            if time.monotonic() > deadline:
                raise DriverTimeoutError(f"no answer to {step} within {self.command_timeout_s:.0f}s")
            time.sleep(self.poll_s)
        try:
            return json.loads(done.read_text(encoding="utf-8"))
        except ValueError as exc:
            raise DriverError(f"unreadable answer {done.name}: {exc}") from exc

    # -- capture ------------------------------------------------------------------
    def capture_state(self) -> ScreenState:
        self._captures += 1
        name = f"s{self._captures}"  # unique per capture: the scenario runner refuses a repeated name
        answer = self._send({"checkpoint": name})
        if not answer.get("ok"):
            raise DriverError(f"checkpoint {name} failed: {answer.get('error', 'no reason given')}")
        pngs = sorted(self.artifacts_dir.glob(f"[0-9][0-9]_{name}.png"))
        if not pngs or pngs[0].stat().st_size == 0:
            raise DriverError(f"checkpoint {name} has no screenshot")
        stem = pngs[0].stem
        try:
            tree = Tree.from_dict(json.loads((self.artifacts_dir / f"{stem}.tree.json").read_text(encoding="utf-8")))
        except (OSError, ValueError, KeyError) as exc:
            raise DriverError(f"checkpoint {stem} has no readable tree: {exc}") from exc
        if not tree.nodes:
            raise DriverError(f"checkpoint {stem} has a tree with no nodes")
        try:
            fs_snapshot = snapshot_directory(self.working_dir, lab_tmp_root(), root_label="work")
        except (OSError, ValueError) as exc:
            raise DriverError(f"cannot list {self.working_dir}: {exc}") from exc
        state = ScreenState(
            tree=tree, screenshot_bytes=pngs[0].read_bytes(), screen_mode=classify_screen(tree, self.product),
            fs_snapshot=fs_snapshot, logs=[], position=self._read_position(self.artifacts_dir / f"{stem}.state.json"),
        )
        self.last_state = state
        return state

    @staticmethod
    def _read_position(path: Path) -> Optional[tuple[int, Optional[int]]]:
        """`(index, total)` from the checkpoint's `state.json` when the app writes them, else None."""
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return int(data["current_index"]), int(data["total"])
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def read_app_log(self) -> List[str]:
        if not self.log_path.is_file():
            return []
        with open(self.log_path, "rb") as fh:
            fh.seek(self._log_offset)
            data = fh.read()
        self._log_offset += len(data)
        return data.decode("utf-8", errors="replace").splitlines()

    # -- actions ------------------------------------------------------------------
    def execute_action(self, action: Action) -> None:
        self.step_count += 1
        t = action.action_type
        if t == "key":
            key, mods = _key_spec(action)
            step: dict[str, Any] = {"key": key.lower() if len(key) == 1 else key}
            if mods:
                step["modifiers"] = [_MODIFIER_NAMES.get(m, m) for m in mods]
        elif t in ("tap", "click"):
            if not action.target_id:
                raise DriverExecutionError("Lensjet clicks name a registered frame or control (target_id)")
            step = {"click": action.target_id}
        elif t == "back":
            step = {"key": "escape"}
        else:
            raise DriverExecutionError(f"Lensjet cannot perform {t!r}")
        answer = self._send(step)
        if not answer.get("ok"):
            raise DriverExecutionError(f"{step} failed: {answer.get('error', 'no reason given')}")
