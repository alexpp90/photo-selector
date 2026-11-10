"""macOS driver: Lensjet."""

from __future__ import annotations

from pathlib import Path
from typing import Any, List, Optional

from ..actions import Action, ScreenState
from .base import DeviceDriver, DriverError


# ==============================================================================
# macOS (refused)
# ==============================================================================

class MacOSDeviceDriver(DeviceDriver):
    """Lensjet cannot be explored yet: its test hook runs a whole scenario per launch.

    A live exploration needs the app held open and driven step by step; replaying the history
    of earlier steps into a fresh launch every capture is not that (duplicate checkpoints,
    dropped modifiers, nothing observed on a remote Mac). Until such a driver exists this one
    refuses, so no run reports a Lensjet exploration it did not do.
    """

    REFUSAL = "Lensjet exploration needs a live-app driver; not implemented yet"

    def __init__(
        self,
        app_id: str = "app.alpatix.lensjet",
        fixture_dir: Path | str = "/tmp/pstlab/fixture",
        out_dir: Optional[Path] = None,
        settle_ms: int = 250,
        **_ignored: Any,
    ) -> None:
        super().__init__("macos-desktop", app_id, fixture_dir, out_dir or Path("."), settle_ms)

    def _refuse(self) -> DriverError:
        return DriverError(self.REFUSAL)

    def setup(self) -> None:
        raise self._refuse()

    def prepare(self) -> None:
        raise self._refuse()

    def teardown(self) -> None:
        pass

    def capture_state(self) -> ScreenState:
        raise self._refuse()

    def execute_action(self, action: Action) -> None:
        raise self._refuse()

    def restart_app(self) -> None:
        raise self._refuse()

    def read_app_log(self) -> List[str]:
        raise self._refuse()
