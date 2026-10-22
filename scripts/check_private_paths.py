#!/usr/bin/env python3
"""check_private_paths.py — keep maintainer-private material out of the public repository.

The maintainer works with a private overlay checked out at `private/` and linked into this
tree (AI-agent framework, requirement specifications, design prompts, audits, agent memory).
Those paths are ignored by `.gitignore`; this gate makes sure nothing slips in anyway —
through `git add -f`, a rename, or a tool that writes a regular file where a link belongs.

Usage:
    python3 scripts/check_private_paths.py            # check every tracked file (CI)
    python3 scripts/check_private_paths.py --staged   # check the index only (pre-commit hook)
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

PRIVATE_PATTERNS = [
    (re.compile(r"^private(/|$)"), "the private overlay checkout"),
    (re.compile(r"^ai(/|$)"), "AI-agent framework (lives in the private overlay)"),
    (re.compile(r"^\.(claude|agents|gemini|cursor)(/|$)"), "AI-tool configuration (private overlay / generated)"),
    (re.compile(r"^(\.cursorrules|\.windsurfrules|ROUTING\.md|\.sync_mode|CLAUDE\.local\.md)$"),
     "generated AI-tool file"),
    (re.compile(r"^\.github/copilot-instructions\.md$"), "generated AI-tool file"),
    (re.compile(r"^scripts/verify_requirements_traceability\.py$"),
     "requirements traceability tool (lives in the private overlay: private/scripts/)"),
    (re.compile(r"^Claude outputs(/|$)"), "agent hand-over material (belongs in private/docs/handover/)"),
    (re.compile(r"(^|/)intent_ledger[^/]*\.jsonl$"), "prompt ledger"),
    (re.compile(r"^docs/.*/(REQUIREMENTS|DESIGN_PROMPT|UX_INVESTIGATION|SECURITY_AUDIT|LEGAL_COMPLIANCE)\.md$"),
     "requirement specification / prompt / audit (private overlay)"),
    (re.compile(r"^docs/(reviews|handover)(/|$)"), "maintainer reviews and handover material (private overlay)"),
    (re.compile(r"^docs/build/ANDROID_SIGNING_[A-Z_]+\.md$"), "release-infrastructure runbook (private overlay)"),
]


def list_paths(staged: bool) -> list[str]:
    cmd = ["git", "-C", str(REPO_ROOT)]
    cmd += ["diff", "--cached", "--name-only", "--diff-filter=ACMR"] if staged else ["ls-files"]
    out = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if out.returncode != 0:
        print(out.stderr.strip() or "git failed", file=sys.stderr)
        sys.exit(2)
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def violations(paths: list[str]) -> list[str]:
    found: list[str] = []
    for path in paths:
        for pattern, why in PRIVATE_PATTERNS:
            if pattern.search(path):
                found.append(f"{path}  ({why})")
                break
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="Fail if private-overlay paths are tracked publicly.")
    parser.add_argument("--staged", action="store_true", help="check the staged index instead of all tracked files")
    parser.add_argument("--check", action="store_true", help="accepted for symmetry with the other gates")
    args = parser.parse_args()

    found = violations(list_paths(args.staged))
    if found:
        scope = "staged for commit" if args.staged else "tracked"
        print(f"✘ Private-overlay material is {scope} in the PUBLIC repository:", file=sys.stderr)
        for line in found:
            print(f"  - {line}", file=sys.stderr)
        print("Unstage it (git rm --cached <path>) and keep it under private/ instead.", file=sys.stderr)
        return 1
    print("✔ No private-overlay material tracked in the public repository.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
