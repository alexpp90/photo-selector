"""Unit tests for scripts/release.py, run against throwaway git repositories."""

from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import release as rel  # noqa: E402

GIT_ENV = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid",
           "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull}

DEBIAN_CHANGELOG = """photo-selector-linux (0.1.0-1) trixie; urgency=medium

  * Initial release for Debian 13 (Trixie).

 -- Jane Doe <jane@example.invalid>  Sat, 10 Oct 2026 12:00:00 +0000
"""

GRADLE = """    defaultConfig {{
        versionName = System.getenv("{env}")
            ?: System.getenv("VERSION_NAME")
            ?: "{v}"
    }}
"""


def write(root: Path, rel_path: str, text: str) -> None:
    path = root / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class Repo:
    """A minimal checkout with every product's version sources, changelog and workflow."""

    def __init__(self, tmp: str):
        self.root = Path(tmp)
        self.git("init", "-q", "-b", "main")
        write(self.root, "products/android/phototok/build.gradle.kts",
              GRADLE.format(env="PHOTOTOK_VERSION_NAME", v="0.5.0"))
        write(self.root, "products/android/android-desktop/build.gradle.kts",
              GRADLE.format(env="TOOLBOX_VERSION_NAME", v="0.5.0"))
        write(self.root, "products/macos-desktop/VERSION", "0.1.0\n")
        write(self.root, "products/linux-desktop/pyproject.toml",
              '[project]\nname = "photo-selector-linux"\nversion = "0.1.0"\n')
        write(self.root, "products/linux-desktop/src/photo_selector_linux/__init__.py",
              '"""Linux."""\n\n__version__ = "0.1.0"\n')
        write(self.root, "products/linux-desktop/debian/changelog", DEBIAN_CHANGELOG)
        write(self.root, "products/desktop/pyproject.toml",
              '[project]\nname = "photo-selector-toolbox"\nversion = "0.5.0"\n')
        write(self.root, "products/desktop/src/photo_selector_toolbox/__init__.py",
              'try:\n    pass\nexcept Exception:\n    __version__ = "0.5.0"\n')
        workflows = {}
        for product in rel.PRODUCTS:
            write(self.root, product.changelog, rel.changelog_header(product))
            workflows.setdefault(product.workflow, []).append(f'"{product.tag_prefix}*"')
        for workflow, prefixes in workflows.items():
            write(self.root, workflow, f"on:\n  push:\n    tags: [{', '.join(prefixes)}]\n")
        self.commit("chore: initial layout")

    def git(self, *args: str, stdin: str = "") -> str:
        env = dict(os.environ, **GIT_ENV)
        return subprocess.run(["git", "-C", str(self.root), *args], env=env, check=True,
                              capture_output=True, text=True, input=stdin).stdout

    def commit(self, message: str, files: dict | None = None) -> str:
        for path, text in (files or {}).items():
            write(self.root, path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD").strip()

    def merge_pr(self, number: int, title: str, files: dict) -> None:
        """A GitHub-style PR merge: --no-ff, PR title on the merge commit's body."""
        self.git("switch", "-q", "-c", f"pr{number}")
        self.commit(f"wip {number}", files)
        self.git("switch", "-q", "main")
        self.git("merge", "-q", "--no-ff", f"pr{number}",
                 "-m", f"Merge pull request #{number} from someone/pr{number}", "-m", title)

    def tag(self, name: str) -> None:
        self.git("tag", "-a", name, "-m", name)


class VersionTest(unittest.TestCase):
    def test_bump_levels(self):
        self.assertEqual(rel.bump((1, 2, 3), "patch"), (1, 2, 4))
        self.assertEqual(rel.bump((1, 2, 3), "minor"), (1, 3, 0))
        self.assertEqual(rel.bump((1, 2, 3), "major"), (2, 0, 0))

    def test_breaking_before_1_0_raises_minor(self):
        self.assertEqual(rel.bump((0, 4, 2), "major"), (0, 5, 0))

    def test_parse_rejects_two_part_versions(self):
        with self.assertRaises(rel.ReleaseError):
            rel.parse_version("1.0")

    def test_classify(self):
        self.assertEqual(rel.classify("feat(linux): x", ""), ("feature", False))
        self.assertEqual(rel.classify("fix: x", ""), ("fix", False))
        self.assertEqual(rel.classify("perf(macos): x", ""), ("fix", False))
        self.assertEqual(rel.classify("docs: x", ""), ("maintenance", False))
        self.assertEqual(rel.classify("release(phototok): 0.6.0", ""), ("ignored", False))
        self.assertEqual(rel.classify("feat!: drop x", ""), ("feature", True))
        self.assertEqual(rel.classify("fix: x", "BREAKING CHANGE: y"), ("fix", True))
        # Unconventional titles may be user-visible: counted as fixes, never ignored.
        self.assertEqual(rel.classify("🎨 UX: add hints", ""), ("fix", False))

    def test_ships_excludes_tests_benchmarks_and_markdown(self):
        tok = rel.BY_SLUG["phototok"]
        self.assertTrue(rel.ships(tok, "products/android/phototok/src/A.kt"))
        self.assertTrue(rel.ships(tok, "products/android/core/src/Exif.kt"))
        self.assertTrue(rel.ships(tok, "products/android/saf/src/SafTransferEngine.kt"))
        self.assertTrue(rel.ships(rel.BY_SLUG["android-desktop"], "products/android/saf/src/SafTransferEngine.kt"))
        self.assertFalse(rel.ships(tok, "products/android/saf/tests/unit/SafTest.kt"))
        self.assertFalse(rel.ships(tok, "products/android/phototok/tests/unit/ATest.kt"))
        self.assertFalse(rel.ships(tok, "products/android/phototok/README.md"))
        self.assertFalse(rel.ships(tok, "products/android/android-desktop/src/A.kt"))
        self.assertFalse(rel.ships(rel.BY_SLUG["desktop"], "products/desktop/benchmarks/b.py"))

    def test_tag_parsing_keeps_desktop_and_android_desktop_apart(self):
        self.assertEqual(rel.product_for_tag("desktop-v0.5.1")[0].slug, "desktop")
        self.assertEqual(rel.product_for_tag("refs/tags/android-desktop-v1.0.0")[0].slug,
                         "android-desktop")
        with self.assertRaises(rel.ReleaseError):
            rel.product_for_tag("v0.5.0")


class RepoTestCase(unittest.TestCase):
    def setUp(self):
        # release.py runs git itself (e.g. `git tag -a`), so the identity and the isolation
        # from the user's git config must hold for the whole process, not only Repo.git():
        # CI runners have no user.name / user.email.
        env = mock.patch.dict(os.environ, GIT_ENV)
        env.start()
        self.addCleanup(env.stop)
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)
        self.root = self.repo.root

    def tearDown(self):
        self._tmp.cleanup()


