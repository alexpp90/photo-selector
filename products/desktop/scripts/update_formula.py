#!/usr/bin/env python3
"""Update the version, download tag and SHA-256 markers of a Homebrew formula or cask.

Used by `desktop.yml` (legacy Desktop) and, for the version and hash only, by `macos.yml`
(Lensjet). A requested value that matches no line is an error: a renamed marker in the tap
must fail the job instead of leaving stale hashes behind a green run.
"""
import argparse
import re
import sys
from pathlib import Path

DOWNLOAD_TAG_RE = re.compile(r"(/releases/download/)[^/\"]*(#\{version\}/)")


def update(content, version=None, macos_sha256=None, linux_sha256=None, tag_prefix=None):
    """Return (new_content, unmatched) where unmatched names every requested value that
    found no line to change."""
    found = set()
    new_lines = []

    for line in content.splitlines():
        updated_line = line
        indent = line[:len(line) - len(line.lstrip())]

        if version and line.strip().startswith('version '):
            updated_line = f'{indent}version "{version}"'
            found.add("--version-val")
        elif macos_sha256 and '# macos_sha256' in line:
            updated_line = f'{indent}sha256 "{macos_sha256}" # macos_sha256'
            found.add("--macos-sha256")
        elif linux_sha256 and '# linux_sha256' in line:
            updated_line = f'{indent}sha256 "{linux_sha256}" # linux_sha256'
            found.add("--linux-sha256")
        elif tag_prefix and DOWNLOAD_TAG_RE.search(line):
            # url ".../download/v#{version}/x.zip" -> ".../download/<tag_prefix>#{version}/x.zip"
            updated_line = DOWNLOAD_TAG_RE.sub(lambda m: m.group(1) + tag_prefix + m.group(2), line)
            found.add("--tag-prefix")

        new_lines.append(updated_line)

    requested = {
        "--version-val": version,
        "--macos-sha256": macos_sha256,
        "--linux-sha256": linux_sha256,
        "--tag-prefix": tag_prefix,
    }
    unmatched = [flag for flag, value in requested.items() if value and flag not in found]
    new_content = "\n".join(new_lines) + ("\n" if content.endswith("\n") else "")
    return new_content, unmatched


def main(argv=None):
    parser = argparse.ArgumentParser(description="Update Homebrew formula/cask files")
    parser.add_argument("--file", required=True, help="Path to the Ruby file to update")
    parser.add_argument("--version-val", help="New version value to set (e.g. 0.1.0)")
    parser.add_argument("--macos-sha256", help="New macOS SHA256 hash")
    parser.add_argument("--linux-sha256", help="New Linux SHA256 hash")
    parser.add_argument(
        "--tag-prefix",
        help="Release-tag prefix to write into url lines of the form .../download/<tag>#{version}/... "
             "(e.g. desktop-v). Without it the url lines are left alone.",
    )
    args = parser.parse_args(argv)

    file_path = Path(args.file)
    if not file_path.exists():
        print(f"Error: File {file_path} does not exist.", file=sys.stderr)
        return 1

    content = file_path.read_text()
    new_content, unmatched = update(
        content, args.version_val, args.macos_sha256, args.linux_sha256, args.tag_prefix
    )
    if unmatched:
        print(
            f"Error: {file_path}: no line to update for {', '.join(unmatched)}; file left unchanged.",
            file=sys.stderr,
        )
        return 1

    file_path.write_text(new_content)
    print(f"Successfully updated {file_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
