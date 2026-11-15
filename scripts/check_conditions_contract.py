#!/usr/bin/env python3
"""check_conditions_contract.py — the usage-conditions contract is well-formed and read.

`testdata/conditions/conditions.json` states the conditions users have (folder sizes, files,
storage, session length); every product's scale tests read their sizes from it
(docs/shared/USAGE_CONDITIONS.md). This gate checks that:

* the contract is well-formed: the three folder sizes rise (typical < pr < stress), the PR size
  splits evenly by the growth factor, the growth ratio sits between linear and quadratic, and
  every range is a rising pair;
* every product listed under `consumers` has at least one test file that reads the contract.
  A product adds itself to `consumers` in the PR that lands its first scale test, so the check
  switches on per product.

Usage:
    python3 scripts/check_conditions_contract.py           # check (CI, run_tests.sh)
    python3 scripts/check_conditions_contract.py --report  # also list which products read it
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CONTRACT = REPO_ROOT / "testdata" / "conditions" / "conditions.json"
CONTRACT_NAME = "conditions.json"

# Where each product's tests live. A test "reads the contract" when its source names the file.
PRODUCT_TESTS = {
    "phototok": ("products/android/phototok/tests", (".kt",)),
    "android-desktop": ("products/android/android-desktop/tests", (".kt",)),
    "macos-desktop": ("products/macos-desktop/tests", (".swift",)),
    "linux-desktop": ("products/linux-desktop/tests", (".py",)),
}
SIZES = ("typical", "pr", "stress")
RANGE_KEYS = ("megapixels", "megabytes", "read_mb_s", "random_small_mb_s", "minutes", "decision_seconds")


def _is_range(v: object) -> bool:
    return (isinstance(v, list) and len(v) == 2 and all(isinstance(x, (int, float)) for x in v)
            and v[0] <= v[1] and v[0] >= 0)


def problems(contract: dict) -> list[str]:
    """Every way the contract is malformed; empty when it is well-formed."""
    out: list[str] = []
    if contract.get("schema") != 1:
        out.append("schema must be 1")
    sizes = contract.get("folder_sizes", {})
    counts = []
    for name in SIZES:
        photos = sizes.get(name, {}).get("photos")
        if not isinstance(photos, int) or photos <= 0:
            out.append(f"folder_sizes.{name}.photos must be a positive integer")
        else:
            counts.append(photos)
        if not sizes.get(name, {}).get("source"):
            out.append(f"folder_sizes.{name} names no source")
    if len(counts) == 3 and not counts[0] < counts[1] < counts[2]:
        out.append(f"folder sizes must rise typical < pr < stress, got {counts}")
    growth = contract.get("growth_check", {})
    factor, ratio = growth.get("factor"), growth.get("max_ratio")
    if not isinstance(factor, int) or factor < 2:
        out.append("growth_check.factor must be an integer >= 2")
    elif len(counts) == 3 and counts[1] % factor:
        out.append(f"folder_sizes.pr ({counts[1]}) must split evenly by growth_check.factor ({factor})")
    if not isinstance(ratio, (int, float)) or isinstance(factor, int) and not factor < ratio < factor * factor:
        out.append("growth_check.max_ratio must lie strictly between linear (factor) and quadratic (factor²)")
    for section in ("files", "storage", "sessions"):
        entries = contract.get(section)
        if not isinstance(entries, list) or not entries:
            out.append(f"{section} must be a non-empty list")
            continue
        seen: set[str] = set()
        for e in entries:
            eid = e.get("id")
            if not eid or eid in seen:
                out.append(f"{section}: missing or duplicate id {eid!r}")
            seen.add(eid)
            for key in RANGE_KEYS:
                if key in e and e[key] is not None and not _is_range(e[key]):
                    out.append(f"{section}.{eid}.{key} must be a rising [low, high] pair")
    for c in contract.get("consumers", []):
        if c not in PRODUCT_TESTS:
            out.append(f"consumers: unknown product {c!r} (known: {', '.join(PRODUCT_TESTS)})")
    return out


def readers(product: str, root: Path = REPO_ROOT) -> list[Path]:
    """The test files of a product that name the contract file."""
    rel, exts = PRODUCT_TESTS[product]
    base = root / rel
    if not base.is_dir():
        return []
    return sorted(p for p in base.rglob("*") if p.suffix in exts and p.is_file()
                  and CONTRACT_NAME in p.read_text(encoding="utf-8", errors="replace")
                  and "testdata" in p.read_text(encoding="utf-8", errors="replace"))


def main(argv: list[str] | None = None, contract_path: Path = CONTRACT, root: Path = REPO_ROOT) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--report", action="store_true", help="list which products' tests read it")
    args = ap.parse_args(argv)
    try:
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"✘ {contract_path}: {exc}")
        return 1
    errs = problems(contract)
    consumers = contract.get("consumers", [])
    for product in consumers:
        if product in PRODUCT_TESTS and not readers(product, root):
            errs.append(f"{product} is a consumer but none of its tests under "
                        f"{PRODUCT_TESTS[product][0]} reads testdata/conditions/{CONTRACT_NAME}")
    if args.report:
        for product in PRODUCT_TESTS:
            files = readers(product, root)
            state = "consumer" if product in consumers else "not yet a consumer"
            print(f"{product}: {state}; {len(files)} test file(s) read the contract")
    for e in errs:
        print(f"✘ {e}")
    if not errs:
        print(f"conditions contract OK (pr {contract['folder_sizes']['pr']['photos']} photos, "
              f"consumers: {', '.join(consumers) or 'none yet'})")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
