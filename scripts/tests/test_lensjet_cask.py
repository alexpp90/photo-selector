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
