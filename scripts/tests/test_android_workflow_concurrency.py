"""A burst of pushes to ``main`` must not drop a pending ``android.yml`` run.

GitHub replaces a *pending* run of a concurrency group by the newest one even when
``cancel-in-progress`` is off. ``detect-changes`` diffs only the push's own range, so the module
the dropped run would have published is never built by the next run. Only pull requests may share
a group per ref (their superseded runs are meant to be cancelled); every other run is keyed by its
commit. Parsed with the standard library only: the runner has no PyYAML.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "android.yml"


def concurrency_block(text: str) -> str:
    match = re.search(r"^concurrency:\n((?:[ ]+.*\n|\n)+)", text, re.MULTILINE)
    assert match, "android.yml has no top-level concurrency block"
    return match.group(1)


class AndroidConcurrencyTest(unittest.TestCase):
    def test_non_pull_request_runs_have_a_group_of_their_own(self) -> None:
        group = re.search(r"^\s+group:\s*(.+)$", concurrency_block(WORKFLOW.read_text()), re.MULTILINE)
        self.assertIsNotNone(group)
        assert group is not None
        self.assertIn("github.sha", group.group(1), "main pushes must not share one concurrency group")
        self.assertIn("pull_request", group.group(1), "only a pull request may share a group per ref")

    def test_only_pull_requests_are_cancelled(self) -> None:
        cancel = re.search(r"^\s+cancel-in-progress:\s*(.+)$", concurrency_block(WORKFLOW.read_text()), re.MULTILINE)
        self.assertIsNotNone(cancel)
        assert cancel is not None
        self.assertIn("github.event_name == 'pull_request'", cancel.group(1))


if __name__ == "__main__":
    unittest.main()
