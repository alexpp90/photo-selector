"""REQ-DESK-CI.05: the archived Desktop runs Linux only on a pull request.

The three-OS test matrix and the visual job belong to tags, the weekly schedule and manual
dispatch (decision D-8(b)). Read as text, like test_ci_mirror.py, so the stdlib suffices.
"""

import json
import re
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "desktop.yml"


def job_block(text: str, name: str) -> str:
    match = re.search(r"^  %s:\n(.*?)(?=^  [a-z-]+:\n|\Z)" % re.escape(name), text, re.S | re.M)
    assert match, name
    return match.group(1)


class DesktopCadenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    def test_a_weekly_schedule_runs_the_full_pipeline(self):
        self.assertRegex(self.text, r"(?m)^  schedule:\n    - cron: '[^']+'")

    def test_the_test_matrix_comes_from_the_changes_job(self):
        block = job_block(self.text, "test")
        self.assertIn("matrix: ${{ fromJSON(needs.changes.outputs.matrix) }}", block)

    def test_the_computed_matrices_are_linux_only_and_three_os(self):
        linux = re.search(r"LINUX='(.*?)'", self.text).group(1)
        full = re.search(r"ALL='(.*?)'", self.text).group(1)
        self.assertEqual([c["os"] for c in json.loads(linux)["include"]], ["ubuntu-24.04"])
        self.assertEqual(
            [c["os"] for c in json.loads(full)["include"]], ["ubuntu-24.04", "macos-26", "windows-2025"])

    def test_pull_requests_and_main_get_the_linux_matrix(self):
        arms = re.search(r'case "\$GITHUB_EVENT_NAME:\$GITHUB_REF" in\n(.*?)esac', self.text, re.S).group(1)
        full_arm, other_arm = re.split(r"(?m)^\s+\*\)", arms)
        for event in ("schedule:*", "workflow_dispatch:*", "push:refs/tags/*"):
            self.assertIn(event, full_arm)
        self.assertNotIn("pull_request", full_arm)
        self.assertNotIn("refs/heads/main", full_arm)
        self.assertIn("full=false", other_arm)

    def test_the_visual_job_runs_only_on_the_full_pipeline(self):
        self.assertIn("needs.changes.outputs.full == 'true'", job_block(self.text, "visual"))

    def test_every_expression_has_balanced_parentheses(self):
        # An unbalanced `${{ }}` makes GitHub reject the whole file ("workflow file issue", 0 s run).
        for expr in re.findall(r"\$\{\{(.*?)\}\}", self.text):
            self.assertEqual(expr.count("("), expr.count(")"), expr)

    def test_the_release_build_does_not_need_the_visual_job_to_have_run(self):
        build = job_block(self.text, "build")
        self.assertIn("needs.test.result == 'success'", build)
        self.assertIn('["success","skipped"]', build)


if __name__ == "__main__":
    unittest.main()