class StatusTest(RepoTestCase):
    def test_counts_per_pr_and_per_product(self):
        self.repo.tag("v0.4.0")
        self.repo.merge_pr(1, "feat(phototok): swipe to archive",
                           {"products/android/phototok/src/A.kt": "a"})
        self.repo.merge_pr(2, "fix(core): EXIF orientation",
                           {"products/android/core/src/E.kt": "e"})
        self.repo.merge_pr(3, "test(phototok): more coverage",
                           {"products/android/phototok/tests/unit/T.kt": "t"})
        self.repo.merge_pr(4, "docs(phototok): readme",
                           {"products/android/phototok/README.md": "r"})
        self.repo.merge_pr(5, "fix(linux): trash",
                           {"products/linux-desktop/src/photo_selector_linux/t.py": "x"})

        tok = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertEqual([e.pr for e in tok.features], [1])
        self.assertEqual([e.pr for e in tok.fixes], [2])
        self.assertEqual(tok.maintenance, [])      # tests and docs do not ship
        self.assertEqual(tok.last_tag, "v0.4.0")
        self.assertTrue(tok.due)
        self.assertEqual(rel.fmt(tok.next_version), "0.5.0")

        # :core is shared, so the EXIF fix counts for Android Desktop too.
        andd = rel.status(rel.BY_SLUG["android-desktop"], self.root, "HEAD")
        self.assertEqual([e.pr for e in andd.fixes], [2])
        self.assertFalse(andd.due)                 # one recent fix is not enough

        linux = rel.status(rel.BY_SLUG["linux-desktop"], self.root, "HEAD")
        self.assertIsNone(linux.last_tag)          # no legacy fallback for native desktops
        self.assertEqual(rel.fmt(linux.next_version), "0.1.0")

    def test_product_tag_wins_over_legacy_tag(self):
        self.repo.tag("v0.4.0")
        self.repo.commit("feat(phototok): a", {"products/android/phototok/src/A.kt": "a"})
        self.repo.tag("phototok-v0.5.0")
        self.repo.commit("fix(phototok): b", {"products/android/phototok/src/A.kt": "b"})
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertEqual(s.last_tag, "phototok-v0.5.0")
        self.assertEqual([e.title for e in s.entries], ["fix(phototok): b"])
        self.assertEqual(rel.fmt(s.next_version), "0.5.1")

    def test_due_by_fix_count_and_age(self):
        self.repo.tag("phototok-v0.5.0")
        for i in range(rel.DUE_FIXES - 1):
            self.repo.commit(f"fix(phototok): {i}", {"products/android/phototok/src/A.kt": str(i)})
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertFalse(s.due)
        for e in s.fixes:
            e.timestamp -= rel.DUE_AGE_DAYS * 86400
        self.assertTrue(s.due)
        self.assertIn("waiting", s.due_reasons[0])

    def test_breaking_change_raises_level(self):
        self.repo.tag("macos-desktop-v1.2.0")
        write(self.root, "products/macos-desktop/VERSION", "1.2.0\n")
        self.repo.commit("feat(macos)!: new library format",
                         {"products/macos-desktop/src/L.swift": "l"})
        s = rel.status(rel.BY_SLUG["macos-desktop"], self.root, "HEAD")
        self.assertEqual(s.level, "major")
        self.assertEqual(rel.fmt(s.next_version), "2.0.0")

    def test_prepared_in_tree_version_is_kept(self):
        self.repo.tag("v0.4.0")
        self.repo.commit("fix(desktop): a", {"products/desktop/src/photo_selector_toolbox/a.py": "a"})
        s = rel.status(rel.BY_SLUG["desktop"], self.root, "HEAD")
        self.assertTrue(s.prepared)
        self.assertEqual(rel.fmt(s.next_version), "0.5.0")   # not 0.4.1

    def test_a_merged_but_untagged_release_is_flagged(self):
        # The v0.5.0 failure mode: version bumped on main past the last tag, no tag pushed.
        self.repo.tag("v0.4.0")
        self.repo.commit("feat(phototok): a", {"products/android/phototok/src/A.kt": "a"})
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertTrue(s.unfinished_bump)          # 0.5.0 in tree, no changelog section
        self.assertFalse(s.awaiting_tag)
        rel.prepare(rel.BY_SLUG["phototok"], self.root, today=dt.date(2026, 10, 17), ref="HEAD")
        self.repo.commit("release(phototok): 0.5.0")
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertTrue(s.awaiting_tag)
        self.assertFalse(s.unfinished_bump)
        self.repo.tag("phototok-v0.5.0")
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "HEAD")
        self.assertFalse(s.awaiting_tag or s.unfinished_bump or s.due)

    def test_versions_are_read_at_the_ref_not_from_an_old_checkout(self):
        # 2026-10-21: a checkout on a stale branch reported "at origin/main" with the
        # branch's 0.5.0 although main had 0.6.0 prepared, and suggested 0.5.0.
        self.repo.tag("v0.4.0")
        self.repo.commit("feat(phototok): a", {"products/android/phototok/src/A.kt": "a"})
        rel.prepare(rel.BY_SLUG["phototok"], self.root, version=(0, 6, 0),
                    today=dt.date(2026, 10, 20), ref="HEAD")
        self.repo.commit("release(phototok): 0.6.0")
        self.repo.git("switch", "-q", "-c", "stale", "HEAD~1")
        s = rel.status(rel.BY_SLUG["phototok"], self.root, "main")
        self.assertEqual(s.in_tree, (0, 6, 0))
        self.assertTrue(s.awaiting_tag)
        self.assertEqual(rel.fmt(s.next_version), "0.6.0")
        here = rel.status(rel.BY_SLUG["phototok"], self.root, "main", worktree=True)
        self.assertEqual(here.in_tree, (0, 5, 0))

    def test_disagreeing_sources_are_reported(self):
        write(self.root, "products/linux-desktop/pyproject.toml",
              '[project]\nversion = "0.2.0"\n')
        version, problems = rel.read_versions(rel.BY_SLUG["linux-desktop"], self.root)
        self.assertIsNone(version)
        self.assertIn("disagree", problems[0])


