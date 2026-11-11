"""Lensjet's cask rewrite (products/macos-desktop/scripts/lensjet_cask.py), run by
macos.yml:update-homebrew-taps after the version and hash update."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "products" / "macos-desktop" / "scripts" / "lensjet_cask.py"
spec = importlib.util.spec_from_file_location("lensjet_cask", SCRIPT)
cask = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cask)

# The stable cask as it was in alexpp90/homebrew-tap on 2026-10-21.
STABLE = '''cask "photo-selector-toolbox" do
  version "0.2.0"
  sha256 "7e596e12db088f93133fd438cb6093eec457cf44e454b4113ea05156b6075f4e" # macos_sha256

  url "https://github.com/alexpp90/photo-selector/releases/download/v#{version}/photo-selector-macos-apple-silicon.zip"
  name "PhotoSelector Studio"
  desc "High-performance native macOS photo culling and selection application (Swift 6 & Apple Vision)"
  homepage "https://github.com/alexpp90/photo-selector"

  conflicts_with cask: "photo-selector-toolbox@nightly"
  depends_on macos: :sonoma

  app "PhotoSelector.app"

  zap trash: "~/Library/Preferences/com.photoselectortoolbox.macos.plist"
end
'''

NIGHTLY_URL = "https://github.com/alexpp90/photo-selector/releases/download/nightly/photo-selector-macos-apple-silicon.zip"


class LensjetCaskTest(unittest.TestCase):
    def test_stable_cask_points_at_the_product_tag_and_names_lensjet(self):
        out = cask.rebrand(STABLE, nightly=False)
        self.assertIn('/releases/download/macos-desktop-v#{version}/photo-selector-macos-apple-silicon.zip"', out)
        self.assertNotIn("/download/v#{version}/", out)
        self.assertIn('  app "Lensjet.app"\n', out)
        self.assertIn('  name "Lensjet"\n', out)
        self.assertNotIn("PhotoSelector", out)
        self.assertIn('"~/Library/Preferences/app.alpatix.lensjet.plist"', out)
        self.assertIn('"~/Library/Preferences/com.photoselectortoolbox.macos.plist"', out)
        # Never touched: the token, version, hash and relations installed casks depend on.
        for kept in ('cask "photo-selector-toolbox" do', 'version "0.2.0"', "7e596e12db08",
                     'conflicts_with cask: "photo-selector-toolbox@nightly"', "depends_on macos: :sonoma"):
            self.assertIn(kept, out)

    def test_nightly_cask_downloads_from_lensjets_own_nightly_tag(self):
        nightly = (STABLE.replace('cask "photo-selector-toolbox" do', 'cask "photo-selector-toolbox@nightly" do')
                   .replace('version "0.2.0"', "version :latest")
                   .replace('https://github.com/alexpp90/photo-selector/releases/download/v#{version}/'
                            'photo-selector-macos-apple-silicon.zip', NIGHTLY_URL))
        out = cask.rebrand(nightly, nightly=True)
        self.assertIn(NIGHTLY_URL.replace("/download/nightly/", "/download/nightly-macos-desktop/"), out)
        self.assertNotIn("/download/nightly/", out)
        self.assertIn('  name "Lensjet (Nightly)"\n', out)
        self.assertIn('  app "Lensjet.app"\n', out)

    def test_both_casks_tell_the_user_how_to_open_the_unnotarized_app(self):
        for nightly in (False, True):
            out = cask.rebrand(STABLE, nightly=nightly)
            self.assertEqual(out.count("caveats <<~EOS"), 1)
            block = out.split("caveats <<~EOS", 1)[1].split("EOS", 1)[0]
            self.assertIn("Open Anyway", block)
            self.assertIn("xattr -dr com.apple.quarantine /Applications/Lensjet.app", block)
            self.assertNotIn("#{", block)  # a Ruby heredoc would interpolate it
            # Between the app and the zap stanzas; the heredoc is closed before `zap`.
            self.assertLess(out.index('app "Lensjet.app"'), out.index("caveats"))
            self.assertLess(out.index("\n  EOS\n"), out.index("zap trash"))

    def test_caveats_are_added_once_and_an_existing_block_is_left_alone(self):
        once = cask.rebrand(STABLE, nightly=False)
        self.assertEqual(cask.rebrand(once, nightly=False).count("caveats"), 1)
        own = STABLE.replace("  zap trash", '  caveats "hand written"\n\n  zap trash')
        out = cask.rebrand(own, nightly=False)
        self.assertEqual(out.count("caveats"), 1)
        self.assertIn('caveats "hand written"', out)

    def test_version_and_sha256_are_rewritten_without_the_legacy_updater(self):
        out = cask.set_version_and_hash(STABLE, version="0.3.1", sha256="ab" * 32)
        self.assertIn('  version "0.3.1"\n', out)
        self.assertIn(f'  sha256 "{"ab" * 32}" # macos_sha256\n', out)
        self.assertNotIn("7e596e12db08", out)
        self.assertNotIn('version "0.2.0"', out)
        # Everything else is byte for byte what it was.
        self.assertEqual(out.replace('0.3.1', '0.2.0').replace("ab" * 32, "7e596e12db088f93133fd438cb6093eec457cf44e454b4113ea05156b6075f4e"), STABLE)

    def test_nightly_has_a_hash_but_no_numeric_version(self):
        nightly = STABLE.replace('version "0.2.0"', "version :latest")
        out = cask.set_version_and_hash(nightly, version=None, sha256="cd" * 32)
        self.assertIn("version :latest", out)
        self.assertIn(f'sha256 "{"cd" * 32}" # macos_sha256', out)

    def test_a_marker_that_is_gone_is_an_error_not_a_stale_hash(self):
        no_marker = STABLE.replace(" # macos_sha256", "")
        with self.assertRaises(cask.MarkerMissing):
            cask.set_version_and_hash(no_marker, version="0.3.1", sha256="ab" * 32)
        no_version = STABLE.replace('version "0.2.0"', "")
        with self.assertRaises(cask.MarkerMissing):
            cask.set_version_and_hash(no_version, version="0.3.1", sha256="ab" * 32)

    def test_command_line_applies_version_hash_and_rebrand_in_one_call(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cask.rb"
            path.write_text(STABLE, encoding="utf-8")
            self.assertEqual(cask.main([str(path), "--version", "0.3.1", "--sha256", "ab" * 32]), 0)
            out = path.read_text(encoding="utf-8")
            self.assertIn('version "0.3.1"', out)
            self.assertIn('app "Lensjet.app"', out)
            self.assertIn("caveats <<~EOS", out)
            self.assertEqual(cask.main([str(path), "--version", "0.3.1", "--sha256", "ab" * 32]), 0)
            self.assertEqual(path.read_text(encoding="utf-8"), out)

    def test_macos_workflow_calls_nothing_under_the_legacy_product(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github" / "workflows" / "macos.yml").read_text(encoding="utf-8")
        self.assertNotIn("products/desktop/", workflow)
        self.assertIn("products/macos-desktop/scripts/lensjet_cask.py", workflow)

    def test_rewrite_is_idempotent(self):
        once = cask.rebrand(STABLE, nightly=False)
        self.assertEqual(cask.rebrand(once, nightly=False), once)

    def test_rewritten_cask_keeps_ruby_structure(self):
        out = cask.rebrand(STABLE, nightly=False)
        self.assertEqual(out.count(" do\n"), 1)
        self.assertTrue(out.rstrip().endswith("end"))
        self.assertEqual(out.count("["), out.count("]"))


if __name__ == "__main__":
    unittest.main()
