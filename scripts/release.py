#!/usr/bin/env python3
"""release.py — per-product release status, version bumps, tags and the CI tag check.

Each product is released on its own: its own semantic version, its own changelog and its own
tag `<slug>-v<X.Y.Z>` (for example `phototok-v0.6.0`). A tag push runs only that product's
workflow, which builds and publishes only that product. docs/build/RELEASING.md describes the
whole procedure; this script is the part a machine can check.

Subcommands:
  status       What changed in each product since its last release, whether a release is due
               and which version comes next. `--brief` prints only the products that are due
               (the session-start notice); `--json` is for tools.
  check        Every product's version sources agree, it has a changelog, and its workflow
               triggers on its tag prefix. Run by repo-hygiene.yml and scripts/run_tests.sh.
  prepare P    Bump product P's version sources and close its changelog's [Unreleased]
               section (drafted from the merged PRs when empty). Commit the result in a
               release PR.
  tag P        On merged main: create the annotated release tag for P's in-tree version.
               Refuses unless scripts/run_tests.sh recorded a passing run of P's gates on
               exactly this tree (--accept-untested REASON overrides, and says so in the tag).
               `--push` pushes the tag, which starts the release workflow.
  verify-tag T CI: fail a release whose tag does not match the in-tree version.
  notes T      Print the changelog section of tag T (the GitHub release body).

What counts: the first-parent history of main, one entry per merged PR (titles follow
Conventional Commits), limited to the files that ship in the product — tests, benchmarks
and Markdown do not. `feat` is a feature, `fix`/`perf`/`revert` and unconventional titles are
fixes, `docs`/`test`/`chore`/`ci`/`build`/`refactor`/`style` are maintenance and never make a
release due on their own.

Python 3.9+, standard library only.
"""

from __future__ import annotations

import argparse
import datetime as dt
import email.utils
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[1]

Version = Tuple[int, int, int]

# A release is due when any of these holds for the user-visible changes since the last one.
DUE_FEATURES = 1          # at least this many features
DUE_FIXES = 3             # or at least this many fixes
DUE_AGE_DAYS = 21         # or any fix that has waited at least this long

FEATURE_TYPES = {"feat"}
FIX_TYPES = {"fix", "perf", "revert"}
MAINTENANCE_TYPES = {"docs", "test", "tests", "chore", "ci", "build", "refactor", "style"}
IGNORED_TYPES = {"release"}

ANDROID_SHARED = [
    "products/android/core/",
    "products/android/saf/",
    "products/android/build.gradle.kts",
    "products/android/settings.gradle.kts",
    "products/android/gradle.properties",
    "products/android/gradle/",
]


@dataclass(frozen=True)
class VersionSource:
    path: str
    pattern: str            # regex with a named group `v`
    bump: bool = True       # False: checked only; `prepare` writes it by other means

    def regex(self) -> "re.Pattern[str]":
        return re.compile(self.pattern, re.M)


@dataclass(frozen=True)
class Product:
    slug: str
    name: str
    paths: Tuple[str, ...]
    sources: Tuple[VersionSource, ...]
    changelog: str
    workflow: str
    run_tests: str          # scripts/run_tests.sh flags covering the product
    record_group: str       # verification_record.py gate group
    legacy_tags: bool       # released under the shared v* tags before per-product releases

    @property
    def tag_prefix(self) -> str:
        return f"{self.slug}-v"

    def tag(self, version: Version) -> str:
        return f"{self.tag_prefix}{fmt(version)}"


def _gradle_version_name(module: str) -> VersionSource:
    return VersionSource(f"products/android/{module}/build.gradle.kts",
                         r'System\.getenv\("VERSION_NAME"\)\s*\?:\s*"(?P<v>[^"]+)"')


