"""The Markdown table merge driver, and the real git merge it is registered for."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
import md_table_merge as m  # noqa: E402

GIT_ENV = {
    "GIT_CONFIG_COUNT": "3", "GIT_CONFIG_KEY_0": "gc.auto", "GIT_CONFIG_VALUE_0": "0",
    "GIT_CONFIG_KEY_1": "maintenance.auto", "GIT_CONFIG_VALUE_1": "false",
    "GIT_CONFIG_KEY_2": "commit.gpgsign", "GIT_CONFIG_VALUE_2": "false",
    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid",
}
BASE = ("# Parity\n\n| Feature | Jackpix | Frameduel | Lensjet |\n|---|---|---|---|\n"
        "| Settings survive | pending | pending | pending |\n| Go to | pending | pending | pending |\n\nend\n")


class MergeTextsTest(unittest.TestCase):
    def test_different_cells_of_one_row_merge(self):
        ours = BASE.replace("Settings survive | pending | pending", "Settings survive | done | pending")
        theirs = BASE.replace("Settings survive | pending | pending | pending", "Settings survive | pending | pending | ok")
        text, conflict = m.merge_texts(BASE, ours, theirs)
        self.assertFalse(conflict)
        self.assertIn("| Settings survive | done | pending | ok |", text)

    def test_the_same_cell_changed_two_ways_conflicts(self):
        ours = BASE.replace("Go to | pending", "Go to | a")
        theirs = BASE.replace("Go to | pending", "Go to | b")
        text, conflict = m.merge_texts(BASE, ours, theirs)
        self.assertTrue(conflict)
        self.assertIn("<<<<<<<", text)

    def test_a_prose_conflict_is_kept(self):
        _, conflict = m.merge_texts(BASE, BASE.replace("end", "ours"), BASE.replace("end", "theirs"))
        self.assertTrue(conflict)


class RealGitMergeTest(unittest.TestCase):
    def test_two_branches_changing_different_cells_merge_without_conflict(self):
        tmp = Path(tempfile.mkdtemp(prefix="pst-mdmerge-")).resolve()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        env = {**os.environ, **GIT_ENV}
        git = lambda *a, check=True: subprocess.run(["git", *a], cwd=tmp, env=env, capture_output=True,  # noqa: E731
                                                    text=True, check=check)
        (tmp / "scripts").mkdir()
        shutil.copy(REPO / "scripts" / "md_table_merge.py", tmp / "scripts")
        (tmp / "docs" / "shared").mkdir(parents=True)
        shutil.copy(REPO / ".gitattributes", tmp / ".gitattributes")
        table = tmp / "docs" / "shared" / "FEATURE_PARITY.md"
        table.write_text(BASE, encoding="utf-8")
        git("init", "-q", "-b", "main")
        subprocess.run([sys.executable, "scripts/md_table_merge.py", "install"], cwd=tmp, env=env, check=True,
                       capture_output=True)
        git("add", "-A")
        git("commit", "-q", "-m", "base")
        git("checkout", "-q", "-b", "a")
        table.write_text(BASE.replace("Settings survive | pending", "Settings survive | done"), encoding="utf-8")
        git("commit", "-qam", "a")
        git("checkout", "-q", "main")
        git("checkout", "-q", "-b", "b")
        table.write_text(BASE.replace("Settings survive | pending | pending | pending",
                                      "Settings survive | pending | pending | ok"), encoding="utf-8")
        git("commit", "-qam", "b")
        result = git("merge", "a", "-m", "merge", check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("| Settings survive | done | pending | ok |", table.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
