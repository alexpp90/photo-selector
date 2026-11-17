"""Copy decisions that survive reopening a folder (``docs/shared/FILE_OPERATIONS.md`` §5, D3).

A photo copied into the Selection is decided. The scan of a reopened folder knows nothing of it
(the original stays where it was), so the decision is kept as local application state, beside the
settings file: one JSON object, ``{folder: {photo key: {"size": bytes, "to": destination path, "at": epoch seconds}}}``.
It is never written into the photo or its sidecar. The destination path lets a reopen check that
the copy still exists: a copy the user deleted from the Selection is not a decision any more.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

#: Folders remembered at most; the oldest entries go first so the file cannot grow without bound.
MAX_FOLDERS = 200


def photo_key(folder: Path, primary: Path) -> str:
    """The identity of a photo inside an opened folder: its path relative to the folder."""
    try:
        return primary.relative_to(folder).as_posix()
    except ValueError:
        return str(primary)


class CopyDecisionStore:
    """Persistent set of copied photos, keyed by the opened folder and the photo's file identity."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self._lock = threading.Lock()
        self._data: Dict[str, Dict[str, Dict[str, Any]]] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.is_file():
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            logger.warning("Could not read copy decisions %s (%s); starting empty", self.path, error)
            return
        if not isinstance(raw, dict):
            return
        for folder, entries in raw.items():
            if isinstance(folder, str) and isinstance(entries, dict):
                self._data[folder] = {
                    k: v for k, v in entries.items() if isinstance(k, str) and isinstance(v, dict)
                }

    def _save(self) -> None:
        temp = self.path.with_name(self.path.name + ".tmp")
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(temp, "w", encoding="utf-8") as handle:
                handle.write(json.dumps(self._data, indent=1, sort_keys=True) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp, self.path)
        except OSError as error:
            logger.warning("Could not save copy decisions %s: %s", self.path, error)
            try:
                temp.unlink()
            except OSError:
                pass

    def folder_entries(self, folder: Path) -> Dict[str, Dict[str, Any]]:
        """A copy of what is remembered for ``folder`` (empty when nothing is)."""
        with self._lock:
            return dict(self._data.get(str(folder), {}))

    def record(self, folder: Path, key: str, size: Optional[int], destination: Optional[Path]) -> None:
        """Remember that the photo ``key`` of ``folder`` was copied (to ``destination``)."""
        with self._lock:
            entry: Dict[str, Any] = {"size": size, "at": time.time()}
            if destination is not None:
                entry["to"] = str(destination)
            folder_key = str(folder)
            entries = self._data.pop(folder_key, {})  # re-inserted last: newest folder
            entries[key] = entry
            self._data[folder_key] = entries
            while len(self._data) > MAX_FOLDERS:
                self._data.pop(next(iter(self._data)))
            self._save()

    def forget(self, folder: Path, key: str) -> None:
        """Forget one photo (its Copy was undone, or its copy is gone)."""
        with self._lock:
            entries = self._data.get(str(folder))
            if not entries or key not in entries:
                return
            del entries[key]
            if not entries:
                del self._data[str(folder)]
            self._save()

    def prune(self, folder: Path, keep: "set[str]") -> None:
        """Drop the entries of ``folder`` whose photo is not among ``keep`` (moved or trashed away)."""
        with self._lock:
            entries = self._data.get(str(folder))
            if not entries:
                return
            gone = [k for k in entries if k not in keep]
            if not gone:
                return
            for k in gone:
                del entries[k]
            if not entries:
                del self._data[str(folder)]
            self._save()
