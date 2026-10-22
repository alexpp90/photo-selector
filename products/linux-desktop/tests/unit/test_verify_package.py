"""Unit tests for verify_package.py packaging and APT repository verification.

Adheres to:
- R-LINUX-PKG-03: In-repo APT repository architecture and multi-version pool verification
- R-LINUX-PKG-05: Automated Debian packaging and verification tooling
"""

from __future__ import annotations

import io
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

# Resolve products/linux-desktop and scripts/
PRODUCT_DIR = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = PRODUCT_DIR / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from verify_package import (  # noqa: E402
    VerificationFailure,
    deb_version_sort_key,
    extract_deb_version,
    verify_apt_structure,
    verify_deb_archive,
)


def _create_mock_deb(path: Path, version: str = "0.2.0-1") -> Path:
    """Create a minimal valid .deb ar archive container for testing."""
    deb_binary = b"2.0\n"

    # Create control.tar.gz
    ctrl_buf = io.BytesIO()
    with tarfile.open(fileobj=ctrl_buf, mode="w:gz") as tar:
        ctrl_content = (
            f"Package: photo-selector-linux\n"
            f"Version: {version}\n"
            f"Architecture: all\n"
            f"Maintainer: Alex Patz\n"
            f"Description: Photo Selector Linux\n"
        ).encode("utf-8")
        ti_ctrl = tarfile.TarInfo(name="./control")
        ti_ctrl.size = len(ctrl_content)
        tar.addfile(ti_ctrl, io.BytesIO(ctrl_content))

        md5_content = b"d41d8cd98f00b204e9800998ecf8427e  usr/bin/photo-selector-linux\n"
        ti_md5 = tarfile.TarInfo(name="./md5sums")
        ti_md5.size = len(md5_content)
        tar.addfile(ti_md5, io.BytesIO(md5_content))
    ctrl_tar = ctrl_buf.getvalue()

    # Create data.tar.gz
    data_buf = io.BytesIO()
    with tarfile.open(fileobj=data_buf, mode="w:gz") as tar:
        launcher = b"#!/bin/sh\nexec python3 -m photo_selector_linux \"$@\"\n"
        ti_bin = tarfile.TarInfo(name="./usr/bin/photo-selector-linux")
        ti_bin.size = len(launcher)
        ti_bin.mode = 0o755
        tar.addfile(ti_bin, io.BytesIO(launcher))

        desktop = b"[Desktop Entry]\nName=Blitzlens\nType=Application\n"
        ti_desk = tarfile.TarInfo(name="./usr/share/applications/app.alpatix.Blitzlens.desktop")
        ti_desk.size = len(desktop)
        tar.addfile(ti_desk, io.BytesIO(desktop))

        meta = b"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<component/>\n"
        ti_meta = tarfile.TarInfo(name="./usr/share/metainfo/app.alpatix.Blitzlens.metainfo.xml")
        ti_meta.size = len(meta)
        tar.addfile(ti_meta, io.BytesIO(meta))

        init_py = b"__version__ = '0.2.0'\n"
        ti_lib = tarfile.TarInfo(name="./usr/lib/python3/dist-packages/photo_selector_linux/__init__.py")
        ti_lib.size = len(init_py)
        tar.addfile(ti_lib, io.BytesIO(init_py))
    data_tar = data_buf.getvalue()

    # Construct ar archive
    def make_header(name: str, size: int) -> bytes:
        return (
            name.ljust(16)
            + "0".ljust(12)
            + "0".ljust(6)
            + "0".ljust(6)
            + "100644".ljust(8)
            + str(size).ljust(10)
            + "`\n"
        ).encode("ascii")

    ar_data = b"!<arch>\n"
    for name, data in [
        ("debian-binary/", deb_binary),
        ("control.tar.gz/", ctrl_tar),
        ("data.tar.gz/", data_tar),
    ]:
        ar_data += make_header(name, len(data))
        ar_data += data
        if len(data) % 2 != 0:
            ar_data += b"\n"

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(ar_data)
    return path


def _create_mock_apt_repo(base_dir: Path, versions: list[str]) -> Path:
    """Set up a mock APT repository directory structure with given package versions."""
    apt_dir = base_dir / "apt"
    trixie_dir = apt_dir / "dists" / "trixie"
    trixie_dir.mkdir(parents=True, exist_ok=True)

    # Release and InRelease
    (trixie_dir / "Release").write_text("Suite: trixie\nCodename: trixie\n", encoding="utf-8")
    (trixie_dir / "InRelease").write_text("Suite: trixie\nCodename: trixie\n", encoding="utf-8")

    # Packages and Packages.gz for binary-all and binary-amd64
    for arch in ("binary-all", "binary-amd64"):
        arch_dir = trixie_dir / "main" / arch
        arch_dir.mkdir(parents=True, exist_ok=True)
        pkg_content = "Package: photo-selector-linux\nVersion: 0.2.0\nArchitecture: all\n\n"
        (arch_dir / "Packages").write_text(pkg_content, encoding="utf-8")
        import gzip
        with gzip.open(arch_dir / "Packages.gz", "wb") as gz:
            gz.write(pkg_content.encode("utf-8"))

    # Pool with specified deb versions
    pool_dir = apt_dir / "pool" / "main" / "p" / "photo-selector-linux"
    pool_dir.mkdir(parents=True, exist_ok=True)
    for ver in versions:
        deb_file = pool_dir / f"photo-selector-linux_{ver}_all.deb"
        _create_mock_deb(deb_file, version=ver)

    # sources and setup.sh
    (apt_dir / "photo-selector.sources").write_text(
        "URIs: https://alexpp90.github.io/apt/\nSuites: trixie\nComponents: main\n",
        encoding="utf-8",
    )
    setup_file = apt_dir / "setup.sh"
    setup_file.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    setup_file.chmod(0o755)

    return apt_dir