PRODUCTS: Tuple[Product, ...] = (
    Product(
        slug="phototok", name="PhotoTok",
        paths=("products/android/phototok/", *ANDROID_SHARED),
        sources=(_gradle_version_name("phototok"),),
        changelog="products/android/phototok/CHANGELOG.md",
        workflow=".github/workflows/android.yml",
        run_tests="--android --android-device", record_group="android", legacy_tags=True),
    Product(
        slug="android-desktop", name="Android Desktop",
        paths=("products/android/android-desktop/", *ANDROID_SHARED),
        sources=(_gradle_version_name("android-desktop"),),
        changelog="products/android/android-desktop/CHANGELOG.md",
        workflow=".github/workflows/android.yml",
        run_tests="--android --android-device", record_group="android", legacy_tags=True),
    Product(
        slug="macos-desktop", name="macOS Desktop",
        paths=("products/macos-desktop/",),
        sources=(VersionSource("products/macos-desktop/VERSION", r"\A(?P<v>[^\s]+)\s*\Z"),),
        changelog="products/macos-desktop/CHANGELOG.md",
        workflow=".github/workflows/macos.yml",
        run_tests="--macos", record_group="macos", legacy_tags=False),
    Product(
        slug="linux-desktop", name="Linux Desktop",
        paths=("products/linux-desktop/",),
        sources=(
            VersionSource("products/linux-desktop/pyproject.toml", r'^version = "(?P<v>[^"]+)"'),
            VersionSource("products/linux-desktop/src/photo_selector_linux/__init__.py",
                          r'^__version__ = "(?P<v>[^"]+)"'),
            VersionSource("products/linux-desktop/debian/changelog",
                          r"\Aphoto-selector-linux \((?P<v>[^)-]+)-\d+\)", bump=False),
        ),
        changelog="products/linux-desktop/CHANGELOG.md",
        workflow=".github/workflows/linux.yml",
        run_tests="--linux", record_group="linux", legacy_tags=False),
    Product(
        slug="desktop", name="Desktop (legacy)",
        paths=("products/desktop/",),
        sources=(
            VersionSource("products/desktop/pyproject.toml", r'^version = "(?P<v>[^"]+)"'),
            VersionSource("products/desktop/src/photo_selector_toolbox/__init__.py",
                          r'^    __version__ = "(?P<v>[^"]+)"'),
        ),
        changelog="products/desktop/CHANGELOG.md",
        workflow=".github/workflows/desktop.yml",
        run_tests="--python", record_group="desktop", legacy_tags=True),
)

BY_SLUG: Dict[str, Product] = {p.slug: p for p in PRODUCTS}


class ReleaseError(Exception):
    """A precondition does not hold; the message says what to do."""


# ── versions ─────────────────────────────────────────────────────────────────

