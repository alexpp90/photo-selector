"""Exceptions of the culling file operations (re-exported by ``core.file_ops``)."""

from __future__ import annotations

from typing import Optional

from photo_selector_linux.core.models import CullingRecord


class FileCullingError(Exception):
    """Base exception for culling file operation failures."""

    def __init__(self, message: str, partial_record: Optional[CullingRecord] = None):
        super().__init__(message)
        self.partial_record = partial_record


class InsufficientDiskSpaceError(FileCullingError):
    """Raised when destination filesystem has insufficient free capacity."""
