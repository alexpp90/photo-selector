"""The legal URLs compiled into the Android apps must point at pages this repository serves.

Google Play rejects a listing whose privacy-policy link does not resolve, and German law
(§ 5 DDG) requires a reachable Impressum. Both `LegalLinks.kt` files once pointed at hosts
that never existed (a separate `phototok-legal` repository, and the Pages site of the
repository's pre-rename name), so every URL returned 404 while all unit tests passed.

GitHub Pages serves `main:/docs`, so `https://alexpp90.github.io/photo-selector/<path>`
must be a tracked file `docs/<path>`.
"""

from __future__ import annotations

from pathlib import Path
import re
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[2]
PAGES_ROOT = "https://alexpp90.github.io/photo-selector/"
SOURCE_CODE = "https://github.com/alexpp90/photo-selector"
# The repository was renamed from this; GitHub redirects git URLs but not Pages URLs.
OLD_REPO_NAME = "homebrew-photo-selector-toolbox"

CONST = re.compile(r'const val (\w+) = "([^"]+)"')


def tracked_files() -> set[str]:
    out = subprocess.run(
        ["git", "-C", str(REPO), "ls-files"], check=True, capture_output=True, text=True
    )
    return set(out.stdout.splitlines())


def legal_links_files() -> list[Path]:
    return sorted(REPO.glob("products/android/*/src/**/LegalLinks.kt"))


class LegalLinksTest(unittest.TestCase):
    def test_both_android_apps_have_legal_links(self):
        products = {p.relative_to(REPO).parts[2] for p in legal_links_files()}
        self.assertEqual(products, {"android-desktop", "phototok"})

    def test_hosted_urls_map_to_tracked_docs(self):
        tracked = tracked_files()
        for path in legal_links_files():
            for name, url in CONST.findall(path.read_text(encoding="utf-8")):
                if name == "SOURCE_CODE":
                    continue
                with self.subTest(file=str(path.relative_to(REPO)), constant=name):
                    self.assertTrue(url.startswith(PAGES_ROOT), url)
                    self.assertIn("docs/" + url[len(PAGES_ROOT):], tracked)

    def test_source_code_link_names_this_repository(self):
        for path in legal_links_files():
            consts = dict(CONST.findall(path.read_text(encoding="utf-8")))
            with self.subTest(file=str(path.relative_to(REPO))):
                self.assertEqual(consts.get("SOURCE_CODE"), SOURCE_CODE)


class OldRepositoryNameTest(unittest.TestCase):
    def test_no_tracked_text_file_uses_the_pre_rename_repository_name(self):
        out = subprocess.run(
            ["git", "-C", str(REPO), "grep", "-l", "-I", OLD_REPO_NAME, "--", ".",
             ":(exclude)scripts/tests/test_legal_links.py", ":(exclude)*CHANGELOG.md"],
            check=False, capture_output=True, text=True,
        )
        self.assertEqual(out.stdout.split(), [])


if __name__ == "__main__":
    unittest.main()