class CheckTest(RepoTestCase):
    def test_fixture_is_consistent(self):
        self.assertEqual(rel.check_problems(self.root), [])

    def test_missing_trigger_and_changelog_fail(self):
        write(self.root, ".github/workflows/macos.yml", 'on:\n  push:\n    tags: ["v*"]\n')
        (self.root / "products/desktop/CHANGELOG.md").unlink()
        problems = rel.check_problems(self.root)
        self.assertTrue(any("macos-desktop" in p and "trigger" in p for p in problems))
        self.assertTrue(any("desktop:" in p and "changelog" in p for p in problems))


class PrepareTest(RepoTestCase):
    def test_bumps_sources_and_drafts_changelog(self):
        self.repo.tag("phototok-v0.5.0")
        self.repo.merge_pr(7, "feat(phototok): pinch to compare",
                           {"products/android/phototok/src/A.kt": "a"})
        version, changed = rel.prepare(rel.BY_SLUG["phototok"], self.root,
                                       today=dt.date(2026, 10, 17), ref="HEAD")
        self.assertEqual(rel.fmt(version), "0.6.0")
        gradle = (self.root / "products/android/phototok/build.gradle.kts").read_text()
        self.assertIn('?: "0.6.0"', gradle)
        log = (self.root / "products/android/phototok/CHANGELOG.md").read_text()
        self.assertIn("## [Unreleased]\n\n## [0.6.0] - 2026-10-17", log)
        self.assertIn("- Pinch to compare (#7)", rel.changelog_section(log, "0.6.0"))
        self.assertEqual(rel.read_versions(rel.BY_SLUG["phototok"], self.root)[0], (0, 6, 0))
        self.assertIn("products/android/phototok/CHANGELOG.md", changed)

    def test_written_unreleased_notes_are_kept(self):
        self.repo.tag("phototok-v0.5.0")
        self.repo.commit("fix(phototok): a", {"products/android/phototok/src/A.kt": "a"})
        path = self.root / "products/android/phototok/CHANGELOG.md"
        path.write_text(path.read_text() + "\n### Fixed\n- Crash on empty folders.\n")
        rel.prepare(rel.BY_SLUG["phototok"], self.root, today=dt.date(2026, 10, 17), ref="HEAD")
        self.assertEqual(rel.changelog_section(path.read_text(), "0.5.1"),
                         "### Fixed\n- Crash on empty folders.")

    def test_linux_gets_a_debian_changelog_entry(self):
        self.repo.commit("feat(linux): a", {"products/linux-desktop/src/photo_selector_linux/a.py": "a"})
        version, _ = rel.prepare(rel.BY_SLUG["linux-desktop"], self.root, version=(0, 2, 0),
                                 today=dt.date(2026, 10, 17), ref="HEAD")
        deb = (self.root / "products/linux-desktop/debian/changelog").read_text()
        self.assertTrue(deb.startswith("photo-selector-linux (0.2.0-1) trixie; urgency=medium"))
        self.assertIn(" -- Jane Doe <jane@example.invalid>  Sat, 17 Oct 2026 12:00:00 +0000", deb)
        self.assertIn("(0.1.0-1)", deb)            # the previous entry stays
        self.assertEqual(rel.read_versions(rel.BY_SLUG["linux-desktop"], self.root),
                         ((0, 2, 0), []))

    def test_never_writes_a_version_below_the_in_tree_one(self):
        # PhotoTok today: last tag v0.4.0, in-tree 0.5.0 — a patch bump must not give 0.4.1.
        self.repo.tag("v0.4.0")
        self.repo.commit("fix(phototok): a", {"products/android/phototok/src/A.kt": "a"})
        with self.assertRaises(rel.ReleaseError) as ctx:
            rel.prepare(rel.BY_SLUG["phototok"], self.root, level="patch", ref="HEAD")
        self.assertIn("below the in-tree 0.5.0", str(ctx.exception))
        version, _ = rel.prepare(rel.BY_SLUG["phototok"], self.root, ref="HEAD",
                                 today=dt.date(2026, 10, 17))
        self.assertEqual(version, (0, 5, 0))

    def test_refuses_versions_not_newer_than_released(self):
        self.repo.tag("phototok-v0.5.0")
        with self.assertRaises(rel.ReleaseError):
            rel.prepare(rel.BY_SLUG["phototok"], self.root, version=(0, 5, 0), ref="HEAD")


