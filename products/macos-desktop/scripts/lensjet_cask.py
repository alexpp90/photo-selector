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
- the cask token is `lensjet` (`lensjet@nightly`), no longer the legacy product's `photo-selector-toolbox`
  ([USER 2026-11-12] decision #4): a cask file still named after the old token is renamed inside
  (`cask "..."` and `conflicts_with`), and `--legacy-notice` turns the old token's cask into a stub
  whose caveat says Lensjet moved and how to reinstall once. The tap is only ever changed by this
  script, through `macos.yml`, never by hand;
- `name` and `desc` say Lensjet;
- `zap` also removes the preferences of the new bundle id `app.alpatix.lensjet`;
- a `caveats` block tells the user how to open the app the first time: Lensjet is ad-hoc signed
  and not notarized, so Gatekeeper refuses the first launch until the user allows it (finding
  SET-R-2/LJ-R-1; the block goes when Lensjet is notarized).

Idempotent: a cask that is already right is left byte for byte. `depends_on` is never touched, and
`version`/`sha256` only when asked for.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

OLD_PREFS = "~/Library/Preferences/com.photoselectortoolbox.macos.plist"
NEW_PREFS = "~/Library/Preferences/app.alpatix.lensjet.plist"

OLD_TOKENS = {"photo-selector-toolbox": "lensjet", "photo-selector-toolbox@nightly": "lensjet@nightly"}

# Shown by `brew install --cask`. No `#{...}` and no square brackets: it sits inside a Ruby heredoc.
GATEKEEPER = """    Lensjet is not notarized by Apple yet, so macOS blocks the first launch
    ("Apple could not verify ...").
    Open System Settings > Privacy & Security, scroll to Security, click "Open Anyway"
    next to Lensjet and confirm. This is needed once.
    Or, in Terminal: xattr -dr com.apple.quarantine /Applications/Lensjet.app
"""

# Stable cask only: users of the old token reinstall once.
REINSTALL = """
    Installed Lensjet as photo-selector-toolbox? That cask no longer updates. Once:
    brew uninstall --cask photo-selector-toolbox && brew install --cask lensjet
"""

BLOCK = re.compile(r"^[ \t]*caveats <<~EOS\n.*?^[ \t]*EOS\n\n?", flags=re.M | re.S)


def caveats(nightly: bool) -> str:
    body = GATEKEEPER + ("" if nightly else REINSTALL)
    return f"  caveats <<~EOS\n{body}  EOS\n\n"


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


def retoken(text: str) -> str:
    """The cask text with the old legacy-product tokens renamed to Lensjet's (whole quoted strings only,
    so the zip's file name is untouched)."""
    for old, new in OLD_TOKENS.items():
        text = text.replace(f'"{old}"', f'"{new}"')
    return text


def legacy_notice(text: str, nightly: bool) -> str:
    """The old token's cask with one caveat: Lensjet moved, reinstall once. Nothing else changes."""
    old = "photo-selector-toolbox@nightly" if nightly else "photo-selector-toolbox"
    new = "lensjet@nightly" if nightly else "lensjet"
    block = (f"  caveats <<~EOS\n    Lensjet has its own cask now and this one no longer updates. Once:\n"
             f"    brew uninstall --cask {old} && brew install --cask {new}\n  EOS\n\n")
    if BLOCK.search(text):
        return BLOCK.sub(lambda _m: block, text, count=1)
    anchor = re.search(r'^[ \t]*zap ', text, flags=re.M) or re.search(r'^end\s*$', text, flags=re.M)
    return text[:anchor.start()] + block + text[anchor.start():] if anchor else text


def rebrand(text: str, nightly: bool) -> str:
    """The cask text with Lensjet's token, app, download tag, name, description and zap paths."""
    text = retoken(text)
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
    found = BLOCK.search(text)
    if found:
        # Our own block follows the script (a hand-written one, or any other, is left alone).
        if "Lensjet is not notarized" in found.group(0):
            text = BLOCK.sub(lambda _m: caveats(nightly), text, count=1)
    elif "caveats" not in text:
        # Before `zap`, which is the last stanza of the casks in the tap; else before the final `end`.
        anchor = re.search(r'^[ \t]*zap ', text, flags=re.M) or re.search(r'^end\s*$', text, flags=re.M)
        if anchor:
            text = text[:anchor.start()] + caveats(nightly) + text[anchor.start():]
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("cask", type=Path, help="path to the cask file, rewritten in place")
    parser.add_argument("--nightly", action="store_true", help="the @nightly cask")
    parser.add_argument("--version", help="release version to write, e.g. 0.3.1 (stable cask only)")
    parser.add_argument("--sha256", help="SHA-256 of the release zip")
    parser.add_argument("--legacy-notice", action="store_true",
                        help="the old token's cask: only add the 'Lensjet moved' caveat")
    args = parser.parse_args(argv)
    old = args.cask.read_text(encoding="utf-8")
    try:
        if args.legacy_notice:
            new = legacy_notice(old, args.nightly)
        else:
            new = rebrand(set_version_and_hash(old, args.version, args.sha256), args.nightly)
    except MarkerMissing as exc:
        print(f"Error: {args.cask}: {exc}; file left unchanged.", file=sys.stderr)
        return 1
    if new != old:
        args.cask.write_text(new, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
