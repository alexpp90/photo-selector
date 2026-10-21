"""xdotool key mapping and dispatcher for Linux Desktop E2E runs (C-41)."""

from __future__ import annotations

import subprocess
import time
from typing import Callable

XDOTOOL_KEY_MAP: dict[str, str] = {
    "RIGHT": "Right",
    "LEFT": "Left",
    "UP": "Up",
    "DOWN": "Down",
    "DPAD_RIGHT": "Right",
    "DPAD_LEFT": "Left",
    "M": "m",
    "C": "c",
    "S": "s",
    "I": "i",
    "1": "1",
    "2": "2",
    "3": "3",
    "SPACE": "space",
    "DELETE": "Delete",
    "FORWARD_DEL": "Delete",
    "DEL": "BackSpace",
    "ESCAPE": "Escape",
    "CTRL+Z": "ctrl+z",
}


def normalize_xdotool_key(key_name: str) -> str:
    """Translate a lab key name into an xdotool key specification."""
    upper = key_name.strip().upper()
    if upper in XDOTOOL_KEY_MAP:
        return XDOTOOL_KEY_MAP[upper]
    return key_name.strip()


def send_keys_xdotool(
    keys: list[str],
    display: str,
    run_cmd: Callable[[list[str], dict[str, str]], subprocess.CompletedProcess[str]] | None = None,
    delay_s: float = 0.15,
) -> list[list[str]]:
    """Activate the Blitzlens window on display and send each key via xdotool."""
    env = {"DISPLAY": display}
    runner = run_cmd or (lambda c, e: subprocess.run(c, env=e, capture_output=True, text=True, check=False))
    recorded: list[list[str]] = []

    activate_cmd = ["xdotool", "search", "--sync", "--name", "Blitzlens", "windowactivate", "--sync"]
    recorded.append(activate_cmd)
    runner(activate_cmd, env)

    for k in keys:
        xk = normalize_xdotool_key(k)
        kcmd = ["xdotool", "key", "--clearmodifiers", xk]
        recorded.append(kcmd)
        runner(kcmd, env)
        if delay_s > 0:
            time.sleep(delay_s)

    return recorded
