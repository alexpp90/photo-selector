"""Unit tests for scripts/visual_baseline_host.py."""

from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import visual_baseline_host as vbh  # noqa: E402

ROOT = SCRIPTS_DIR.parent
UBUNTU_2404 = 'NAME="Ubuntu"\nID=ubuntu\nVERSION_ID="24.04"\nID_LIKE=debian\n'
DEBIAN_13 = 'PRETTY_NAME="Debian GNU/Linux 13 (trixie)"\nVERSION_ID="13"\nID=debian\n'


class HostTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)

    def os_release(self, text: str) -> Path:
        path = self.dir / "os-release"
        path.write_text(text, encoding="utf-8")
        return path

    def test_parse_strips_both_quote_styles_and_ignores_comments(self):
        fields = vbh.parse_os_release("# c\nID='ubuntu'\nVERSION_ID=\"24.04\"\nBAD\n\nX=a=b\n")
        self.assertEqual(fields, {"ID": "ubuntu", "VERSION_ID": "24.04", "X": "a=b"})

    def test_host_id_of_the_ci_image_is_the_baseline(self):
        self.assertEqual(vbh.host_id(self.os_release(UBUNTU_2404)), vbh.BASELINE_HOST)

    def test_host_id_without_version_and_without_file(self):
        self.assertEqual(vbh.host_id(self.os_release("ID=arch\n")), "arch")
        self.assertEqual(vbh.host_id(self.dir / "missing"), "unknown")

    def test_baseline_image_runs_the_gate(self):
        self.assertIsNone(vbh.skip_reason(self.os_release(UBUNTU_2404), env={}))

    def test_other_linux_is_skipped_with_both_hosts_named(self):
        reason = vbh.skip_reason(self.os_release(DEBIAN_13), env={})
        self.assertIn("debian-13", reason)
        self.assertIn(vbh.BASELINE_HOST, reason)
        self.assertIn("PST_VISUAL_ANY_HOST=1", reason)

    def test_another_ubuntu_release_is_skipped(self):
        other = UBUNTU_2404.replace("24.04", "22.04")
        self.assertIsNotNone(vbh.skip_reason(self.os_release(other), env={}))

    def test_force_runs_the_gate_anywhere(self):
        path = self.os_release(DEBIAN_13)
        self.assertIsNone(vbh.skip_reason(path, env={"PST_VISUAL_ANY_HOST": "1"}))
        self.assertIsNotNone(vbh.skip_reason(path, env={"PST_VISUAL_ANY_HOST": "0"}))

    def test_cli_exit_codes(self):
        script = SCRIPTS_DIR / "visual_baseline_host.py"
        forced = subprocess.run([sys.executable, str(script)], capture_output=True, text=True,
                                env={"PST_VISUAL_ANY_HOST": "1"})
        self.assertEqual((forced.returncode, forced.stdout), (0, ""))
        unforced = subprocess.run([sys.executable, str(script)], capture_output=True, text=True,
                                  env={})
        if vbh.host_id() == vbh.BASELINE_HOST:
            self.assertEqual(unforced.returncode, 0)
        else:
            self.assertEqual(unforced.returncode, vbh.SKIP_EXIT)
            self.assertIn(vbh.host_id(), unforced.stdout)


class WiringTest(unittest.TestCase):
    """The baseline host must stay the image the visual job actually runs on."""

    def test_visual_job_runs_on_ubuntu_latest(self):
        workflow = (ROOT / ".github/workflows/desktop.yml").read_text(encoding="utf-8")
        job = workflow[workflow.index("\n  visual:"):]
        runs_on = re.search(r"runs-on:\s*(\S+)", job).group(1)
        # ubuntu-latest resolves to BASELINE_HOST today; a pinned image must equal it.
        self.assertIn(runs_on, ("ubuntu-latest", vbh.BASELINE_HOST))

    def test_run_tests_consults_the_helper_before_the_visual_gate(self):
        script = (ROOT / "scripts/run_tests.sh").read_text(encoding="utf-8")
        helper = script.index("visual_baseline_host.py")
        self.assertLess(helper, script.index('gate "pytest tests/visual/ -m visual"'))


if __name__ == "__main__":
    unittest.main()
