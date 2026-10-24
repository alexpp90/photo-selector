#!/usr/bin/env python3
"""Can this host reproduce the legacy Desktop visual baselines?

The baselines in products/desktop/tests/visual/baselines/ are screenshots rendered under Xvfb
by desktop.yml's `visual` job, so they hold that runner image's font build. Another Linux
renders the same widgets with different glyphs: measured 2026-10-24 on Debian 13, eight of nine
tests differ by 0.9-3.9 % of pixels, all on text, against the 0.5 % threshold. A failure there
says nothing about the code, so scripts/run_tests.sh reports the gate SKIPPED on such a host.

Exit 0: run the gate (this is the baseline image, or PST_VISUAL_ANY_HOST=1).
Exit 3: skip it; stdout carries the reason for the summary line.

Stdlib only: run_tests.sh calls it with the bare system python3.
"""

from __future__ import annotations

import os
from pathlib import Path
import sys
from typing import Mapping

# The image desktop.yml's `visual` job runs on: `runs-on: ubuntu-latest`, which the job log's
# "Runner Image" group names as ubuntu-24.04. When GitHub moves ubuntu-latest, the baselines
# are re-rendered from a CI run (docs/build/CI_PARITY.md) — update this in the same commit.
BASELINE_HOST = "ubuntu-24.04"

OS_RELEASE = Path("/etc/os-release")
FORCE_ENV = "PST_VISUAL_ANY_HOST"
SKIP_EXIT = 3


def parse_os_release(text: str) -> dict[str, str]:
    """KEY=value lines of os-release(5); values may be single- or double-quoted."""
    fields: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        fields[key] = value
    return fields


def host_id(os_release: Path = OS_RELEASE) -> str:
    """`<ID>-<VERSION_ID>`, e.g. "debian-13"; "unknown" when os-release is unreadable."""
    try:
        fields = parse_os_release(os_release.read_text(encoding="utf-8"))
    except OSError:
        return "unknown"
    os_id = fields.get("ID", "unknown")
    version = fields.get("VERSION_ID", "")
    return f"{os_id}-{version}" if version else os_id


def skip_reason(os_release: Path = OS_RELEASE, env: Mapping[str, str] = os.environ) -> str | None:
    """None when the gate should run here, else the reason it cannot be judged."""
    if env.get(FORCE_ENV) == "1":
        return None
    host = host_id(os_release)
    if host == BASELINE_HOST:
        return None
    return (f"baselines are rendered on CI's {BASELINE_HOST} image and this host is {host}, "
            f"whose fonts differ; CI job 'Visual regression tests' covers this "
            f"({FORCE_ENV}=1 runs it anyway)")


def main() -> int:
    reason = skip_reason()
    if reason is None:
        return 0
    print(reason)
    return SKIP_EXIT


if __name__ == "__main__":
    sys.exit(main())