class TestVerifyPackageVersionSorting(unittest.TestCase):
    """Verify version extraction and sort key mechanics (REQ-LINUX-PKG.05)."""

    def test_extract_deb_version(self):
        self.assertEqual(extract_deb_version("photo-selector-linux_0.1.0-1_all.deb"), "0.1.0-1")
        self.assertEqual(extract_deb_version("photo-selector-linux_0.2.0-1_all.deb"), "0.2.0-1")
        self.assertEqual(extract_deb_version("photo-selector-linux_0.2.0_all.deb"), "0.2.0")
        self.assertEqual(extract_deb_version("unrelated_1.0.0_all.deb"), "")

    def test_deb_version_sort_key(self):
        v1 = deb_version_sort_key("0.1.0-1")
        v2 = deb_version_sort_key("0.2.0-1")
        v2_rev2 = deb_version_sort_key("0.2.0-2")
        v3 = deb_version_sort_key("1.0.0-1")

        self.assertLess(v1, v2)
        self.assertLess(v2, v2_rev2)
        self.assertLess(v2_rev2, v3)

        versions = ["0.1.0-1", "1.0.0-1", "0.2.0-2", "0.2.0-1"]
        sorted_vers = sorted(versions, key=deb_version_sort_key)
        self.assertEqual(sorted_vers, ["0.1.0-1", "0.2.0-1", "0.2.0-2", "1.0.0-1"])


class TestVerifyAptStructureMultiVersion(unittest.TestCase):
    """Verify verify_apt_structure handles multi-version pool (R-LINUX-PKG-03, CI-1)."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.base_dir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_select_latest_version_by_default(self):
        """When multiple versions exist and no version is requested, select latest by version sorting."""
        apt_dir = _create_mock_apt_repo(self.base_dir, ["0.1.0-1", "0.2.0-1"])
        selected = verify_apt_structure(apt_dir)
        self.assertEqual(selected.name, "photo-selector-linux_0.2.0-1_all.deb")

    def test_select_specific_requested_version(self):
        """When expected_version is specified, select the matching package."""
        apt_dir = _create_mock_apt_repo(self.base_dir, ["0.1.0-1", "0.2.0-1"])
        selected_01 = verify_apt_structure(apt_dir, expected_version="0.1.0")
        self.assertEqual(selected_01.name, "photo-selector-linux_0.1.0-1_all.deb")

        selected_02 = verify_apt_structure(apt_dir, expected_version="0.2.0")
        self.assertEqual(selected_02.name, "photo-selector-linux_0.2.0-1_all.deb")

        selected_exact = verify_apt_structure(apt_dir, expected_version="0.1.0-1")
        self.assertEqual(selected_exact.name, "photo-selector-linux_0.1.0-1_all.deb")

    def test_select_latest_revision_for_matching_version(self):
        """When multiple revisions of expected_version exist, pick highest revision."""
        apt_dir = _create_mock_apt_repo(self.base_dir, ["0.2.0-1", "0.2.0-2", "0.1.0-1"])
        selected = verify_apt_structure(apt_dir, expected_version="0.2.0")
        self.assertEqual(selected.name, "photo-selector-linux_0.2.0-2_all.deb")

    def test_missing_expected_version_raises(self):
        """When expected_version does not exist in pool, raise VerificationFailure."""
        apt_dir = _create_mock_apt_repo(self.base_dir, ["0.1.0-1", "0.2.0-1"])
        with self.assertRaises(VerificationFailure) as ctx:
            verify_apt_structure(apt_dir, expected_version="0.3.0")
        self.assertIn("No package matching version '0.3.0'", str(ctx.exception))

    def test_empty_pool_raises(self):
        """When pool contains no .deb files, raise VerificationFailure."""
        apt_dir = _create_mock_apt_repo(self.base_dir, [])
        with self.assertRaises(VerificationFailure) as ctx:
            verify_apt_structure(apt_dir)
        self.assertIn("No .deb packages found in pool", str(ctx.exception))


class TestVerifyDebArchive(unittest.TestCase):
    """Verify verify_deb_archive container integrity validation (R-LINUX-PKG-05)."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.base_dir = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_verify_valid_deb_archive(self):
        deb_path = self.base_dir / "photo-selector-linux_0.2.0-1_all.deb"
        _create_mock_deb(deb_path, "0.2.0-1")
        # Should pass without exception
        verify_deb_archive(deb_path)

    def test_verify_nonexistent_deb_raises(self):
        missing_deb = self.base_dir / "nonexistent.deb"
        with self.assertRaises(VerificationFailure):
            verify_deb_archive(missing_deb)

    def test_verify_corrupt_deb_raises(self):
        corrupt_deb = self.base_dir / "corrupt.deb"
        corrupt_deb.write_bytes(b"not an ar container")
        with self.assertRaises(VerificationFailure):
            verify_deb_archive(corrupt_deb)


class TestVerifyPackageCli(unittest.TestCase):
    """Verify CLI interface of verify_package.py (R-LINUX-PKG-05)."""

    def test_cli_help(self):
        script = SCRIPTS_DIR / "verify_package.py"
        res = subprocess.run([sys.executable, str(script), "--help"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("--product-dir", res.stdout)
        self.assertIn("--apt-dir", res.stdout)
        self.assertIn("--deb", res.stdout)
        self.assertIn("--version", res.stdout)


if __name__ == "__main__":
    unittest.main()
