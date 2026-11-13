"""Every product workflow reports a required check on every pull request (decision 8, fallback).

GitHub refused a merge-queue ruleset on this user-owned repository, so ``main`` is protected by
required status checks and "require branches to be up to date". A workflow-level ``paths:`` filter
on ``pull_request`` would leave a required check pending forever on a PR that touches no path of
the product, so the filter moved into a ``changes`` job and each workflow ends in a ``gate`` job
that always runs and fails when any job it needs failed or was cancelled (a skipped job passes).
The names below are the checks the ruleset requires; ``docs/build/CI_PARITY.md`` lists them.
Parsed with the standard library only: the runner has no PyYAML.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

# workflow file -> name of its always-running gate job (the required check)
REQUIRED = {
    "desktop.yml": "Desktop CI gate",
    "android.yml": "Android CI gate",
    "macos.yml": "macOS Desktop CI gate",
    "linux.yml": "Linux Desktop CI gate",
    "repo-hygiene.yml": "README parity & private-path guard",
}
PRODUCT = ("desktop.yml", "android.yml", "macos.yml", "linux.yml")
# publishing job -> concurrency group (one run per store at a time, never cancelled)
PUBLISH_GROUPS = {
    "android.yml": {"publish-release": "publish-android-release", "publish-play": "publish-google-play"},
    "desktop.yml": {"release": "publish-desktop-release", "update-homebrew-taps": "publish-homebrew-tap-desktop"},
    "macos.yml": {"release": "publish-macos-release", "update-homebrew-taps": "publish-homebrew-tap-macos"},
    "linux.yml": {"publish-apt": "publish-apt", "github-release": "publish-linux-release"},
}


def job_blocks(text: str) -> dict[str, str]:
    blocks: dict[str, list[str]] = {}
    current = None
    in_jobs = False
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            current = match.group(1)
            blocks[current] = []
        elif current is not None:
            blocks[current].append(line)
    return {name: "\n".join(lines) for name, lines in blocks.items()}


def job_name(block: str) -> str | None:
    match = re.search(r"^    name:\s*(.+?)\s*$", block, re.MULTILINE)
    return match.group(1).strip("'\"") if match else None


def needs(block: str) -> set[str]:
    match = re.search(r"^    needs:\s*(.+)$", block, re.MULTILINE)
    return set(re.findall(r"[A-Za-z0-9_-]+", match.group(1).strip("[]"))) if match else set()


class RequiredChecksTest(unittest.TestCase):
    def load(self, name: str) -> tuple[str, dict[str, str]]:
        text = (WORKFLOWS / name).read_text(encoding="utf-8")
        return text, job_blocks(text)

    def test_the_gate_job_is_named_as_the_ruleset_requires_and_always_runs(self) -> None:
        for name in PRODUCT:
            with self.subTest(workflow=name):
                _, jobs = self.load(name)
                self.assertIn("gate", jobs)
                self.assertEqual(job_name(jobs["gate"]), REQUIRED[name])
                self.assertRegex(jobs["gate"], r"(?m)^    if:\s*always\(\)\s*$")

    def test_the_hygiene_check_runs_on_every_pull_request(self) -> None:
        text, jobs = self.load("repo-hygiene.yml")
        self.assertEqual(job_name(jobs["hygiene"]), REQUIRED["repo-hygiene.yml"])
        self.assertNotRegex(text, r"(?m)^    paths:")
        self.assertNotIn("\n    if:", jobs["hygiene"])

    def test_no_product_workflow_filters_pull_requests_by_path(self) -> None:
        for name in PRODUCT:
            with self.subTest(workflow=name):
                text, _ = self.load(name)
                on_block = text.split("\njobs:")[0]
                pr = re.search(r"(?ms)^  pull_request:\n(.*?)(?=^  [a-z_]+:|\Z)", on_block)
                self.assertIsNotNone(pr)
                assert pr is not None
                self.assertNotIn("paths:", pr.group(1), "a skipped workflow leaves a required check pending")

    def test_the_gate_waits_for_the_changes_job_and_every_pull_request_job(self) -> None:
        for name in PRODUCT:
            with self.subTest(workflow=name):
                _, jobs = self.load(name)
                gate_needs = needs(jobs["gate"])
                self.assertIn("changes", gate_needs)
                for job, block in jobs.items():
                    publishes = re.search(r"(?m)^    if:.*(github\.event_name == 'push'|startsWith\(github\.ref)", block)
                    if job in ("gate", "changes", "detect-changes") or publishes:
                        continue
                    self.assertIn(job, gate_needs, f"{job} runs on a pull request but the gate does not wait for it")

    def test_every_job_reaches_the_changes_job_through_needs(self) -> None:
        """An irrelevant pull request skips the whole chain: nothing may start without `changes`."""
        for name in PRODUCT:
            with self.subTest(workflow=name):
                _, jobs = self.load(name)

                def reaches(job: str, seen: frozenset[str] = frozenset()) -> bool:
                    return "changes" in needs(jobs[job]) or any(
                        reaches(n, seen | {job}) for n in needs(jobs[job]) if n in jobs and n not in seen)

                for job in jobs:
                    if job in ("changes", "gate", "detect-changes"):
                        continue
                    self.assertTrue(reaches(job), f"{job} would run on a pull request that touches nothing of the product")

    def test_the_gate_fails_on_failure_or_cancel_only(self) -> None:
        for name in PRODUCT:
            with self.subTest(workflow=name):
                _, jobs = self.load(name)
                self.assertIn("failure", jobs["gate"])
                self.assertIn("cancelled", jobs["gate"])
                self.assertNotIn('" skipped "', jobs["gate"], "a skipped job is an irrelevant one and passes")

    def test_publish_jobs_are_serialised_per_store_and_never_cancelled(self) -> None:
        for name, groups in PUBLISH_GROUPS.items():
            _, jobs = self.load(name)
            for job, group in groups.items():
                with self.subTest(workflow=name, job=job):
                    block = jobs[job]
                    self.assertRegex(block, rf"(?m)^    concurrency:\n      group: {re.escape(group)}\n      cancel-in-progress: false")

    def test_ci_parity_names_every_required_check(self) -> None:
        text = (ROOT / "docs" / "build" / "CI_PARITY.md").read_text(encoding="utf-8")
        for check in REQUIRED.values():
            with self.subTest(check=check):
                self.assertIn(f"`{check}`", text)


if __name__ == "__main__":
    unittest.main()
