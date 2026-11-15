"""Unit tests for scripts/check_conditions_contract.py."""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import check_conditions_contract as ccc  # noqa: E402

REAL = json.loads(ccc.CONTRACT.read_text(encoding="utf-8"))


class ContractShapeTest(unittest.TestCase):
    def test_committed_contract_is_well_formed(self):
        self.assertEqual(ccc.problems(REAL), [])

    def test_committed_sizes_are_the_decided_ones(self):
        sizes = {k: v["photos"] for k, v in REAL["folder_sizes"].items()}
        self.assertEqual(sizes, {"typical": 2000, "pr": 10000, "stress": 50000})

    def test_sizes_must_rise(self):
        bad = copy.deepcopy(REAL)
        bad["folder_sizes"]["pr"]["photos"] = 60000
        self.assertTrue(any("must rise" in p for p in ccc.problems(bad)))

    def test_growth_ratio_must_separate_linear_from_quadratic(self):
        for ratio in (4, 16, 20):
            bad = copy.deepcopy(REAL)
            bad["growth_check"]["max_ratio"] = ratio
            self.assertTrue(any("max_ratio" in p for p in ccc.problems(bad)), ratio)

    def test_pr_size_must_split_by_factor(self):
        bad = copy.deepcopy(REAL)
        bad["folder_sizes"]["pr"]["photos"] = 10001
        self.assertTrue(any("split evenly" in p for p in ccc.problems(bad)))

    def test_ranges_must_rise(self):
        bad = copy.deepcopy(REAL)
        bad["files"][0]["megabytes"] = [8, 1.5]
        self.assertTrue(any("rising" in p for p in ccc.problems(bad)))

    def test_unknown_consumer_is_named(self):
        bad = copy.deepcopy(REAL)
        bad["consumers"] = ["phototok", "jackpix"]
        self.assertTrue(any("'jackpix'" in p for p in ccc.problems(bad)))


class ConsumerSwitchTest(unittest.TestCase):
    """A product listed as consumer fails until one of its tests reads the contract."""

    def _run(self, consumers, test_source):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            contract = copy.deepcopy(REAL)
            contract["consumers"] = consumers
            path = root / "conditions.json"
            path.write_text(json.dumps(contract), encoding="utf-8")
            tests = root / "products/linux-desktop/tests/unit"
            tests.mkdir(parents=True)
            (tests / "test_scale.py").write_text(test_source, encoding="utf-8")
            return ccc.main([], contract_path=path, root=root)

    def test_not_a_consumer_passes_without_readers(self):
        self.assertEqual(self._run([], "def test_x(): pass\n"), 0)

    def test_consumer_without_reader_fails(self):
        self.assertEqual(self._run(["linux-desktop"], "def test_x(): pass\n"), 1)

    def test_consumer_with_reader_passes(self):
        src = 'CONTRACT = ROOT / "testdata" / "conditions" / "conditions.json"\n'
        self.assertEqual(self._run(["linux-desktop"], src), 0)


if __name__ == "__main__":
    unittest.main()
