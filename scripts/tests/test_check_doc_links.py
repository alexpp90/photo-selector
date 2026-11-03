"""scripts/check_doc_links.py: broken relative links and `#anchor`s fail, the repository has none.

2026-11-03: the three platform badges at the top of the root README pointed at headings that no
longer exist, and so did two links in docs/products/phototok/README.md. Nothing checked.
"""

from __future__ import annotations

import importlib.util
import re
import subprocess
import sys
import tempfile
import unittest
import weakref
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check_doc_links.py"
spec = importlib.util.spec_from_file_location("check_doc_links", SCRIPT)
links = importlib.util.module_from_spec(spec)
# The module imports check_private_paths from its own directory.
sys.path.insert(0, str(SCRIPT.parent))
spec.loader.exec_module(links)


class Fixture:
    """A throw-away tree; `check` reads exactly the files given."""

    def __init__(self, files: dict[str, str]):
        self.tmp = tempfile.TemporaryDirectory()
        self._finalizer = weakref.finalize(self, self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        self.files = sorted(files)

    def problems(self) -> list[str]:
        return links.check(self.root, self.files)


class SlugTest(unittest.TestCase):
    def test_slugs_follow_github(self):
        self.assertEqual(links.slug("📱 Android Installation (Jackpix & Frameduel)"), "-android-installation-jackpix--frameduel")
        self.assertEqual(links.slug("🍎 Lensjet (macOS) Installation"), "-lensjet-macos-installation")
        self.assertEqual(links.slug("Why `run_tests.sh` & [links](x)"), "why-run_testssh--links")


class CheckTest(unittest.TestCase):
    def test_a_missing_file_a_missing_anchor_and_a_missing_image_are_reported(self):
        fx = Fixture({
            "README.md": "# Title\n\n[ok](docs/a.md) [no](docs/zzz.md) [bad](docs/a.md#nope) ![i](img/none.png)\n[self](#title) [gone](#missing)\n",
            "docs/a.md": "# A\n\n## Sub heading\n",
        })
        found = " | ".join(fx.problems())
        self.assertIn("docs/zzz.md does not exist", found)
        self.assertIn("no heading or id 'nope' in docs/a.md", found)
        self.assertIn("img/none.png does not exist", found)
        self.assertIn("no heading or id 'missing' in this file", found)
        self.assertEqual(len(fx.problems()), 4, fx.problems())

    def test_valid_links_pass(self):
        fx = Fixture({
            "README.md": "# T\n\n[a](docs/a.md#sub-heading) [dup](docs/a.md#sub-heading-1) [dir](docs/) [abs](/docs/a.md)\n"
                         "[web](https://example.invalid/x) [mail](mailto:a@b.c) [line](docs/a.md#L3)\n",
            "docs/a.md": "# A\n\n## Sub heading\n\n## Sub heading\n",
        })
        self.assertEqual(fx.problems(), [])

    def test_code_is_not_a_link(self):
        fx = Fixture({"README.md": "```\n[x](nowhere.md)\n```\nInline `[y](nowhere.md)` too.\n"})
        self.assertEqual(fx.problems(), [])

    def test_html_documents_and_anchors(self):
        fx = Fixture({
            "docs/index.html": '<a href="p/a.html#top">x</a> <a href="p/a.html#nope">y</a> <a href="missing.html">z</a>',
            "docs/p/a.html": '<h1 id="top">A</h1>',
        })
        found = " | ".join(fx.problems())
        self.assertIn("no heading or id 'nope' in docs/p/a.html", found)
        self.assertIn("docs/missing.html does not exist", found)
        self.assertEqual(len(fx.problems()), 2)

    def test_links_into_the_private_overlay_are_not_reported(self):
        # A public clone has no private/ checkout; AGENTS.md points at it on purpose.
        fx = Fixture({"AGENTS.md": "[overlay](private/AGENTS.md) [req](docs/products/x/REQUIREMENTS.md)\n"})
        self.assertEqual(fx.problems(), [])

    def test_a_link_leaving_the_repository_is_reported(self):
        fx = Fixture({"docs/a.md": "[x](../../outside.md)\n"})
        self.assertIn("leaves the repository", fx.problems()[0])


class RepositoryTest(unittest.TestCase):
    def test_every_link_and_anchor_in_the_repository_resolves(self):
        out = subprocess.run(["python3", str(SCRIPT), "--root", str(ROOT)], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)


class AptDocumentationTest(unittest.TestCase):
    def test_docs_name_only_files_the_apt_repository_serves(self):
        # 2026-11-03: INSTALL.md told users to fetch apt/KEY.gpg, which the published repository
        # never served (curl -f fails, gpg --dearmor writes an empty keyring, apt update then fails).
        # The repository holds what package_debian.sh writes into it, what linux.yml exports into it
        # and the files under packaging/apt/ (copied to its root).
        workflow = (ROOT / ".github" / "workflows" / "linux.yml").read_text(encoding="utf-8")
        served = set(re.findall(r"apt-repo/([A-Za-z0-9._-]+\.(?:gpg|asc))", workflow))
        served |= {p.name for p in (ROOT / "products" / "linux-desktop" / "packaging" / "apt").iterdir()}
        served.discard("gitignore.template")
        self.assertIn("photo-selector-archive-keyring.gpg", served)
        offenders = []
        out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "*.md", "*.html", "*.sh"], capture_output=True, text=True)
        for name in out.stdout.splitlines():
            path = ROOT / name
            if not path.is_file() or name.startswith("scripts/tests/"):
                continue
            for match in re.finditer(r"alexpp90\.github\.io/apt/([A-Za-z0-9._-]+\.[A-Za-z0-9]+)", path.read_text(encoding="utf-8", errors="replace")):
                if match.group(1) not in served:
                    offenders.append(f"{name}: {match.group(1)}")
        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
