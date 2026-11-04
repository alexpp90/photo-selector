"""An instrumented test never skips with ``Assume`` unless it is a lab-device test (AS-T-3).

AGP's connected runner counts JUnit's ``Assume`` status (-4) as a failure, while ``am instrument``
and the device matrix read it as a skip. A test that skips on a docked tablet or an old API level
therefore stays green on the lab phones and goes red in Gradle. Conditions on the device are
expressed with ``@SdkSuppress`` or by not asserting the unreachable part; state only a prepared lab
device has (a persisted folder grant) goes with ``@LabDeviceOnly``, which Gradle's runs exclude.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

ANDROID = Path(__file__).resolve().parents[2] / "products" / "android"
ASSUME_CALL = re.compile(r"\bassume(True|False|NotNull|That)\s*\(")


def unguarded_assumes(path: Path) -> list[str]:
    """``file:line`` of every Assume call that is not inside a ``@LabDeviceOnly`` test or class."""
    lines = path.read_text().splitlines()
    class_level = any("@LabDeviceOnly" in line and i < _first_fun(lines) for i, line in enumerate(lines))
    offenders: list[str] = []
    for index, line in enumerate(lines):
        if not ASSUME_CALL.search(line) or line.lstrip().startswith(("import ", "//", "*")):
            continue
        if class_level:
            continue
        fun = index
        while fun >= 0 and " fun " not in f" {lines[fun]}":
            fun -= 1
        annotations = lines[max(fun - 4, 0):fun + 1]
        if not any("@LabDeviceOnly" in a for a in annotations):
            offenders.append(f"{path.name}:{index + 1}")
    return offenders


def _first_fun(lines: list[str]) -> int:
    return next((i for i, line in enumerate(lines) if " fun " in f" {line}"), len(lines))


class NoAssumeInInstrumentedTests(unittest.TestCase):
    def test_instrumented_tests_do_not_skip_with_assume(self) -> None:
        offenders: list[str] = []
        for source in sorted(ANDROID.glob("*/tests/instrumented/**/*.kt")):
            offenders += unguarded_assumes(source)
        self.assertEqual([], offenders, "use @SdkSuppress, @LabDeviceOnly or return; Assume reads as a failure in Gradle")

    def test_the_guard_flags_an_assume_in_an_ordinary_test(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            ordinary = Path(directory) / "Ordinary.kt"
            ordinary.write_text("class T {\n    @Test\n    fun a() {\n        assumeTrue(\"x\", false)\n    }\n}\n")
            lab = Path(directory) / "Lab.kt"
            lab.write_text("class T {\n    @Test\n    @LabDeviceOnly\n    fun a() {\n        assumeTrue(\"x\", false)\n    }\n}\n")
            self.assertEqual(["Ordinary.kt:4"], unguarded_assumes(ordinary))
            self.assertEqual([], unguarded_assumes(lab))


if __name__ == "__main__":
    unittest.main()
