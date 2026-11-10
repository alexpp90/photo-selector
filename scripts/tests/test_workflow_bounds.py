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


PUBLISHING_JOB = re.compile(r"^(publish|release|github-release|update-homebrew)")


def job_conditions(text: str) -> dict[str, str]:
    """Each job's job-level ``if:`` expression ("" when the job has none)."""
    conditions: dict[str, str] = {}
    in_jobs = False
    current: str | None = None
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        match = JOB_RE.match(line)
        if match:
            current = match.group(1)
            conditions[current] = ""
        elif current is not None:
            cond = re.match(r"^    if:\s*(.*)$", line)
            if cond:
                conditions[current] = cond.group(1)
    return conditions


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

    # Tag-only or publishing jobs: a bound that is too low kills a publish halfway, so each cites
    # where it comes from. NOT_MEASURED jobs run only on a product's release tag; re-set them
    # from the first real tag run (3x its duration, at least 10 minutes).
    PUBLISH_JOBS = {
        "desktop.yml": ["release", "update-homebrew-taps"],
        "macos.yml": ["release", "update-homebrew-taps"],
        "android.yml": ["publish-play"],
        "linux.yml": ["publish-apt", "github-release"],
    }

    def test_publish_job_bounds_cite_a_measurement(self):
        for name, jobs in self.PUBLISH_JOBS.items():
            lines = (WORKFLOWS / name).read_text(encoding="utf-8").splitlines()
            for job in jobs:
                with self.subTest(workflow=name, job=job):
                    start = lines.index(f"  {job}:")
                    end = next((i for i in range(start + 1, len(lines)) if JOB_RE.match(lines[i])),
                               len(lines))
                    bound = next(i for i in range(start, end)
                                 if re.match(r"^    timeout-minutes:\s*\d+", lines[i]))
                    self.assertRegex(lines[bound - 1].strip(), r"^# (Not m|M)easured:")
                    minutes = int(lines[bound].split(":")[1])
                    self.assertGreaterEqual(minutes, 10)

    def test_parser_reads_folded_run_blocks(self):
        text = "steps:\n  - run: >-\n      poetry run pytest\n      -q\n  # pytest in a comment\n  - run: echo\n"
        self.assertEqual(run_commands(text), ["poetry run pytest -q", "echo"])

    def test_every_ci_pytest_run_sets_faulthandler_timeout(self):
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for command in run_commands(path.read_text(encoding="utf-8")):
                if re.search(r"(^|\s)(poetry run |python3? -m )?pytest(\s|$)", command) and "install" not in command:
                    with self.subTest(workflow=path.name, command=command[:80]):
                        self.assertIn("faulthandler_timeout", command)


    def test_parser_reads_job_conditions(self):
        text = "jobs:\n  a:\n    if: github.event_name == 'push'\n    steps:\n      - if: x\n  b:\n    runs-on: y\n"
        self.assertEqual(job_conditions(text), {"a": "github.event_name == 'push'", "b": ""})

    def test_publishing_jobs_never_run_on_a_manual_dispatch(self):
        # 2026-10-21: linux.yml publish-apt ran on any workflow_dispatch, from any branch and
        # without verify-tag, and pushed a signed .deb to the production APT repository.
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for job, condition in job_conditions(path.read_text(encoding="utf-8")).items():
                if PUBLISHING_JOB.match(job):
                    with self.subTest(workflow=path.name, job=job):
                        self.assertTrue(condition, "a publishing job needs an explicit if:")
                        self.assertNotIn("workflow_dispatch", condition)


    def test_no_two_workflows_publish_the_same_release_tag(self):
        # 2026-10-21 audit (CI-3): android.yml, desktop.yml and macos.yml all force-moved the one
        # `nightly` tag, so after a multi-product merge it pointed at whichever run finished last.
        owners: dict[str, set[str]] = {}
        for path in sorted(WORKFLOWS.glob("*.yml")):
            text = path.read_text(encoding="utf-8")
            tags = set(re.findall(r"git/refs/tags/([A-Za-z0-9._-]+)", text))
            tags |= set(re.findall(r"ref=refs/tags/([A-Za-z0-9._-]+)", text))
            tags |= set(re.findall(r"tag_name:[^\n]*?'([A-Za-z0-9._-]+)'", text))
            tags |= set(re.findall(r"tag_name: ([A-Za-z0-9._-]+)\s*$", text, flags=re.M))
            for tag in tags:
                owners.setdefault(tag, set()).add(path.name)
        shared = {tag: sorted(files) for tag, files in owners.items() if len(files) > 1}
        self.assertEqual(shared, {})

    def test_release_titles_come_from_release_py_not_a_hard_coded_name(self):
        # 2026-10-22: linux.yml titled the Blitzlens 0.2.0 release "Linux Desktop 0.2.0".
        # verify-tag writes the product's display name (`name`) next to `version`.
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for line in path.read_text(encoding="utf-8").splitlines():
                if re.match(r"^\s+name: .*outputs\.version", line):
                    with self.subTest(workflow=path.name, line=line.strip()):
                        self.assertIn("outputs.name", line)

    def test_third_party_actions_are_pinned_to_a_commit(self):
        # 2026-10-21 audit (CI-7): actions that receive release secrets ran whatever their moving
        # tag pointed at. GitHub's own `actions/*` and `gradle/actions` stay on major tags.
        unpinned = []
        for path in sorted(list(WORKFLOWS.glob("*.yml")) + list((WORKFLOWS.parent / "actions").rglob("*.yml"))):
            for ref in re.findall(r"^\s*(?:- )?uses:\s*([^\s#]+)", path.read_text(encoding="utf-8"), flags=re.M):
                if ref.startswith(("./", "actions/", "gradle/actions/")):
                    continue
                if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", ref):
                    unpinned.append(f"{path.name}: {ref}")
        self.assertEqual(unpinned, [])

    def test_runner_images_are_pinned(self):
        # 2026-11-03 (SET-CI-8): every job ran on a floating image, and GitHub had already announced
        # that `ubuntu-latest` moves to a new release. The flip would have landed on `main`
        # unannounced (apt package names, the emulator job's KVM step, the glibc the legacy Linux
        # binary is linked against). Moving to a newer image is one deliberate change.
        floating = []
        for path in sorted(WORKFLOWS.glob("*.yml")):
            for line in path.read_text(encoding="utf-8").splitlines():
                if re.search(r"(runs-on:|- os:)\s*[\w.-]*-latest\b", line):
                    floating.append(f"{path.name}: {line.strip()}")
        self.assertEqual(floating, [])

    def test_apt_steps_retry_and_are_bounded(self):
        # 2026-10-23: one `apt-get update` stalled for 15 minutes and consumed 80 % of a 20-minute
        # job bound. Retries are the cure; a 5-minute step bound makes a stall cheap.
        problems = []
        for path in sorted(WORKFLOWS.glob("*.yml")):
            steps = re.split(r"(?m)^\s*- (?=name:|uses:|run:)", path.read_text(encoding="utf-8"))
            for step in steps:
                for command in run_commands(step):
                    for apt in re.findall(r"apt-get\s+(?:-\S+\s+)*(?:update|install)", command):
                        if "Acquire::Retries=3" not in apt:
                            problems.append(f"{path.name}: {apt} has no Acquire::Retries=3")
                if "apt-get" in step and not re.search(r"(?m)^\s+timeout-minutes:\s*[1-5]\s*$", step):
                    problems.append(f"{path.name}: an apt-get step has no step-level timeout-minutes of at most 5")
        self.assertEqual(problems, [])

    def test_actions_cache_is_not_the_node_20_release(self):
        # The composite action under .github/actions was the one place still on actions/cache@v4.
        for path in sorted(list(WORKFLOWS.glob("*.yml")) + list((WORKFLOWS.parent / "actions").rglob("*.yml"))):
            with self.subTest(file=path.name):
                self.assertNotIn("actions/cache@v4", path.read_text(encoding="utf-8"))

    def test_the_nightly_tag_move_cannot_fail_a_published_run(self):
        # 2026-10-22: a 403 on the tag step failed Desktop, macOS and Android runs after (or before)
        # their assets were published. The step is a convenience for the nightly link.
        for name in ("desktop.yml", "macos.yml"):
            text = (WORKFLOWS / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                tag_step = text.index("- name: Move nightly tag to this commit")
                publish = text.index("- name: Update GitHub Release")
                self.assertGreater(tag_step, publish, "the tag moves after the release is published")
                self.assertIn("continue-on-error: true", text[tag_step:tag_step + 400])


DEPENDABOT = WORKFLOWS.parent / "dependabot.yml"


class DependabotCoverageTest(unittest.TestCase):
    def test_python_projects_and_composite_actions_are_covered(self):
        # 2026-11-03 (LD-S-2, SET-CI-10): Dependabot scanned neither Poetry project (the locked
        # Pillow carried ~60 advisories nobody was told about) nor .github/actions/*.
        text = DEPENDABOT.read_text(encoding="utf-8")
        blocks = re.split(r"(?m)^  - package-ecosystem: ", text)[1:]
        by_ecosystem = {block.split()[0]: block for block in blocks}
        for directory in ("/products/desktop", "/products/linux-desktop"):
            with self.subTest(directory=directory):
                self.assertIn(directory, by_ecosystem["pip"])
        self.assertIn("/.github/actions/*", by_ecosystem["github-actions"])
        self.assertIn("version-update:semver-major", by_ecosystem["pip"])

    def test_every_composite_action_directory_is_in_the_glob(self):
        actions = WORKFLOWS.parent / "actions"
        self.assertTrue(list(actions.glob("*/action.yml")), "no composite action found")
        self.assertIn("/.github/actions/*", DEPENDABOT.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
