#!/usr/bin/env python3
"""Third-party licence notices of the two Android apps.

Each app bundles ``res/raw/third_party_notices.txt`` (shown in Settings, "Licences") listing the
open-source components its *release* classpath bundles, with the full text of their licences.
The source of truth is ``products/android/licenses/components.json`` plus the licence texts
beside it. Two subcommands:

``check``     fails when a runtime dependency of either app matches no component (a new library
              must come with its licence), when a component matches no dependency of any app,
              or when a committed notice file differs from what its classpath needs.
``generate``  rewrites the notice files.

The classpath is read from ``./gradlew :<module>:dependencies --configuration
releaseRuntimeClasspath`` (run in ``products/android``); ``--classpath MODULE=FILE`` reads a saved
report instead. The notice text carries no versions, so a Dependabot bump never makes it stale.
Standard library only.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Dict, List, Optional, Sequence

REPO_ROOT = Path(__file__).resolve().parent.parent
ANDROID_DIR = REPO_ROOT / "products" / "android"
LICENSES_DIR = ANDROID_DIR / "licenses"
COMPONENTS_FILE = LICENSES_DIR / "components.json"

# module -> (display name, notice file the app reads as R.raw.third_party_notices)
APPS = {"phototok": "Jackpix", "android-desktop": "Frameduel"}
NOTICE_FILES = {module: ANDROID_DIR / module / "res" / "raw" / "third_party_notices.txt" for module in APPS}

_TREE_LINE = re.compile(r"^[|\s]*[+\\]--- (?P<rest>.+)$")
_COORD = re.compile(r"^(?P<group>[\w.\-]+):(?P<artifact>[\w.\-]+)(?::(?P<declared>[^\s>]+))?(?: -> (?P<resolved>[^\s]+))?")


def parse_classpath(report: str) -> Dict[str, str]:
    """``{'group:artifact': resolved version}`` of a ``gradle dependencies`` report.

    Project dependencies (``project ':core'``) and constraints (``(c)``) are not libraries on the
    classpath and are skipped.
    """
    coords: Dict[str, str] = {}
    for line in report.splitlines():
        tree = _TREE_LINE.match(line)
        if not tree:
            continue
        rest = tree.group("rest").strip()
        if rest.startswith("project ") or rest.endswith("(c)"):
            continue
        coord = _COORD.match(rest)
        if coord:
            coords[f"{coord.group('group')}:{coord.group('artifact')}"] = coord.group("resolved") or coord.group("declared") or ""
    return coords


def load_components(path: Path = COMPONENTS_FILE) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def find_component(components: Sequence[dict], coord: str) -> Optional[dict]:
    for component in components:
        if any(fnmatch.fnmatchcase(coord, pattern) for pattern in component["match"]):
            return component
    return None


def unlicensed(components: Sequence[dict], coords: Dict[str, str]) -> List[str]:
    return sorted(c for c in coords if find_component(components, c) is None)


def licence_texts(data: dict) -> Dict[str, str]:
    return {name: (LICENSES_DIR / lic["file"]).read_text(encoding="utf-8").strip("\n")
            for name, lic in data["licenses"].items()}


def render(app_name: str, data: dict, coords: Dict[str, str], texts: Dict[str, str]) -> str:
    """The notice text of one app: its components, bundled native libraries, then each licence once."""
    used = [component for component in data["components"]
            if any(find_component(data["components"], c) is component for c in coords)]
    out = [f"THIRD-PARTY SOFTWARE NOTICES — {app_name}", "",
           f"{app_name} is free software that includes the open-source components below. "
           "Each is used under the licence named beside it; the full text of those licences follows the list.", ""]
    for component in used:
        out += [f"* {component['name']}",
                f"  Copyright {component['owner']}",
                f"  Licence: {data['licenses'][component['license']]['name']}",
                f"  {component['url']}", ""]
        bundled = [b for b in data.get("bundled", [])
                   if b["in"] in coords and find_component(data["components"], b["in"]) is component]
        if bundled:
            out.append("  Libraries built into it:")
            for item in bundled:
                out += [f"  - {item['name']}: {item['license']}", f"    {item['url']}"]
            out.append("")
    for name in sorted({c["license"] for c in used}):
        out += ["=" * 78, data["licenses"][name]["name"], "=" * 78, "", texts[name], ""]
    return "\n".join(out).rstrip("\n") + "\n"


def gradle_classpath(module: str) -> Dict[str, str]:
    cmd = ["./gradlew", "-q", f":{module}:dependencies", "--configuration", "releaseRuntimeClasspath"]
    done = subprocess.run(cmd, cwd=ANDROID_DIR, capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise SystemExit(f"gradle failed for :{module} (exit {done.returncode}):\n{(done.stdout + done.stderr)[-1500:]}")
    return parse_classpath(done.stdout)


def classpaths(overrides: Dict[str, str]) -> Dict[str, Dict[str, str]]:
    return {module: parse_classpath(Path(overrides[module]).read_text(encoding="utf-8")) if module in overrides
            else gradle_classpath(module) for module in APPS}


def check(paths: Dict[str, Dict[str, str]], data: dict, notice_files: Dict[str, Path] = NOTICE_FILES) -> List[str]:
    problems: List[str] = []
    texts = licence_texts(data)
    seen: set = set()
    for module, coords in paths.items():
        for coord in unlicensed(data["components"], coords):
            problems.append(f":{module} bundles {coord}, which has no entry in {COMPONENTS_FILE.relative_to(REPO_ROOT)}: "
                            "add its licence there, then run `python3 scripts/android_licenses.py generate`")
        seen.update(find_component(data["components"], c)["id"] for c in coords if find_component(data["components"], c))
        want = render(APPS[module], data, coords, texts)
        path = notice_files[module]
        have = path.read_text(encoding="utf-8") if path.is_file() else None
        if have != want:
            problems.append(f"{path.relative_to(REPO_ROOT)} is {'missing' if have is None else 'out of date'}: "
                            "run `python3 scripts/android_licenses.py generate`")
    for component in data["components"]:
        if component["id"] not in seen:
            problems.append(f"component '{component['id']}' matches no dependency of either app: remove it from "
                            f"{COMPONENTS_FILE.relative_to(REPO_ROOT)}")
    return problems


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("check", "generate"))
    parser.add_argument("--classpath", action="append", default=[], metavar="MODULE=FILE",
                        help="read this module's release classpath from a saved `gradle dependencies` report")
    args = parser.parse_args(argv)
    overrides = dict(item.split("=", 1) for item in args.classpath)
    data = load_components()
    paths = classpaths(overrides)
    if args.command == "generate":
        texts = licence_texts(data)
        for module, coords in paths.items():
            missing = unlicensed(data["components"], coords)
            if missing:
                print(f"✘ :{module} bundles libraries with no entry in components.json: {', '.join(missing)}", file=sys.stderr)
                return 1
            NOTICE_FILES[module].parent.mkdir(parents=True, exist_ok=True)
            NOTICE_FILES[module].write_text(render(APPS[module], data, coords, texts), encoding="utf-8")
            print(f"wrote {NOTICE_FILES[module].relative_to(REPO_ROOT)}")
        return 0
    problems = check(paths, data)
    for problem in problems:
        print(f"✘ {problem}", file=sys.stderr)
    if not problems:
        print(f"✔ third-party notices cover every release dependency of {', '.join(f':{m}' for m in APPS)} "
              f"({sum(len(c) for c in paths.values())} coordinates)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
