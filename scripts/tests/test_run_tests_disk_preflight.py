import unittest
import subprocess
import os

class TestRunTestsDiskPreflight(unittest.TestCase):
    def test_run_tests_fails_on_low_disk_space(self):
        env = os.environ.copy()
        env["PST_MIN_SPACE_GB"] = "100000"
        env["PST_NO_HOST_REEXEC"] = "1"
        result = subprocess.run(
            ["./scripts/run_tests.sh", "--android", "--quick"],
            env=env,
            capture_output=True,
            text=True
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Error: Disk space too low", result.stdout)
        self.assertIn("Threshold: 100000 GB", result.stdout)

if __name__ == '__main__':
    unittest.main()
