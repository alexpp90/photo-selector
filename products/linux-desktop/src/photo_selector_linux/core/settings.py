"""Persistent application settings store backed by XDG_CONFIG_HOME JSON.

Adheres to:
- R-LINUX-PREF-01: Decoupled AdwPreferencesWindow
- R-LINUX-PREF-02: Configurable Application Preferences
"""

from __future__ import annotations

import json
import logging
import os
import shutil
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from photo_selector_linux.core.destination import (
    MODE_CUSTOM,
    MODE_WORKING,
    MODES,
    selection_subfolder_name,
)

logger = logging.getLogger(__name__)

#: The orders the photos can be viewed in, and the ones that persist. "natural" is file-name
#: order; "lowest_sharpness_first" needs a scoring pass over the folder, so it is a per-session
#: triage view and never the order a folder opens in.
ORDER_NEWEST = "newest"
ORDER_OLDEST = "oldest"
ORDER_NAME = "natural"
ORDER_LOWEST_SHARPNESS = "lowest_sharpness_first"
PERSISTED_ORDERS = (ORDER_NEWEST, ORDER_OLDEST, ORDER_NAME)


def default_settings_path() -> Path:
    """Return ``$XDG_CONFIG_HOME/photo-selector-linux/settings.json`` (or ``~/.config/...``)."""
    xdg_config = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg_config) if xdg_config else (Path.home() / ".config")
    return base / "photo-selector-linux" / "settings.json"


class SettingsStore:
    """Persistent user preferences with observer notifications (R-LINUX-PREF-02)."""

    def __init__(
        self,
        config_path: Optional[Path] = None,
        path: Optional[Path] = None,
        auto_load: bool = True,
    ) -> None:
        resolved = path if path is not None else config_path
        self.config_path: Path = Path(resolved) if resolved is not None else default_settings_path()
        self.selection_folder_name: str = "Selection"
        self.auto_advance: bool = True
        self.blurry_threshold: float = 35.0
        self.sharp_threshold: float = 70.0
        self.cache_limit_mb: float = 512.0
        #: Where Move/Copy put photos: ``working`` (inside the open folder) or ``custom``
        #: (inside ``external_destination_folder``). Never ``custom`` without a folder.
        self.destination_mode: str = MODE_WORKING
        #: The custom folder. Kept when the user switches back to the working folder.
        self.external_destination_folder: Optional[str] = None
        self.stop_sharpness_threshold: Optional[float] = None
        #: The order a folder opens in. New installs open newest first; an install that has a
        #: settings file without this key keeps the file-name order it always had.
        self.view_order: str = ORDER_NEWEST
        self._callbacks: List[Callable[[SettingsStore], None]] = []

        if auto_load and self.config_path.is_file():
            self.view_order = ORDER_NAME  # a file written before the order was stored
            self._load_from_disk()

    def connect(self, callback: Callable[[SettingsStore], None]) -> None:
        """Register an observer invoked whenever settings change."""
        if callback not in self._callbacks:
            self._callbacks.append(callback)

    def _notify(self) -> None:
        for cb in list(self._callbacks):
            try:
                cb(self)
            except Exception:
                # An observer that fails must not stop the others or the save, but it is a bug.
                logger.exception("Settings observer %r failed", cb)

    @staticmethod
    def _sanitize_folder_name(value: Any) -> str:
        return selection_subfolder_name(value)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "selection_folder_name": self.selection_folder_name,
            "auto_advance": self.auto_advance,
            "blurry_threshold": self.blurry_threshold,
            "sharp_threshold": self.sharp_threshold,
            "cache_limit_mb": self.cache_limit_mb,
            "destination_mode": self.destination_mode,
            "external_destination_folder": self.external_destination_folder,
            "stop_sharpness_threshold": self.stop_sharpness_threshold,
            "view_order": self.view_order,
        }

    def _apply_dict(self, data: Dict[str, Any]) -> None:
        if "selection_folder_name" in data:
            self.selection_folder_name = self._sanitize_folder_name(data["selection_folder_name"])
        if "auto_advance" in data:
            self.auto_advance = bool(data["auto_advance"])
        if "blurry_threshold" in data and data["blurry_threshold"] is not None:
            try:
                self.blurry_threshold = max(1.0, min(95.0, float(data["blurry_threshold"])))
            except (TypeError, ValueError):
                pass
        if "sharp_threshold" in data and data["sharp_threshold"] is not None:
            try:
                self.sharp_threshold = max(5.0, min(99.0, float(data["sharp_threshold"])))
            except (TypeError, ValueError):
                pass
        if self.blurry_threshold > self.sharp_threshold:
            self.blurry_threshold = self.sharp_threshold
        if "cache_limit_mb" in data and data["cache_limit_mb"] is not None:
            try:
                self.cache_limit_mb = max(16.0, float(data["cache_limit_mb"]))
            except (TypeError, ValueError):
                pass
        if "external_destination_folder" in data:
            val = data["external_destination_folder"]
            self.external_destination_folder = (str(val).strip() or None) if val else None
            if "destination_mode" not in data and self.external_destination_folder:
                # Settings written before the choice existed: a stored folder was in use.
                self.destination_mode = MODE_CUSTOM
        if "destination_mode" in data:
            mode = data["destination_mode"]
            self.destination_mode = mode if mode in MODES else MODE_WORKING
        if self.destination_mode == MODE_CUSTOM and not self.external_destination_folder:
            self.destination_mode = MODE_WORKING
        if data.get("view_order") in PERSISTED_ORDERS:
            self.view_order = data["view_order"]
        if "stop_sharpness_threshold" in data:
            val = data["stop_sharpness_threshold"]
            if val is None or val == "":
                self.stop_sharpness_threshold = None
            else:
                try:
                    self.stop_sharpness_threshold = max(0.0, min(100.0, float(val)))
                except (TypeError, ValueError):
                    pass

    def _load_from_disk(self) -> SettingsStore:
        """Load settings from ``self.config_path`` if present."""
        if not self.config_path.is_file():
            return self
        try:
            raw = json.loads(self.config_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            # Defaults apply, and the next save would overwrite the unreadable file: keep a copy.
            logger.warning("Could not read settings %s (%s); using defaults", self.config_path, e)
            try:
                shutil.copy2(self.config_path, self.config_path.with_name(self.config_path.name + ".corrupt"))
            except OSError:
                pass
            return self
        if isinstance(raw, dict):
            self._apply_dict(raw)
        return self

    @classmethod
    def load(cls, config_path: Optional[Path] = None) -> SettingsStore:
        """Load a ``SettingsStore`` from ``config_path`` or ``default_settings_path()``."""
        if isinstance(config_path, cls):
            return config_path._load_from_disk()
        return cls(config_path=config_path, auto_load=True)

    def save(self) -> None:
        """Persist current settings to ``self.config_path``."""
        text = json.dumps(self.to_dict(), indent=2, sort_keys=True) + "\n"
        temp = self.config_path.with_name(self.config_path.name + ".tmp")
        try:
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            # Write beside the file and rename over it: a crash leaves the old settings, not a
            # half-written file that reads back as defaults.
            with open(temp, "w", encoding="utf-8") as handle:
                handle.write(text)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp, self.config_path)
        except OSError as e:
            logger.warning("Could not save settings %s: %s", self.config_path, e)
            try:
                temp.unlink()
            except OSError:
                pass

    def update(self, **kwargs: Any) -> None:
        """Update one or more settings, persist to disk, and notify observers."""
        self._apply_dict(kwargs)
        self.save()
        self._notify()
