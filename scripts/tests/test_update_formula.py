"""products/desktop/scripts/update_formula.py: the Homebrew version, tag and hash updater that
desktop.yml (legacy Desktop) and macos.yml (Lensjet) run against alexpp90/homebrew-tap."""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "products" / "desktop" / "scripts" / "update_formula.py"
spec = importlib.util.spec_from_file_location("update_formula", SCRIPT)
uf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uf)

# The stable legacy formula as it was in alexpp90/homebrew-tap on 2026-11-03.
FORMULA = '''class PhotoSelectorToolbox < Formula
  version "0.5.0"

  on_macos do
    if Hardware::CPU.arm?
      url "https://github.com/alexpp90/photo-selector/releases/download/v#{version}/photo-selector-toolbox-macos-apple-silicon.zip"
      sha256 "1d983c6b26d3ecdf487141db59692533be0614ccfca25a9c88304adff56cae39" # macos_sha256
    end
  end

  on_linux do
    if Hardware::CPU.intel?
      url "https://github.com/alexpp90/photo-selector/releases/download/v#{version}/photo-selector-toolbox-linux-x64.zip"
      sha256 "a2c50665b1906f55cb31ea0f6de18b586c5dce0b1c1ef83239f23dcf3c92e8dd" # linux_sha256
    end
  end
end
'''

CASK = '''cask "photo-selector-toolbox-legacy" do
  version "0.5.0"
  sha256 "1d983c6b26d3ecdf487141db59692533be0614ccfca25a9c88304adff56cae39" # macos_sha256

  url "https://github.com/alexpp90/photo-selector/releases/download/v#{version}/photo-selector-toolbox-macos-apple-silicon.zip"
end
'''


def run(content, *args):
    """Run main() on a temp file; returns (rc, file text afterwards, stderr)."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "f.rb"
        path.write_text(content)
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            rc = uf.main(["--file", str(path), *args])
        return rc, path.read_text(), err.getvalue()


class UpdateFormulaTest(unittest.TestCase):
    def test_tag_prefix_rewrites_every_download_url_to_the_product_tag(self):
        rc, out, _ = run(FORMULA, "--version-val", "0.6.0", "--tag-prefix", "desktop-v",
                         "--macos-sha256", "aa", "--linux-sha256", "bb")
        self.assertEqual(rc, 0)
        self.assertEqual(out.count("/releases/download/desktop-v#{version}/"), 2)
        self.assertNotIn("/download/v#{version}/", out)
        self.assertIn('version "0.6.0"', out)
        self.assertIn('sha256 "aa" # macos_sha256', out)
        self.assertIn('sha256 "bb" # linux_sha256', out)

    def test_without_tag_prefix_the_url_is_left_alone(self):
        """Lensjet's macos.yml passes no --tag-prefix; lensjet_cask.py rewrites the URL itself."""
        rc, out, _ = run(CASK, "--version-val", "0.6.0", "--macos-sha256", "aa")
        self.assertEqual(rc, 0)
        self.assertIn("/releases/download/v#{version}/", out)

    def test_nightly_url_without_version_template_is_never_rewritten(self):
        nightly = CASK.replace("v#{version}", "nightly")
        rc, out, _ = run(nightly, "--macos-sha256", "aa", "--tag-prefix", "desktop-v")
        self.assertEqual(rc, 1)
        self.assertIn("/releases/download/nightly/", out)

    def test_missing_sha_marker_fails_and_leaves_the_file_alone(self):
        no_marker = CASK.replace(" # macos_sha256", "")
        rc, out, err = run(no_marker, "--version-val", "2.0", "--macos-sha256", "deadbeef")
        self.assertEqual(rc, 1)
        self.assertIn("--macos-sha256", err)
        self.assertEqual(out, no_marker)  # not even the version line was written

    def test_missing_version_line_fails(self):
        rc, _, err = run(CASK.replace('  version "0.5.0"\n', ""), "--version-val", "2.0")
        self.assertEqual(rc, 1)
        self.assertIn("--version-val", err)

    def test_linux_hash_on_a_cask_without_linux_marker_fails(self):
        rc, _, err = run(CASK, "--linux-sha256", "bb")
        self.assertEqual(rc, 1)
        self.assertIn("--linux-sha256", err)

    def test_unchanged_value_is_not_an_error(self):
        """A re-run of the same release finds its lines and rewrites the same text."""
        rc, out, _ = run(CASK, "--version-val", "0.5.0",
                         "--macos-sha256", "1d983c6b26d3ecdf487141db59692533be0614ccfca25a9c88304adff56cae39")
        self.assertEqual(rc, 0)
        self.assertEqual(out, CASK)

    def test_missing_file_fails(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(uf.main(["--file", "/nonexistent/c.rb", "--version-val", "1"]), 1)


if __name__ == "__main__":
    unittest.main()
