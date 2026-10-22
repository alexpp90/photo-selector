"""Unit tests for scripts/check_private_paths.py."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import check_private_paths as cpp  # noqa: E402


class ViolationsTest(unittest.TestCase):
    def test_claude_outputs_is_denied(self):
        found = cpp.violations(["Claude outputs/x", "Claude outputs/history-rewrite/a.txt"])
        self.assertEqual(len(found), 2)

    def test_traceability_script_is_denied(self):
        found = cpp.violations(["scripts/verify_requirements_traceability.py"])
        self.assertEqual(len(found), 1)

    def test_reviews_and_handover_are_denied(self):
        found = cpp.violations([
            "docs/reviews/CHANGE_REVIEW_2026-10-21.md",
            "docs/reviews/audit-2026-10-21/summary.md",
            "docs/handover/audit-2026-10-21/handoff.md",
            "docs/handover/notes.txt",
        ])
        self.assertEqual(len(found), 4)
        for f in found:
            self.assertIn("maintainer reviews and handover material", f)

    def test_public_paths_pass(self):
        self.assertEqual(cpp.violations(["README.md", "docs/shared/FILE_OPERATIONS.md"]), [])

    def test_similar_names_are_not_denied(self):
        self.assertEqual(cpp.violations([
            "docs/Claude outputs.md",
            "Claude outputs.md",
            "docs/reviews.md",
            "docs/handover.md",
        ]), [])


class GitignoreTest(unittest.TestCase):
    def test_claude_outputs_is_ignored(self):
        repo = Path(__file__).resolve().parents[2]
        out = subprocess.run(
            ["git", "-C", str(repo), "check-ignore", "-q", "Claude outputs/x"], check=False
        )
        self.assertEqual(out.returncode, 0)


class TrackedFixtureTest(unittest.TestCase):
    def test_gate_fails_when_claude_outputs_is_tracked(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            (repo / "Claude outputs").mkdir()
            (repo / "Claude outputs" / "x").write_text("secret")
            subprocess.run(["git", "-C", str(repo), "add", "-f", "."], check=True)
            original = cpp.REPO_ROOT
            cpp.REPO_ROOT = repo
            try:
                found = cpp.violations(cpp.list_paths(staged=False))
            finally:
                cpp.REPO_ROOT = original
            self.assertEqual(len(found), 1)
            self.assertIn("Claude outputs/x", found[0])


if __name__ == "__main__":
    unittest.main()
