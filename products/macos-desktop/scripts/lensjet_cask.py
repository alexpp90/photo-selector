#!/usr/bin/env python3
"""Bring a Lensjet cask in alexpp90/homebrew-tap up to date with what Lensjet ships.

`macos.yml:update-homebrew-taps` runs the legacy `update_formula.py` for the version and hash,
then this script for everything Lensjet changed since the cask was written:

- the zip holds `Lensjet.app` (was `PhotoSelector.app`);
- the stable cask downloads from the product's own tag `macos-desktop-v<version>` (was `v<version>`,
  the old shared tag scheme, so the URL of the first Lensjet release would have been a 404), and
  the nightly cask from `nightly-macos-desktop` (was the `nightly` tag three workflows shared);
- `name` and `desc` say Lensjet (the cask *token* stays: installed casks keep updating);
- `zap` also removes the preferences of the new bundle id `app.alpatix.lensjet`.

Idempotent: a cask that is already right is left byte for byte. The cask token, `version`,
`sha256`, `conflicts_with` and `depends_on` are never touched.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

OLD_PREFS = "~/Library/Preferences/com.photoselectortoolbox.macos.plist"
NEW_PREFS = "~/Library/Preferences/app.alpatix.lensjet.plist"


def rebrand(text: str, nightly: bool) -> str:
    """The cask text with Lensjet's app, download tag, name, description and zap paths."""
    text = re.sub(r'^([ \t]*app )"PhotoSelector\.app"', r'\1"Lensjet.app"', text, flags=re.M)
    if nightly:
        # Lensjet's nightlies have their own rolling tag (the shared `nightly` is legacy Desktop's).
        text = text.replace("/releases/download/nightly/", "/releases/download/nightly-macos-desktop/")
    else:
        text = text.replace("/releases/download/v#{version}/",
                            "/releases/download/macos-desktop-v#{version}/")
    name = "Lensjet (Nightly)" if nightly else "Lensjet"
    text = re.sub(r'^([ \t]*name )".*"$', rf'\1"{name}"', text, count=1, flags=re.M)
    desc = "Mac photo culling: compare, score and sort photos on-device"
    if nightly:
        desc += " (nightly build)"
    text = re.sub(r'^([ \t]*desc )".*"$', rf'\1"{desc}"', text, count=1, flags=re.M)
    zap = re.search(r'^([ \t]*)zap trash: (.*)$', text, flags=re.M)
    if zap and NEW_PREFS not in text:
        paths = [OLD_PREFS, NEW_PREFS]
        listed = ",\n".join(f'{zap.group(1)}  "{p}"' for p in paths)
        text = text[:zap.start()] + f"{zap.group(1)}zap trash: [\n{listed},\n{zap.group(1)}]" \
            + text[zap.end():]
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("cask", type=Path, help="path to the cask file, rewritten in place")
    parser.add_argument("--nightly", action="store_true", help="the @nightly cask")
    args = parser.parse_args(argv)
    old = args.cask.read_text(encoding="utf-8")
    new = rebrand(old, args.nightly)
    if new != old:
        args.cask.write_text(new, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
