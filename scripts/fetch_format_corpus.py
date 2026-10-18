#!/usr/bin/env python3
"""Fetch and verify the supported-format conformance corpus.

The contract (which file formats every product must support) and the corpus (one real camera
file per RAW format, plus small committed bitmaps) are described in
``testdata/formats/manifest.json``; see ``docs/shared/SUPPORTED_FORMATS.md``.

RAW samples are public-domain (CC0) files from raw.pixls.us. They are too large to commit, so
they are downloaded once into a cache directory and verified by SHA-256 on every run. Sources
are tried in order: the mirrors listed in the manifest (release assets of this repository),
then each sample's canonical raw.pixls.us URL:

    python3 scripts/fetch_format_corpus.py            # download what is missing, verify all
    python3 scripts/fetch_format_corpus.py --check    # verify only, no network (exit 1 if incomplete)
    python3 scripts/fetch_format_corpus.py --validate # check the manifest itself, no corpus needed
    python3 scripts/fetch_format_corpus.py --print-dir

The cache directory is ``$PST_FORMAT_CORPUS`` or ``~/.cache/photo-selector-toolbox/format-corpus``.
Product test suites read the same variable; with ``PST_REQUIRE_FORMAT_CORPUS=1`` (set in CI) a
missing corpus fails their tests instead of skipping them.

Standard library only, so it runs on every CI runner before any product toolchain is set up.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "testdata" / "formats" / "manifest.json"
DEFAULT_CACHE = Path("~/.cache/photo-selector-toolbox/format-corpus").expanduser()

TIERS = {"required", "recognized"}
KINDS = {"raw", "bitmap"}
EXPECT_KEYS = {"long_edge_px", "make", "model", "iso", "exposure_time_s", "f_number", "focal_length_mm", "lens"}
REQUIRED_EXPECT_KEYS = {"long_edge_px", "make", "model", "iso", "exposure_time_s", "f_number", "focal_length_mm"}
USER_AGENT = "photo-selector-toolbox-format-corpus/1 (+https://github.com/alexpp90/photo-selector)"


def load_manifest(path: Path = MANIFEST) -> dict:
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def cache_dir() -> Path:
    env = os.environ.get("PST_FORMAT_CORPUS", "").strip()
    return Path(env).expanduser() if env else DEFAULT_CACHE


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate(manifest: dict, repo_root: Path = REPO_ROOT) -> list[str]:
    """Return every structural problem in the manifest (empty list = valid)."""
    errors: list[str] = []
    formats = manifest.get("formats", [])
    samples = manifest.get("samples", [])
    format_ids: dict[str, dict] = {}
    seen_ext: dict[str, str] = {}

    for fmt in formats:
        fid = fmt.get("id", "")
        if not fid or fid in format_ids:
            errors.append(f"format id missing or duplicated: {fid!r}")
        format_ids[fid] = fmt
        if fmt.get("tier") not in TIERS:
            errors.append(f"format {fid}: tier must be one of {sorted(TIERS)}")
        if fmt.get("kind") not in KINDS:
            errors.append(f"format {fid}: kind must be one of {sorted(KINDS)}")
        exts = fmt.get("extensions") or []
        if not exts:
            errors.append(f"format {fid}: no extensions")
        for ext in exts:
            if ext != ext.lower() or ext.startswith(".") or not re.fullmatch(r"[a-z0-9]+", ext):
                errors.append(f"format {fid}: extension {ext!r} must be lowercase without a dot")
            if ext in seen_ext:
                errors.append(f"extension {ext!r} claimed by both {seen_ext[ext]} and {fid}")
            seen_ext[ext] = fid

    sample_ids: set[str] = set()
    covered: set[str] = set()
    for s in samples:
        sid = s.get("id", "")
        if not sid or sid in sample_ids:
            errors.append(f"sample id missing or duplicated: {sid!r}")
        sample_ids.add(sid)
        fid = s.get("format")
        if fid not in format_ids:
            errors.append(f"sample {sid}: unknown format {fid!r}")
            continue
        covered.add(fid)
        ext = Path(s.get("file", "")).suffix.lower().lstrip(".")
        if ext not in format_ids[fid]["extensions"]:
            errors.append(f"sample {sid}: file extension .{ext} is not an extension of format {fid}")
        if not re.fullmatch(r"[0-9a-f]{64}", s.get("sha256", "")):
            errors.append(f"sample {sid}: sha256 must be 64 lowercase hex characters")
        source = s.get("source")
        if source == "corpus":
            if not str(s.get("url", "")).startswith("https://"):
                errors.append(f"sample {sid}: corpus samples need an https url")
            if not isinstance(s.get("bytes"), int):
                errors.append(f"sample {sid}: corpus samples need an integer byte size")
            if "/" in s.get("file", ""):
                errors.append(f"sample {sid}: corpus file names are flat (no directories)")
        elif source == "repo":
            local = repo_root / "testdata" / "formats" / s.get("file", "")
            if not local.is_file():
                errors.append(f"sample {sid}: committed fixture {local.relative_to(repo_root)} is missing")
            elif sha256_of(local) != s.get("sha256"):
                errors.append(f"sample {sid}: committed fixture does not match its sha256")
        else:
            errors.append(f"sample {sid}: source must be 'corpus' or 'repo'")
        expect = s.get("expect") or {}
        missing = REQUIRED_EXPECT_KEYS - expect.keys()
        unknown = expect.keys() - EXPECT_KEYS
        if missing:
            errors.append(f"sample {sid}: expect is missing {sorted(missing)}")
        if unknown:
            errors.append(f"sample {sid}: expect has unknown keys {sorted(unknown)}")

    for fid, fmt in format_ids.items():
        if fmt.get("tier") == "required" and fid not in covered:
            errors.append(f"required format {fid} has no sample — add one or demote it to 'recognized'")
    return errors


def corpus_samples(manifest: dict) -> list[dict]:
    return [s for s in manifest["samples"] if s["source"] == "corpus"]


def verify(manifest: dict, directory: Path) -> tuple[list[dict], list[str]]:
    """Return (missing samples, mismatching sample messages)."""
    missing, bad = [], []
    for s in corpus_samples(manifest):
        path = directory / s["file"]
        if not path.is_file():
            missing.append(s)
        elif path.stat().st_size != s["bytes"] or sha256_of(path) != s["sha256"]:
            bad.append(f"{s['file']}: size or sha256 mismatch")
    return missing, bad


def candidate_urls(manifest: dict, sample: dict) -> list[str]:
    """Mirrors first (our own release assets), the canonical origin last."""
    from urllib.parse import quote

    mirrors = manifest.get("corpus", {}).get("mirrors", [])
    return [m.format(file=quote(sample["file"])) for m in mirrors] + [sample["url"]]


def download(url: str, sample: dict, directory: Path) -> None:
    target = directory / sample["file"]
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    fd, tmp_name = tempfile.mkstemp(prefix=".partial-", dir=directory)
    tmp = Path(tmp_name)
    try:
        with os.fdopen(fd, "wb") as out, urllib.request.urlopen(request, timeout=120) as resp:
            shutil.copyfileobj(resp, out, length=1 << 20)
        actual = sha256_of(tmp)
        if actual != sample["sha256"]:
            raise RuntimeError(f"sha256 {actual} != pinned {sample['sha256']}")
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)


def fetch(manifest: dict, sample: dict, directory: Path) -> bool:
    for url in candidate_urls(manifest, sample):
        try:
            download(url, sample, directory)
            return True
        except Exception as exc:  # noqa: BLE001 — try the next source, report all
            print(f"  {url}: {exc}", file=sys.stderr)
    return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="verify the cache only, never download")
    mode.add_argument("--validate", action="store_true", help="validate the manifest only")
    mode.add_argument("--print-dir", action="store_true", help="print the cache directory and exit")
    args = parser.parse_args(argv)

    manifest = load_manifest()
    directory = cache_dir()
    if args.print_dir:
        print(directory)
        return 0

    errors = validate(manifest)
    if errors:
        for err in errors:
            print(f"manifest: {err}", file=sys.stderr)
        return 1
    if args.validate:
        print(f"manifest OK: {len(manifest['formats'])} formats, {len(manifest['samples'])} samples")
        return 0

    directory.mkdir(parents=True, exist_ok=True)
    missing, bad = verify(manifest, directory)
    if bad:
        for msg in bad:
            print(f"corpus: {msg} — delete the file and re-run to fetch it again", file=sys.stderr)
        return 1
    if missing and args.check:
        names = ", ".join(s["file"] for s in missing)
        print(f"corpus incomplete in {directory}: missing {names}", file=sys.stderr)
        print("run: python3 scripts/fetch_format_corpus.py", file=sys.stderr)
        return 1
    for s in missing:
        print(f"fetching {s['file']} ({s['bytes'] / 1e6:.1f} MB)", flush=True)
        if not fetch(manifest, s, directory):
            print(f"corpus: every source failed for {s['file']}", file=sys.stderr)
            return 1
    print(f"corpus OK: {len(corpus_samples(manifest))} samples in {directory}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
