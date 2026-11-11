"""An adb fake with real state, for the device tools' tests (no device needed).

It answers the commands ``android_test_device.PREPARE_STEPS`` and their read-backs use, keeping
the state a real phone keeps. ``stuck`` names steps that exit 0 and change nothing: the case a
step's exit status cannot tell from success.
"""

from __future__ import annotations

from typing import Iterable, List, Optional, Tuple


class FakePhone:
    def __init__(self, stuck: Iterable[str] = (), secure_lock: bool = False, keyguard_showing: bool = False):
        self.stuck = set(stuck)
        self.secure_lock = secure_lock
        self.keyguard_showing = keyguard_showing
        self.settings = {"global/window_animation_scale": "1.0", "global/transition_animation_scale": "1.0",
                         "global/animator_duration_scale": "1.0", "global/stay_on_while_plugged_in": "0",
                         "system/screen_off_timeout": "60000", "system/accelerometer_rotation": "1",
                         "system/user_rotation": "1", "system/font_scale": "1.3"}
        self.size: Optional[str] = "1080x1920"
        self.density: Optional[str] = "320"
        self.night = "yes"
        self.awake = False
        self.calls: List[List[str]] = []

    # one adb invocation, like android_test_device.run / DeviceWorker.runner
    def __call__(self, cmd, timeout=120) -> Tuple[int, str]:
        self.calls.append(list(cmd))
        if cmd[3:4] != ["shell"] and cmd[1:2] != ["-s"]:
            return 0, ""
        if cmd[-1] == "root" or cmd[3:] == ["wait-for-device"]:
            return 0, ""
        if cmd[3:] == ["shell", "id", "-u"]:
            return 0, "0"
        if cmd[3:5] == ["shell", "dumpsys"] and cmd[5:] == ["window"]:
            return 0, f"  isKeyguardShowing={'true' if self.keyguard_showing else 'false'}\n"
        if len(cmd) < 5 or cmd[3] != "shell":
            return 0, ""
        code, out = 0, ""
        for part in cmd[4].split(" && "):
            code, out = self._one(part.strip())
            if code:
                break
        return code, out

    def _one(self, command: str) -> Tuple[int, str]:
        words = command.split()
        stuck = command in self.stuck or any(command.startswith(s) for s in self.stuck)
        if command == "dumpsys power | grep mWakefulness=":
            return 0, f"mWakefulness={'Awake' if self.awake else 'Asleep'}\n"
        if words[:2] == ["settings", "get"]:
            return 0, self.settings.get(f"{words[2]}/{words[3]}", "null") + "\n"
        if words[:2] == ["settings", "put"]:
            if not stuck:
                self.settings[f"{words[2]}/{words[3]}"] = words[4]
            return 0, ""
        if words[:3] == ["svc", "power", "stayon"]:
            if not stuck:
                self.settings["global/stay_on_while_plugged_in"] = "7" if words[3] == "true" else "0"
            return 0, ""
        if command == "wm size":
            return 0, "Physical size: 1080x1920\n" + (f"Override size: {self.size}\n" if self.size else "")
        if command == "wm density":
            return 0, "Physical density: 440\n" + (f"Override density: {self.density}\n" if self.density else "")
        if command == "wm size reset" or command == "wm density reset":
            if not stuck:
                if "size" in command:
                    self.size = None
                else:
                    self.density = None
            return 0, ""
        if command == "cmd uimode night":
            return 0, f"Night mode: {self.night}\n"
        if words[:3] == ["cmd", "uimode", "night"]:
            if not stuck:
                self.night = words[3]
            return 0, ""
        if words[:2] == ["locksettings", "set-disabled"]:
            if self.secure_lock:
                return 255, "Error: cannot disable the lock screen while a credential is set\n"
            return 0, ""
        if words[:2] == ["input", "keyevent"]:
            self.awake = True
            return 0, ""
        if words[:2] == ["wm", "dismiss-keyguard"]:
            return 0, ""
        return 0, ""