class TagTest(RepoTestCase):
    def _release_commit(self):
        self.repo.tag("macos-desktop-v0.0.9")
        self.repo.commit("feat(macos): a", {"products/macos-desktop/src/A.swift": "a"})
        rel.prepare(rel.BY_SLUG["macos-desktop"], self.root, today=dt.date(2026, 10, 17),
                    ref="HEAD")
        head = self.repo.commit("release(macos-desktop): 0.1.0")
        self.repo.git("update-ref", "refs/remotes/origin/main", head)

    def _record(self, status: str):
        tree = self.repo.git("rev-parse", "HEAD^{tree}").strip()
        directory = self.root / ".git" / "pst-verification"
        directory.mkdir(exist_ok=True)
        (directory / "r.json").write_text(json.dumps({
            "recorded_at": "2026-10-17T00:00:00Z", "git": {"tree": tree},
            "gates": [{"status": status, "name": "swift test", "detail": "", "ci": "macos.yml:test",
                       "unrunnable": False, "group": "macos"}]}))

    def test_refuses_without_a_passing_run(self):
        self._release_commit()
        with self.assertRaises(rel.ReleaseError) as ctx:
            rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False)
        self.assertIn("no passing run", str(ctx.exception))
        self._record("FAIL")
        with self.assertRaises(rel.ReleaseError):
            rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False)

    def test_tags_a_verified_tree(self):
        self._release_commit()
        self._record("PASS")
        name = rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False)
        self.assertEqual(name, "macos-desktop-v0.1.0")
        message = self.repo.git("tag", "-l", "--format=%(contents)", name)
        self.assertIn("Verified: ./scripts/run_tests.sh --macos passed", message)
        self.assertEqual(rel.verify_tag(name, self.root)[1], (0, 1, 0))

    def test_accept_untested_is_written_into_the_tag(self):
        self._release_commit()
        name = rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False,
                       accept_untested="no Mac here; macos.yml runs swift test")
        message = self.repo.git("tag", "-l", "--format=%(contents)", name)
        self.assertIn("NOT verified locally (not run): no Mac here", message)

    def test_refuses_off_main_and_dirty_trees(self):
        self._release_commit()
        self._record("PASS")
        write(self.root, "products/macos-desktop/VERSION", "0.1.1\n")
        with self.assertRaises(rel.ReleaseError) as ctx:
            rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False)
        self.assertIn("uncommitted", str(ctx.exception))
        self.repo.git("checkout", "--", ".")
        self.repo.commit("chore: unmerged")
        with self.assertRaises(rel.ReleaseError) as ctx:
            rel.tag(rel.BY_SLUG["macos-desktop"], self.root, fetch=False)
        self.assertIn("not on origin/main", str(ctx.exception))

    def test_verify_tag_rejects_a_mismatch(self):
        with self.assertRaises(rel.ReleaseError) as ctx:
            rel.verify_tag("macos-desktop-v0.2.0", self.root)
        self.assertIn("tree says 0.1.0", str(ctx.exception))


class RealTreeTest(unittest.TestCase):
    def test_repository_release_metadata_is_consistent(self):
        self.assertEqual(rel.check_problems(), [])


if __name__ == "__main__":
    unittest.main()
