"""The local mirror selects the products a change touches exactly as the workflows' ``paths:`` do.

``scripts/run_tests.sh`` reads ``git diff`` and decides which product gates run; each product
workflow does the same through ``on.push.paths`` / ``on.pull_request.paths``. Nothing tied the two
lists together, so they drifted: ``desktop.yml`` listed ``scripts/**`` (every Frameduel lab edit
rebuilt the legacy Desktop and republished its nightly) while the mirror had no ``scripts/`` arm,
and ``linux.yml`` missed a file its own gate reads (docs/build/CI_PARITY.md, "Automatic path
filtering"). Parsed with the standard library only: the runner has no PyYAML.
"""

from __future__ import annotations

import fnmatch
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
MIRROR = ROOT / "scripts" / "run_tests.sh"

PRODUCT_WORKFLOWS = {"desktop.yml": "desktop", "android.yml": "android", "macos.yml": "macos", "linux.yml": "linux"}
ALL_FLAGS = set(PRODUCT_WORKFLOWS.values())


def changes_filter_paths(text: str) -> list[str]:
    """The pull-request path list: ``relevant:`` of the ``changes`` job's paths-filter.

    ``on.pull_request.paths`` would leave a required check pending on a PR the filter skips, so
    the list moved into the ``changes`` job (``test_required_checks.py``).
    """
    match = re.search(r"(?m)^          filters: \|\n            relevant:\n((?:              - .*\n)+)", text)
    return [m.group(1) for m in re.finditer(r"- '?([^'\n]+)'?", match.group(1))] if match else []


def trigger_paths(text: str, event: str) -> list[str]:
    """The ``paths:`` list of ``on.<event>`` ("" entries never occur)."""
    paths: list[str] = []
    in_on = in_event = in_paths = False
    for line in text.splitlines():
        if re.match(r"^on:\s*$", line):
            in_on = True
            continue
        if not in_on:
            continue
        if line and not line.startswith((" ", "#")):
            break
        key = re.match(r"^  ([A-Za-z_]+):\s*(.*)$", line)
        if key:
            in_event, in_paths = key.group(1) == event, False
            continue
        if not in_event:
            continue
        if re.match(r"^    paths:\s*$", line):
            in_paths = True
        elif re.match(r"^    [A-Za-z_-]+:", line):
            in_paths = False
        elif in_paths:
            item = re.match(r"^      - ['\"]?([^'\"#]+?)['\"]?\s*(#.*)?$", line)
            if item:
                paths.append(item.group(1))
    return paths


def workflow_glob_matches(pattern: str, path: str) -> bool:
    """GitHub's filter globs: ``**`` crosses directories, ``*`` does not."""
    regex = ""
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            regex += ".*"
            i += 2
        elif pattern[i] == "*":
            regex += "[^/]*"
            i += 1
        else:
            regex += re.escape(pattern[i])
            i += 1
    return re.fullmatch(regex, path) is not None


def mirror_arms(text: str) -> list[tuple[list[str], set[str]]]:
    """The ``case "$f" in`` arms of run_tests.sh's change detection: (patterns, flags), in order."""
    start = text.index('case "$f" in')
    block = text[start:text.index("esac", start)]
    arms = []
    for line in block.splitlines()[1:]:
        arm = re.match(r"^\s*([^#)][^)]*)\)\s*(.*?)\s*;;\s*$", line)
        if arm:
            arms.append(([p.strip() for p in arm.group(1).split("|")], set(re.findall(r"has_(\w+)=true", arm.group(2)))))
    return arms


def mirror_selects(arms, path: str) -> set[str]:
    """Bash ``case``: the first matching arm wins; a cross-cutting change selects every product."""
    for patterns, flags in arms:
        if any(fnmatch.fnmatchcase(path, p) for p in patterns):
            return set(ALL_FLAGS) if "cross" in flags else set(flags)
    return set()


def sample_path(pattern: str) -> str:
    return pattern.replace("**", "x/y.txt").replace("*", "x")


class CiMirrorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.arms = mirror_arms(MIRROR.read_text(encoding="utf-8"))

    def test_parsers_read_a_fixture(self):
        text = "on:\n  push:\n    branches: [main]\n    paths:\n      - 'a/**'\n      - b.txt\n  pull_request:\n    paths:\n      - 'c/*'\njobs:\n"
        self.assertEqual(trigger_paths(text, "push"), ["a/**", "b.txt"])
        self.assertEqual(trigger_paths(text, "pull_request"), ["c/*"])
        arms = mirror_arms('case "$f" in\n  # note\n  a/*|*.spec) has_x=true ;;\n  b/c) has_x=true; has_y=true ;;\nesac\n')
        self.assertEqual(arms, [(["a/*", "*.spec"], {"x"}), (["b/c"], {"x", "y"})])
        self.assertTrue(workflow_glob_matches("a/**", "a/b/c.txt"))
        self.assertFalse(workflow_glob_matches("*.spec", "dir/x.spec"))

    def test_the_mirror_has_arms_for_every_product(self):
        selected = set().union(*(flags for _, flags in self.arms))
        self.assertTrue(ALL_FLAGS | {"cross"} <= selected, selected)

    def test_push_filter_and_pull_request_changes_filter_are_identical(self):
        for name in PRODUCT_WORKFLOWS:
            text = (WORKFLOWS / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertEqual(trigger_paths(text, "push"), changes_filter_paths(text))
                self.assertTrue(trigger_paths(text, "push"))
                self.assertEqual(trigger_paths(text, "pull_request"), [], "pull_request must not carry paths:")

    def test_every_workflow_path_selects_its_product_in_the_mirror(self):
        for name, flag in PRODUCT_WORKFLOWS.items():
            for pattern in trigger_paths((WORKFLOWS / name).read_text(encoding="utf-8"), "push"):
                with self.subTest(workflow=name, path=pattern):
                    self.assertIn(
                        flag, mirror_selects(self.arms, sample_path(pattern)),
                        f"{name} runs for {pattern} but run_tests.sh does not select '{flag}' for it",
                    )

    def test_every_product_arm_of_the_mirror_is_a_workflow_path(self):
        filters = {
            flag: trigger_paths((WORKFLOWS / name).read_text(encoding="utf-8"), "push")
            for name, flag in PRODUCT_WORKFLOWS.items()
        }
        for patterns, flags in self.arms:
            if "cross" in flags:
                continue
            for pattern in patterns:
                for flag in flags:
                    with self.subTest(path=pattern, product=flag):
                        self.assertTrue(
                            any(workflow_glob_matches(f, sample_path(pattern)) for f in filters[flag]),
                            f"run_tests.sh selects '{flag}' for {pattern} but its workflow's paths: do not list it",
                        )

    def test_no_workflow_runs_a_product_pipeline_for_all_of_scripts(self):
        # `scripts/**` made every Frameduel lab edit rebuild, republish and re-hash the legacy Desktop.
        for name in PRODUCT_WORKFLOWS:
            paths = trigger_paths((WORKFLOWS / name).read_text(encoding="utf-8"), "push")
            with self.subTest(workflow=name):
                self.assertNotIn("scripts/**", paths)

    def test_files_a_gate_reads_are_in_that_gates_filter(self):
        linux = trigger_paths((WORKFLOWS / "linux.yml").read_text(encoding="utf-8"), "push")
        # products/linux-desktop/tests/unit/test_packaging.py reads the APT key fingerprint from here.
        self.assertIn("docs/products/linux-desktop/INSTALL.md", linux)


if __name__ == "__main__":
    unittest.main()
