#!/usr/bin/env python3
"""Bring a Lensjet cask in alexpp90/homebrew-tap up to date with what Lensjet ships.

`macos.yml:update-homebrew-taps` runs this script alone (it no longer calls anything under
`products/desktop/`). With `--version` and `--sha256` it first writes the release's version and
the zip's hash into the cask, and fails loudly when the cask has no line for a requested value
(a renamed marker in the tap must not leave a stale hash behind a green run). Then, always, it
brings the cask up to date with what Lensjet changed since the cask was written:

- the zip holds `Lensjet.app` (was `PhotoSelector.app`);
- the stable cask downloads from the product's own tag `macos-desktop-v<version>` (was `v<version>`,
  the old shared tag scheme, so the URL of the first Lensjet release would have been a 404), and
  the nightly cask from `nightly-macos-desktop` (was the `nightly` tag three workflows shared);
- `name` and `desc` say Lensjet (the cask *token* stays: installed casks keep updating);
- `zap` also removes the preferences of the new bundle id `app.alpatix.lensjet`;
- a `caveats` block tells the user how to open the app the first time: Lensjet is ad-hoc signed
  and not notarized, so Gatekeeper refuses the first launch until the user allows it (finding
  SET-R-2/LJ-R-1; the block goes when Lensjet is notarized).

Idempotent: a cask that is already right is left byte for byte. The cask token,
`conflicts_with` and `depends_on` are never touched, and `version`/`sha256` only when asked for.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

OLD_PREFS = "~/Library/Preferences/com.photoselectortoolbox.macos.plist"
NEW_PREFS = "~/Library/Preferences/app.alpatix.lensjet.plist"

# Shown by `brew install --cask`. No `#{...}` and no square brackets: it sits inside a Ruby heredoc.
CAVEATS = """  caveats <<~EOS
    Lensjet is not notarized by Apple yet, so macOS blocks the first launch
    ("Apple could not verify ...").
    Open System Settings > Privacy & Security, scroll to Security, click "Open Anyway"
    next to Lensjet and confirm. This is needed once.
    Or, in Terminal: xattr -dr com.apple.quarantine /Applications/Lensjet.app
  EOS

"""


class MarkerMissing(Exception):
    """A value was requested but the cask has no line to put it on."""


def set_version_and_hash(text: str, version: str | None, sha256: str | None) -> str:
    """The cask text with `version "<version>"` and the `# macos_sha256` hash line rewritten.

    A value that is None is left alone (the nightly cask has `version :latest`). A requested
    value with no matching line raises `MarkerMissing`.
    """
    found: set[str] = set()
    lines = []
    for line in text.split("\n"):
        indent = line[:len(line) - len(line.lstrip())]
        if version and line.strip().startswith("version "):
            line = f'{indent}version "{version}"'
            found.add("version")
        elif sha256 and "# macos_sha256" in line:
            line = f'{indent}sha256 "{sha256}" # macos_sha256'
            found.add("sha256")
        lines.append(line)
    missing = [name for name, value in (("version", version), ("sha256", sha256))
               if value and name not in found]
    if missing:
        raise MarkerMissing("no line to update for " + ", ".join(missing))
    return "\n".join(lines)


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
    if "caveats" not in text:
        # Before `zap`, which is the last stanza of the casks in the tap; else before the final `end`.
        anchor = re.search(r'^[ \t]*zap ', text, flags=re.M) or re.search(r'^end\s*$', text, flags=re.M)
        if anchor:
            text = text[:anchor.start()] + CAVEATS + text[anchor.start():]
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("cask", type=Path, help="path to the cask file, rewritten in place")
    parser.add_argument("--nightly", action="store_true", help="the @nightly cask")
    parser.add_argument("--version", help="release version to write, e.g. 0.3.1 (stable cask only)")
    parser.add_argument("--sha256", help="SHA-256 of the release zip")
    args = parser.parse_args(argv)
    old = args.cask.read_text(encoding="utf-8")
    try:
        new = rebrand(set_version_and_hash(old, args.version, args.sha256), args.nightly)
    except MarkerMissing as exc:
        print(f"Error: {args.cask}: {exc}; file left unchanged.", file=sys.stderr)
        return 1
    if new != old:
        args.cask.write_text(new, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
