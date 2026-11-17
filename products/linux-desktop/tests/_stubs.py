"""One definition of "GTK is stubbed", shared by the test modules that need real widgets.

``conftest.py`` replaces ``gi`` with ``MagicMock`` stubs when PyGObject or a display server is
missing. A test module asks whether the module under test ended up on the stubs, and skips what
needs real widgets (Linux CI runs them under ``xvfb-run``).
"""

from unittest.mock import MagicMock


def is_stubbed(module) -> bool:
    """True when ``module.Gtk`` is a stub, or the module reports no PyGObject (``HAS_GI`` False)."""
    return isinstance(module.Gtk, MagicMock) or not getattr(module, "HAS_GI", True)
