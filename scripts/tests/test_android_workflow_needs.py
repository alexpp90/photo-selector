"""``android.yml``: the build runs beside the emulator job, publishing waits for it.

The ``build`` job used to ``need`` ``instrumented-tests``, so a main push spent ~10 minutes of
emulator time before the 6-8 minute build even started (run 42906683522: 581 s emulator, then
480 s build, then Play). Only publishing needs a green emulator run, so the build (compile, sign,
upload artifacts) starts after ``unit-tests`` and the publishing steps live in jobs that also
``need`` ``instrumented-tests``. Parsed with the standard library only: the runner has no PyYAML.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "android.yml"
JOB_RE = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")

# Steps that publish: they must never run in a job that does not wait for the emulator.
PUBLISHING_USES = ("wzieba/Firebase-Distribution-Github-Action", "softprops/action-gh-release", "r0adkll/upload-google-play")


def jobs(text: str) -> dict[str, str]:
    """Job name -> the text of its block."""
    blocks: dict[str, list[str]] = {}
    current = None
    in_jobs = False
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        match = JOB_RE.match(line)
        if match:
            current = match.group(1)
            blocks[current] = []
        elif current is not None:
            blocks[current].append(line)
    return {name: "\n".join(lines) for name, lines in blocks.items()}


def needs(block: str) -> set[str]:
    match = re.search(r"^    needs:\s*(.+)$", block, re.MULTILINE)
    if not match:
        return set()
    return set(re.findall(r"[A-Za-z0-9_-]+", match.group(1).strip("[]")))


class AndroidWorkflowNeedsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.jobs = jobs(WORKFLOW.read_text(encoding="utf-8"))

    def test_parser_reads_a_flow_list_and_a_single_name(self) -> None:
        self.assertEqual(needs("    needs: [a, b-c]\n"), {"a", "b-c"})
        self.assertEqual(needs("    needs: unit-tests\n"), {"unit-tests"})
        self.assertEqual(needs("    runs-on: x\n"), set())

    def test_the_emulator_job_still_waits_for_the_cheap_unit_job(self) -> None:
        self.assertIn("unit-tests", needs(self.jobs["instrumented-tests"]))

    def test_the_build_does_not_wait_for_the_emulator(self) -> None:
        self.assertNotIn("instrumented-tests", needs(self.jobs["build"]))
        self.assertIn("unit-tests", needs(self.jobs["build"]))

    def test_every_job_that_publishes_waits_for_the_emulator(self) -> None:
        publishing = [name for name, block in self.jobs.items()
                      if any(f"uses: {action}" in block for action in PUBLISHING_USES)
                      or "git/refs/tags/nightly-android" in block]
        self.assertIn("publish-play", publishing)
        self.assertGreater(len(publishing), 1, "the GitHub release / Firebase steps are publishing too")
        for name in publishing:
            with self.subTest(job=name):
                self.assertIn("instrumented-tests", needs(self.jobs[name]))
                self.assertNotEqual(name, "build")


if __name__ == "__main__":
    unittest.main()
