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


# Spellings scenarios use for keys whose X keysym is spelled differently; xdotool ignores an
# unknown name with only a warning and exit 0 ("No such key name 'Space'"), so a wrong
# spelling is a key that silently never arrives.
_KEYSYMS = {
    "SPACE": "space", "ENTER": "Return", "RETURN": "Return", "ESC": "Escape", "TAB": "Tab",
    "BACKSPACE": "BackSpace", "PAGEUP": "Prior", "PAGEDOWN": "Next", "HOME": "Home", "END": "End",
    ",": "comma", "COMMA": "comma", ".": "period", "/": "slash", "?": "question", "+": "plus", "-": "minus",
}
_XMODIFIERS = {"CTRL": "ctrl", "CONTROL": "ctrl", "SHIFT": "shift", "ALT": "alt", "SUPER": "super"}


def normalize_xdotool_key(key_name: str) -> str:
    """Translate a lab key name (`M`, `Space`, `Ctrl+L`) into an xdotool key specification.

    Letters become lower-case keysyms: `M` would be sent as Shift+m.
    """
    name = key_name.strip()
    upper = name.upper()
    if upper in XDOTOOL_KEY_MAP:
        return XDOTOOL_KEY_MAP[upper]
    if "+" in name.strip("+"):
        *mods, key = name.split("+")
        xmods = [_XMODIFIERS.get(m.strip().upper(), m.strip().lower()) for m in mods]
        return "+".join([*xmods, normalize_xdotool_key(key)])
    if upper in _KEYSYMS:
        return _KEYSYMS[upper]
    if len(name) == 1 and name.isalpha():
        return name.lower()
    return name


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
