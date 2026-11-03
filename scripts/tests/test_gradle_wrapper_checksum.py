"""The Gradle wrapper verifies the distribution it downloads (AS-S-1).

Without ``distributionSha256Sum`` the wrapper runs whatever the download URL serves, in a job that
holds the signing secrets. The checksum is the SHA-256 of the ``-bin.zip`` named by
``distributionUrl``; Dependabot's wrapper bumps update both lines together.
"""

from __future__ import annotations

from pathlib import Path
import re
import unittest

PROPERTIES = (
    Path(__file__).resolve().parents[2] / "products" / "android" / "gradle" / "wrapper" / "gradle-wrapper.properties"
)


class GradleWrapperChecksumTest(unittest.TestCase):
    def test_the_distribution_is_pinned_by_checksum(self) -> None:
        text = PROPERTIES.read_text()
        match = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", text, re.MULTILINE)
        self.assertIsNotNone(match, "gradle-wrapper.properties must set distributionSha256Sum (64 hex digits)")

    def test_the_distribution_url_is_https_and_a_bin_zip(self) -> None:
        text = PROPERTIES.read_text()
        match = re.search(r"^distributionUrl=(.+)$", text, re.MULTILINE)
        self.assertIsNotNone(match)
        assert match is not None
        url = match.group(1).replace("\\", "")
        self.assertTrue(url.startswith("https://services.gradle.org/distributions/gradle-"), url)
        self.assertTrue(url.endswith("-bin.zip"), url)


if __name__ == "__main__":
    unittest.main()