SEMVER_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def parse_version(text: str) -> Version:
    m = SEMVER_RE.match(text.strip())
    if not m:
        raise ReleaseError(f"not a MAJOR.MINOR.PATCH version: {text!r}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def fmt(version: Version) -> str:
    return "%d.%d.%d" % version


def bump(version: Version, level: str) -> Version:
    major, minor, patch = version
    if level == "major":
        # Before 1.0 a breaking change raises the minor version (SemVer §4).
        return (major + 1, 0, 0) if major > 0 else (0, minor + 1, 0)
    if level == "minor":
        return major, minor + 1, 0
    if level == "patch":
        return major, minor, patch + 1
    raise ReleaseError(f"unknown bump level {level!r} (patch, minor or major)")


# ── git ──────────────────────────────────────────────────────────────────────


def git(*args: str, root: Path = REPO_ROOT, check: bool = True) -> str:
    out = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        raise ReleaseError(f"git {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout if out.returncode == 0 else ""


def default_ref(root: Path) -> str:
    """origin/main when the checkout has it (what is merged), else HEAD."""
    return "origin/main" if git("rev-parse", "-q", "--verify", "origin/main",
                                root=root, check=False).strip() else "HEAD"


def tags(root: Path, pattern: str) -> List[str]:
    return [t for t in git("tag", "-l", pattern, root=root).split() if t]


def last_release(product: Product, root: Path) -> Tuple[Optional[str], Optional[Version]]:
    """The highest released version: the product's own tag, else (for products released
    before per-product tags) the highest shared v* tag."""
    found: List[Tuple[Version, str]] = []
    for tag in tags(root, f"{product.tag_prefix}*"):
        try:
            found.append((parse_version(tag[len(product.tag_prefix):]), tag))
        except ReleaseError:
            continue
    if not found and product.legacy_tags:
        for tag in tags(root, "v*"):
            try:
                found.append((parse_version(tag[1:]), tag))
            except ReleaseError:
                continue
    if not found:
        return None, None
    version, tag = max(found)
    return tag, version


# ── history ──────────────────────────────────────────────────────────────────

CONVENTIONAL_RE = re.compile(r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<desc>.+)$")
PR_MERGE_RE = re.compile(r"^Merge pull request #(?P<pr>\d+) from \S+")


@dataclass
class Entry:
    sha: str
    timestamp: int
    title: str
    pr: Optional[int]
    files: List[str]
    kind: str = "fix"           # feature | fix | maintenance | ignored
    breaking: bool = False

    @property
    def date(self) -> dt.date:
        return dt.datetime.fromtimestamp(self.timestamp, dt.timezone.utc).date()

    def label(self) -> str:
        ref = f"#{self.pr}" if self.pr else self.sha[:7]
        return f"{self.title} ({ref})"


def classify(title: str, body: str) -> Tuple[str, bool]:
    m = CONVENTIONAL_RE.match(title)
    breaking = "BREAKING CHANGE" in body or bool(m and m.group("bang"))
    if not m:
        return "fix", breaking            # unknown: assume it may be user-visible
    kind = m.group("type").lower()
    if kind in IGNORED_TYPES:
        return "ignored", False
    if kind in FEATURE_TYPES:
        return "feature", breaking
    if kind in MAINTENANCE_TYPES:
        return "maintenance", breaking
    return "fix", breaking


def history(root: Path, base: Optional[str], head: str) -> List[Entry]:
    """First-parent history of `head` since `base`, one entry per merged PR or direct commit,
    with the files it changed relative to its first parent."""
    rev = f"{base}..{head}" if base else head
    out = git("log", "--first-parent", "--diff-merges=first-parent", "--name-only",
              "--format=%x1e%H%x1f%ct%x1f%s%x1f%b%x1d", rev, root=root)
    entries = []
    for chunk in out.split("\x1e"):
        if not chunk.strip():
            continue
        header, _, files = chunk.partition("\x1d")
        sha, ts, subject, body = (header.split("\x1f") + ["", "", "", ""])[:4]
        title, pr = subject.strip(), None
        m = PR_MERGE_RE.match(title)
        if m:
            pr = int(m.group("pr"))
            lines = [line.strip() for line in body.splitlines() if line.strip()]
            title = lines[0] if lines else title
            body = "\n".join(lines[1:])
        entry = Entry(sha=sha.strip(), timestamp=int(ts or 0), title=title, pr=pr,
                      files=[f for f in files.splitlines() if f.strip()])
        entry.kind, entry.breaking = classify(title, body)
        entries.append(entry)
    return entries


def ships(product: Product, path: str) -> bool:
    """True when `path` is part of what the product ships."""
    if not any(path == p or path.startswith(p) for p in product.paths):
        return False
    parts = path.split("/")
    return not ({"tests", "benchmarks"} & set(parts)) and not path.endswith(".md")


# ── status ───────────────────────────────────────────────────────────────────


@dataclass
class Status:
    product: Product
    ref: str
    last_tag: Optional[str]
    last_version: Optional[Version]
    in_tree: Optional[Version]
    problems: List[str]
    entries: List[Entry] = field(default_factory=list)
    changelog_ready: bool = False   # the changelog has a section for the in-tree version

    def of_kind(self, kind: str) -> List[Entry]:
        return [e for e in self.entries if e.kind == kind]

    @property
    def features(self) -> List[Entry]:
        return self.of_kind("feature")

    @property
    def fixes(self) -> List[Entry]:
        return self.of_kind("fix")

    @property
    def maintenance(self) -> List[Entry]:
        return self.of_kind("maintenance")

    @property
    def level(self) -> Optional[str]:
        visible = self.features + self.fixes
        if any(e.breaking for e in visible):
            return "major"
        if self.features:
            return "minor"
        if self.fixes:
            return "patch"
        return None

    @property
    def oldest_fix_days(self) -> int:
        if not self.fixes:
            return 0
        today = dt.datetime.now(dt.timezone.utc).date()
        return (today - min(e.date for e in self.fixes)).days

    @property
    def due_reasons(self) -> List[str]:
        reasons = []
        if any(e.breaking for e in self.features + self.fixes):
            reasons.append("breaking change")
        if len(self.features) >= DUE_FEATURES:
            reasons.append(f"{len(self.features)} feature(s)")
        if len(self.fixes) >= DUE_FIXES:
            reasons.append(f"{len(self.fixes)} fixes")
        elif self.fixes and self.oldest_fix_days >= DUE_AGE_DAYS:
            reasons.append(f"a fix waiting {self.oldest_fix_days} days")
        return reasons

    @property
    def due(self) -> bool:
        return bool(self.due_reasons)

    @property
    def next_version(self) -> Optional[Version]:
        """The version a release would get: the level's bump of the last release, or the
        in-tree version when that is already higher (prepared, or never released)."""
        if self.last_version is None:
            return self.in_tree
        level = self.level or "patch"
        candidate = bump(self.last_version, level)
        if self.in_tree and self.in_tree > candidate:
            return self.in_tree
        return candidate

    @property
    def prepared(self) -> bool:
        """The in-tree version is ahead of the last release."""
        return bool(self.in_tree and (self.last_version is None or self.in_tree > self.last_version))

    @property
    def awaiting_tag(self) -> bool:
        """A release was prepared and merged but never tagged: version and changelog section
        are on the branch, the tag is not. The release resumes at the tag step."""
        return self.prepared and self.changelog_ready

    @property
    def unfinished_bump(self) -> bool:
        """The version was bumped past a real release without a changelog section — a
        release that stopped half-way (how v0.5.0 was never published)."""
        return self.prepared and self.last_version is not None and not self.changelog_ready

    def as_dict(self) -> Dict[str, object]:
        nv = self.next_version
        return {
            "product": self.product.slug, "name": self.product.name, "ref": self.ref,
            "last_tag": self.last_tag,
            "last_version": fmt(self.last_version) if self.last_version else None,
            "in_tree_version": fmt(self.in_tree) if self.in_tree else None,
            "next_version": fmt(nv) if nv else None, "level": self.level,
            "due": self.due, "due_reasons": self.due_reasons, "prepared": self.prepared,
            "awaiting_tag": self.awaiting_tag, "unfinished_bump": self.unfinished_bump,
            "problems": self.problems, "run_tests": f"./scripts/run_tests.sh {self.product.run_tests}",
            "entries": [{"kind": e.kind, "breaking": e.breaking, "title": e.title, "pr": e.pr,
                         "sha": e.sha, "date": e.date.isoformat()} for e in self.entries],
        }


def read_versions(product: Product, root: Path) -> Tuple[Optional[Version], List[str]]:
    """The product's in-tree version, and every disagreement or unreadable source."""
    found: Dict[str, str] = {}
    problems = []
    for source in product.sources:
        path = root / source.path
        if not path.is_file():
            problems.append(f"{source.path}: missing")
            continue
        m = source.regex().search(path.read_text(encoding="utf-8"))
        if not m:
            problems.append(f"{source.path}: no version matching /{source.pattern}/")
            continue
        found[source.path] = m.group("v")
    values = set(found.values())
    if len(values) > 1:
        problems.append("version sources disagree: " +
                        ", ".join(f"{p}={v}" for p, v in found.items()))
        return None, problems
    if not values:
        return None, problems
    try:
        return parse_version(values.pop()), problems
    except ReleaseError as exc:
        problems.append(str(exc))
        return None, problems


def status(product: Product, root: Path = REPO_ROOT, ref: Optional[str] = None) -> Status:
    ref = ref or default_ref(root)
    last_tag, last_version = last_release(product, root)
    in_tree, problems = read_versions(product, root)
    entries = [e for e in history(root, last_tag, ref) if e.kind != "ignored"]
    for entry in entries:
        entry.files = [f for f in entry.files if ships(product, f)]
    log = root / product.changelog
    ready = bool(in_tree and log.is_file()
                 and changelog_section(log.read_text(encoding="utf-8"), fmt(in_tree)))
    return Status(product, ref, last_tag, last_version, in_tree, problems,
                  [e for e in entries if e.files], ready)


def _summary_line(s: Status) -> str:
    last = fmt(s.last_version) if s.last_version else "never released"
    nxt = fmt(s.next_version) if s.next_version else "?"
    counts = f"{len(s.features)} feat, {len(s.fixes)} fix, {len(s.maintenance)} maint"
    flag = "DUE " if s.due else ("—   " if not s.entries else "    ")
    since = f" since {s.last_tag}" if s.last_tag else ""
    if s.awaiting_tag:
        since += "  [prepared, tag missing]"
    elif s.unfinished_bump:
        since += f"  [in-tree {fmt(s.in_tree)}: bumped, never released]"
    return f"  {flag}{s.product.name:<17} {last:>14} → {nxt:<8} {counts}{since}"


def cmd_status(args: argparse.Namespace) -> int:
    products = [BY_SLUG[args.product]] if args.product else list(PRODUCTS)
    results = [status(p, ref=args.ref) for p in products]
    if args.json:
        print(json.dumps([r.as_dict() for r in results], indent=2))
        return 0
    if args.brief:
        listed = [r for r in results if r.due or r.awaiting_tag]
        if not listed:
            return 0
        print(f"RELEASE RADAR — {len(listed)} product(s) have unreleased changes worth shipping "
              f"({results[0].ref}):")
        for r in listed:
            nxt = fmt(r.next_version) if r.next_version else "?"
            since = f"since {r.last_tag}" if r.last_tag else "(never released)"
            if r.awaiting_tag:
                print(f"  • {r.product.name}: {nxt} prepared on main, tag missing → "
                      f"release-product resumes at the tag step")
                continue
            line = f"  • {r.product.name}: {', '.join(r.due_reasons)} {since} → suggest {nxt}"
            if r.unfinished_bump:
                line += (f" (in-tree version already {fmt(r.in_tree)} — bumped, never released)")
            print(line)
        print("Mention this to the user at a natural point; /release <product> runs the "
              "release (docs/build/RELEASING.md).")
        return 0
    head = git("rev-parse", "--short", results[0].ref).strip() if results else ""
    print(f"Release status at {results[0].ref} ({head}) — DUE = a release is suggested\n")
    for r in results:
        print(_summary_line(r))
        for problem in r.problems:
            print(f"        ! {problem}")
        if args.verbose or args.product:
            for title, items in (("Features", r.features), ("Fixes", r.fixes),
                                 ("Maintenance", r.maintenance)):
                if items:
                    print(f"      {title}:")
                    for e in items:
                        mark = " [BREAKING]" if e.breaking else ""
                        print(f"        - {e.label()}{mark}")
    if args.product:
        r = results[0]
        print(f"\n  Tests: ./scripts/run_tests.sh {r.product.run_tests}")
        if r.due:
            print(f"  Next:  python3 scripts/release.py prepare {r.product.slug}")
    return 0


# ── check ────────────────────────────────────────────────────────────────────


def check_problems(root: Path = REPO_ROOT) -> List[str]:
    problems = []
    for product in PRODUCTS:
        _, version_problems = read_versions(product, root)
        problems += [f"{product.slug}: {p}" for p in version_problems]
        if not (root / product.changelog).is_file():
            problems.append(f"{product.slug}: changelog {product.changelog} is missing")
        workflow = root / product.workflow
        text = workflow.read_text(encoding="utf-8") if workflow.is_file() else ""
        if f'"{product.tag_prefix}*"' not in text:
            problems.append(f"{product.slug}: {product.workflow} does not trigger on "
                            f'tags "{product.tag_prefix}*"')
    return problems


def cmd_check(args: argparse.Namespace) -> int:
    problems = check_problems()
    for problem in problems:
        print(f"✘ {problem}")
    if not problems:
        print(f"✔ release metadata consistent for {len(PRODUCTS)} products")
    return 1 if problems else 0


# ── prepare ──────────────────────────────────────────────────────────────────

UNRELEASED_RE = re.compile(r"^## \[Unreleased\][^\n]*\n(?P<body>.*?)(?=^## \[|\Z)", re.M | re.S)


def changelog_header(product: Product) -> str:
    return (f"# Changelog — {product.name}\n\n"
            f"Notable changes to {product.name}, released under the tags "
            f"`{product.tag_prefix}X.Y.Z`.\n"
            "The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), "
            "and this product adheres to\n"
            "[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before "
            "per-product releases: the root `CHANGELOG.md`.\n\n## [Unreleased]\n")


def draft_notes(s: Status) -> str:
    """Changelog bullets drafted from the merged PR titles — a starting point to rewrite for
    users, not the finished text."""
    def bullets(items: Sequence[Entry]) -> str:
        lines = []
        for e in items:
            m = CONVENTIONAL_RE.match(e.title)
            desc = m.group("desc") if m else e.title
            desc = desc[:1].upper() + desc[1:]
            lines.append(f"- {desc}{' **(breaking)**' if e.breaking else ''} "
                         f"({'#%d' % e.pr if e.pr else e.sha[:7]})")
        return "\n".join(lines)
    parts = []
    if s.features:
        parts.append("### Added\n" + bullets(s.features))
    if s.fixes:
        parts.append("### Fixed\n" + bullets(s.fixes))
    return "\n\n".join(parts) or "### Changed\n- Maintenance release."


def close_unreleased(text: str, version: Version, date: dt.date, drafted: str) -> str:
    m = UNRELEASED_RE.search(text)
    if not m:
        raise ReleaseError("changelog has no '## [Unreleased]' section")
    body = m.group("body").strip() or drafted
    section = f"## [Unreleased]\n\n## [{fmt(version)}] - {date.isoformat()}\n\n{body}\n\n"
    return text[:m.start()] + section + text[m.end():].lstrip("\n")


def changelog_section(text: str, version: str) -> Optional[str]:
    m = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n(?P<body>.*?)(?=^## \[|\Z)", text,
                  re.M | re.S)
    return m.group("body").strip() if m else None


def debian_entry(changelog: str, version: Version, notes: str, when: dt.datetime) -> str:
    """A new top entry for debian/changelog, signed like the previous one."""
    m = re.search(r"^ -- (?P<who>.+?>)  ", changelog, re.M)
    who = m.group("who") if m else "Photo Selector maintainers <noreply@github.com>"
    items = []
    for line in notes.splitlines():
        line = line.strip()
        if line.startswith(("- ", "* ")):
            items.append("  * " + re.sub(r"[*`]", "", line[2:]).strip())
    items = items or ["  * New upstream release."]
    return (f"photo-selector-linux ({fmt(version)}-1) trixie; urgency=medium\n\n"
            + "\n".join(items)
            + f"\n\n -- {who}  {email.utils.format_datetime(when)}\n\n")


def prepare(product: Product, root: Path = REPO_ROOT, version: Optional[Version] = None,
            level: Optional[str] = None, today: Optional[dt.date] = None,
            ref: Optional[str] = None) -> Tuple[Version, List[str]]:
    """Write the release version into the tree. Returns the version and the files changed."""
    s = status(product, root, ref)
    if s.problems:
        raise ReleaseError("fix the version sources first:\n  " + "\n  ".join(s.problems))
    if version is None:
        if level:
            if s.last_version is None:
                raise ReleaseError("never released: pass --version for the first release")
            version = bump(s.last_version, level)
        else:
            version = s.next_version
    if version is None:
        raise ReleaseError("cannot determine the next version: pass --version")
    if s.last_version and version <= s.last_version:
        raise ReleaseError(f"{fmt(version)} is not newer than the released {fmt(s.last_version)}")
    if s.in_tree and version < s.in_tree:
        raise ReleaseError(f"{fmt(version)} is below the in-tree {fmt(s.in_tree)} (bumped but never "
                           f"released): pass --version {fmt(s.in_tree)} or higher")
    if tags(root, product.tag(version)):
        raise ReleaseError(f"tag {product.tag(version)} already exists")
    today = today or dt.datetime.now(dt.timezone.utc).date()
    changed = []

    for source in product.sources:
        if not source.bump:
            continue
        path = root / source.path
        text = path.read_text(encoding="utf-8")
        m = source.regex().search(text)
        assert m is not None  # read_versions found it
        new = text[:m.start("v")] + fmt(version) + text[m.end("v"):]
        if new != text:
            path.write_text(new, encoding="utf-8")
            changed.append(source.path)

    log_path = root / product.changelog
    log = log_path.read_text(encoding="utf-8") if log_path.is_file() else changelog_header(product)
    if changelog_section(log, fmt(version)) is None:
        log = close_unreleased(log, version, today, draft_notes(s))
        log_path.write_text(log, encoding="utf-8")
        changed.append(product.changelog)

    if product.slug == "linux-desktop":
        deb = root / "products/linux-desktop/debian/changelog"
        text = deb.read_text(encoding="utf-8")
        if not text.startswith(f"photo-selector-linux ({fmt(version)}-"):
            when = dt.datetime.combine(today, dt.time(12, 0), dt.timezone.utc)
            notes = changelog_section(log, fmt(version)) or ""
            deb.write_text(debian_entry(text, version, notes, when) + text, encoding="utf-8")
            changed.append("products/linux-desktop/debian/changelog")
    return version, changed


def cmd_prepare(args: argparse.Namespace) -> int:
    product = BY_SLUG[args.product]
    version, changed = prepare(product, version=parse_version(args.version) if args.version
                               else None, level=args.level, ref=args.ref)
    print(f"Prepared {product.name} {fmt(version)} (tag {product.tag(version)}). Changed:")
    for path in changed:
        print(f"  {path}")
    print(f"""
Next:
  1. Rewrite the new {product.changelog} section for users (it may be a draft of PR titles).
  2. ./scripts/run_tests.sh {product.run_tests}   — fix every failure before going on.
  3. Commit "release({product.slug}): {fmt(version)}", open the release PR, merge it.
  4. On the merged main: python3 scripts/release.py tag {product.slug} --push""")
    return 0


# ── tag ──────────────────────────────────────────────────────────────────────


def verification(product: Product, root: Path) -> Tuple[str, List[str]]:
    """The CI-mirror result for the product's gates on exactly HEAD's tree:
    passed | partial | failed | not run, and the names of gates that did not run."""
    sys.path.insert(0, str(REPO_ROOT / "scripts"))
    import verification_record as vr  # noqa: E402  (sibling script)
    tree = git("rev-parse", "HEAD^{tree}", root=root).strip()
    records = [r for r in vr.load_records(root) if (r.get("git") or {}).get("tree") == tree]
    gates = [g for g in vr.merge_gates(records) if g.get("group") == product.record_group]
    skipped = [g["name"] for g in gates if g["status"] == "SKIP"]
    result = vr.group_status(gates)
    return ("not run" if result == "not in scope" else result), skipped


def tag(product: Product, root: Path = REPO_ROOT, remote: str = "origin", fetch: bool = True,
        accept_untested: Optional[str] = None, push: bool = False) -> str:
    if fetch:
        git("fetch", "--quiet", "--tags", remote, "main", root=root)
    if git("status", "--porcelain", "--untracked-files=no", root=root).strip():
        raise ReleaseError("the working tree has uncommitted changes")
    main = f"{remote}/main"
    head = git("rev-parse", "HEAD", root=root).strip()
    if subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", head, main]).returncode:
        raise ReleaseError(f"HEAD is not on {main}: merge the release PR, then tag the merged "
                           f"commit (git switch --detach {main})")
    s = status(product, root, "HEAD")
    if s.problems:
        raise ReleaseError("version sources disagree:\n  " + "\n  ".join(s.problems))
    if s.in_tree is None:
        raise ReleaseError("no in-tree version")
    if s.last_version and s.in_tree <= s.last_version:
        raise ReleaseError(f"in-tree {fmt(s.in_tree)} is already released ({s.last_tag}): run "
                           f"`release.py prepare {product.slug}` in a release PR first")
    name = product.tag(s.in_tree)
    if tags(root, name):
        raise ReleaseError(f"tag {name} already exists")
    log_path = root / product.changelog
    notes = changelog_section(log_path.read_text(encoding="utf-8"), fmt(s.in_tree)) \
        if log_path.is_file() else None
    if not notes:
        raise ReleaseError(f"{product.changelog} has no section for {fmt(s.in_tree)}")

    result, skipped = verification(product, root)
    if result == "passed":
        verified = f"Verified: ./scripts/run_tests.sh {product.run_tests} passed on this tree."
    elif result == "partial" and accept_untested is None:
        print(f"! {product.name} gates passed partially; not run here: {', '.join(skipped)}",
              file=sys.stderr)
        verified = (f"Verified: ./scripts/run_tests.sh {product.run_tests} passed on this tree; "
                    f"not run locally: {', '.join(skipped)}.")
    elif accept_untested is not None:
        verified = f"NOT verified locally ({result}): {accept_untested}"
    else:
        raise ReleaseError(
            f"no passing run of the {product.name} gates on this tree ({result}). Run "
            f"./scripts/run_tests.sh {product.run_tests} here and fix every failure, or pass "
            f"--accept-untested REASON (e.g. macOS gates on a Linux machine; the tag workflow "
            f"still runs them before publishing).")

    message = f"{product.name} {fmt(s.in_tree)}\n\n{notes}\n\n{verified}\n"
    subprocess.run(["git", "-C", str(root), "tag", "-a", name, "-F", "-"], input=message,
                   text=True, check=True)
    if push:
        git("push", remote, f"refs/tags/{name}", root=root)
    return name


def cmd_tag(args: argparse.Namespace) -> int:
    product = BY_SLUG[args.product]
    name = tag(product, remote=args.remote, fetch=not args.no_fetch,
               accept_untested=args.accept_untested, push=args.push)
    if args.push:
        print(f"Pushed {name}: {product.workflow} now builds and publishes {product.name}.")
        print("Watch it: gh run list --workflow " + Path(product.workflow).name)
    else:
        print(f"Created {name} locally. Push it to release: git push {args.remote} {name}")
    return 0


# ── CI: verify-tag, notes ────────────────────────────────────────────────────


def product_for_tag(name: str) -> Tuple[Product, Version]:
    name = re.sub(r"^refs/tags/", "", name)
    for product in sorted(PRODUCTS, key=lambda p: -len(p.tag_prefix)):
        if name.startswith(product.tag_prefix):
            return product, parse_version(name[len(product.tag_prefix):])
    raise ReleaseError(f"{name!r} is not a product release tag "
                       f"({', '.join(p.tag_prefix + 'X.Y.Z' for p in PRODUCTS)})")


def verify_tag(name: str, root: Path = REPO_ROOT) -> Tuple[Product, Version]:
    product, version = product_for_tag(name)
    in_tree, problems = read_versions(product, root)
    if problems:
        raise ReleaseError("; ".join(problems))
    if in_tree != version:
        raise ReleaseError(f"tag {name} says {fmt(version)} but the tree says "
                           f"{fmt(in_tree) if in_tree else '?'}: tag the release commit")
    log = root / product.changelog
    if not log.is_file() or not changelog_section(log.read_text(encoding="utf-8"), fmt(version)):
        raise ReleaseError(f"{product.changelog} has no section for {fmt(version)}")
    return product, version


def cmd_verify_tag(args: argparse.Namespace) -> int:
    product, version = verify_tag(args.tag)
    print(f"✔ {args.tag}: {product.name} {fmt(version)}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as fh:
            fh.write(f"product={product.slug}\nversion={fmt(version)}\nname={product.name}\n")
    return 0


def cmd_notes(args: argparse.Namespace) -> int:
    product, version = product_for_tag(args.tag)
    notes = changelog_section((REPO_ROOT / product.changelog).read_text(encoding="utf-8"),
                              fmt(version))
    if not notes:
        raise ReleaseError(f"{product.changelog} has no section for {fmt(version)}")
    print(notes)
    return 0


# ── main ─────────────────────────────────────────────────────────────────────


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    slugs = sorted(BY_SLUG)

    p = sub.add_parser("status", help="what changed since each product's last release")
    p.add_argument("product", nargs="?", choices=slugs)
    p.add_argument("--ref", help="history to read (default: origin/main, else HEAD)")
    p.add_argument("--brief", action="store_true", help="only products due, for the notice")
    p.add_argument("--json", action="store_true")
    p.add_argument("-v", "--verbose", action="store_true", help="list every entry")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("check", help="version sources, changelogs and tag triggers agree")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("prepare", help="bump a product's version and close its changelog")
    p.add_argument("product", choices=slugs)
    group = p.add_mutually_exclusive_group()
    group.add_argument("--level", choices=["patch", "minor", "major"])
    group.add_argument("--version")
    p.add_argument("--ref", help="history to draft notes from (default: origin/main)")
    p.set_defaults(func=cmd_prepare)

    p = sub.add_parser("tag", help="tag the merged release commit")
    p.add_argument("product", choices=slugs)
    p.add_argument("--push", action="store_true", help="push the tag (starts the release)")
    p.add_argument("--remote", default="origin")
    p.add_argument("--no-fetch", action="store_true")
    p.add_argument("--accept-untested", metavar="REASON")
    p.set_defaults(func=cmd_tag)

    p = sub.add_parser("verify-tag", help="CI: the tag matches the in-tree version")
    p.add_argument("tag")
    p.set_defaults(func=cmd_verify_tag)

    p = sub.add_parser("notes", help="print a tag's changelog section")
    p.add_argument("tag")
    p.set_defaults(func=cmd_notes)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except ReleaseError as exc:
        print(f"✘ {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
