"""Every GitHub Actions job is bounded, and every pytest run in CI can dump a hung test's stacks.

A job without ``timeout-minutes`` runs until GitHub's 360-minute default, so a hang shows as
"pending" for hours (docs/build/CI_PARITY.md, "Reading a failed Actions run"). Parsed with the
standard library only: the runner has no PyYAML.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"
JOB_RE = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")


def jobs_without_timeout(text: str) -> list[str]:
    """Names of the jobs in a workflow file that declare no job-level ``timeout-minutes``."""
    missing: list[str] = []
    in_jobs = False
    current: str | None = None
    bounded = False
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        match = JOB_RE.match(line)
        if match:
            if current is not None and not bounded:
                missing.append(current)
            current, bounded = match.group(1), False
        elif re.match(r"^    timeout-minutes:\s*\d+", line):
            bounded = True
    if current is not None and not bounded:
        missing.append(current)
    return missing


def run_commands(text: str) -> list[str]:
    """The value of every ``run:`` key, continuation lines joined with spaces (comments dropped)."""
    commands: list[str] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        match = re.match(r"^(\s*)(- )?run:\s*(.*)$", lines[i])
        if not match:
            i += 1
            continue
        # Continuation lines are indented deeper than the `run` key itself.
        indent = len(match.group(1)) + (2 if match.group(2) else 0)
        head = match.group(3).strip()
        parts = [] if head in ("|", ">", ">-", "|-") else [head]
        i += 1
        while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
            stripped = lines[i].strip()
            if stripped and not stripped.startswith("#"):
                parts.append(stripped)
            i += 1
        commands.append(" ".join(parts))
    return commands


class WorkflowBoundsTest(unittest.TestCase):
    def test_parser_flags_a_job_without_timeout(self):
        text = (
            "on: push\njobs:\n  a:\n    runs-on: x\n    timeout-minutes: 5\n"
            "    steps:\n      - run: echo\n  b:\n    runs-on: x\n    steps:\n"
            "      - run: echo\n        timeout-minutes: 3\n"
        )
        self.assertEqual(jobs_without_timeout(text), ["b"])

    def test_every_job_declares_timeout_minutes(self):
        files = sorted(WORKFLOWS.glob("*.yml"))
        self.assertTrue(files, f"no workflows found under {WORKFLOWS}")
        for path in files:
            with self.subTest(workflow=path.name):
                self.assertEqual(jobs_without_timeout(path.read_text(encoding="utf-8")), [])

    def test_parser_reads_folded_run_blocks(self):
        text = "steps:\n  - run: >-\n      poetry run pytest\n      -q\n  # pytest in a comment\n  - run: echo\n"
        self.assertEqual(run_commands(text), ["poetry run pytest -q", "echo"])

    def test_every_ci_pytest_run_sets_faulthandler_timeout(self):
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for command in run_commands(path.read_text(encoding="utf-8")):
                if re.search(r"(^|\s)(poetry run |python3? -m )?pytest(\s|$)", command) and "install" not in command:
                    with self.subTest(workflow=path.name, command=command[:80]):
                        self.assertIn("faulthandler_timeout", command)


if __name__ == "__main__":
    unittest.main()
