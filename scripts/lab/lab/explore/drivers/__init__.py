"""Device drivers for exploratory testing, one module per platform.

- `base`: the error hierarchy, `DeviceDriver` (the life cycle the orchestrator calls) and helpers.
- `android`: `AndroidDeviceDriver`, direct adb (`hublock` holds the process-wide hub lock).
- `linux`: `LinuxDeviceDriver`, Blitzlens in its own Xvfb and private D-Bus.
- `macos`: `MacOSDeviceDriver`, Lensjet.
- `mock`: `MockDeviceDriver`, a deterministic offline state machine for tests and `--driver mock`.
"""

from __future__ import annotations

from .android import PNG_MAGIC, AndroidDeviceDriver
from .base import (
    ActionUnavailableError,
    AppCrashedError,
    AppLeftError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    TargetUnderSystemBarError,
)
from .linux import LinuxDeviceDriver
from .macos import MacOSDeviceDriver
from .mock import MockDeviceDriver

__all__ = [
    "ActionUnavailableError", "AndroidDeviceDriver", "AppCrashedError", "AppLeftError", "DeviceDriver",
    "DriverError", "DriverExecutionError", "DriverSettlingError", "DriverTimeoutError", "LinuxDeviceDriver",
    "MacOSDeviceDriver", "MockDeviceDriver", "PNG_MAGIC", "TargetUnderSystemBarError",
]
