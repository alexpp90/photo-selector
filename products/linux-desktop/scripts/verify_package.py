#!/usr/bin/env python3
"""verify_package.py — Packaging and APT repository verification engine.

Verifies:
1. debian/ packaging definitions (control, changelog, rules, copyright, install).
2. .deb binary archive validity (ar container, debian-binary, control.tar, data.tar).
3. Metadata version synchronization (Python package, pyproject.toml, changelog, control).
4. In-repo APT repository tree structure (dists/trixie/..., pool/main/...).
5. Release manifest cryptographic checksum consistency against live index files.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import re
import sys
import tarfile
from pathlib import Path
from typing import Dict, Tuple


class VerificationFailure(Exception):
    """Raised when a verification assertion fails."""
    pass


def log_pass(msg: str) -> None:
    print(f"  ✔ {msg}")


def log_fail(msg: str) -> None:
    print(f"  ✘ {msg}", file=sys.stderr)


# ── Check 1: debian/ Packaging Definitions ──────────────────────────────

def verify_debian_definitions(product_dir: Path) -> None:
    debian_dir = product_dir / "debian"
    if not debian_dir.is_dir():
        raise VerificationFailure(f"debian/ directory not found: {debian_dir}")

    # 1. debian/control
    ctrl_path = debian_dir / "control"
    if not ctrl_path.is_file():
        raise VerificationFailure("debian/control missing")
    ctrl_text = ctrl_path.read_text(encoding="utf-8")

    stanzas = ctrl_text.strip().split("\n\n")
    if len(stanzas) < 1:
        raise VerificationFailure("debian/control contains no valid stanzas")

    # Source stanza
    src_lines = stanzas[0].splitlines()
    src_map: Dict[str, str] = {}
    for line in src_lines:
        if ":" in line and not line.startswith(" "):
            k, v = line.split(":", 1)
            src_map[k.strip()] = v.strip()
    if src_map.get("Source") != "photo-selector-linux":
        raise VerificationFailure(f"Expected Source: photo-selector-linux, got: {src_map.get('Source')}")

    # Binary stanza
    bin_stanza = stanzas[-1]
    bin_map: Dict[str, str] = {}
    curr_k = ""
    for line in bin_stanza.splitlines():
        if ":" in line and not line.startswith(" "):
            k, v = line.split(":", 1)
            curr_k = k.strip()
            bin_map[curr_k] = v.strip()
        elif curr_k and (line.startswith(" ") or line.startswith("\t")):
            bin_map[curr_k] += " " + line.strip()

    if bin_map.get("Package") != "photo-selector-linux":
        raise VerificationFailure(f"Expected Package: photo-selector-linux, got: {bin_map.get('Package')}")
    if bin_map.get("Architecture") != "all":
        raise VerificationFailure(f"Expected Architecture: all, got: {bin_map.get('Architecture')}")

    depends = bin_map.get("Depends", "")
    req_deps = (
        "python3",
        "python3-gi",
        "gir1.2-gtk-4.0",
        "gir1.2-adw-1",
        "gir1.2-gexiv2-0.10",
        "gir1.2-gdkpixbuf-2.0",
        "libraw-bin",  # dcraw_emu develops RAWs whose embedded preview is too small
        "python3-numpy",
        "python3-pil",
    )
    for req in req_deps:
        if req not in depends:
            raise VerificationFailure(f"Missing mandatory dependency in debian/control Depends: {req}")
    log_pass("debian/control syntax and dependencies verified")

    # 2. debian/changelog
    ch_path = debian_dir / "changelog"
    if not ch_path.is_file():
        raise VerificationFailure("debian/changelog missing")
    ch_lines = ch_path.read_text(encoding="utf-8").splitlines()
    if not ch_lines:
        raise VerificationFailure("debian/changelog is empty")
    header_m = re.match(r"^photo-selector-linux\s+\(([^)]+)\)\s+([a-zA-Z0-9_-]+);", ch_lines[0])
    if not header_m:
        raise VerificationFailure(f"Invalid changelog header format: {ch_lines[0]}")
    suite = header_m.group(2)
    if suite not in ("trixie", "unstable"):
        raise VerificationFailure(f"Target distribution in changelog must be 'trixie' or 'unstable', got: {suite}")
    log_pass("debian/changelog syntax and target suite verified")

    # 3. debian/rules
    rules_path = debian_dir / "rules"
    if not rules_path.is_file():
        raise VerificationFailure("debian/rules missing")
    if not os.access(rules_path, os.X_OK):
        raise VerificationFailure("debian/rules is not executable")
    rules_text = rules_path.read_text(encoding="utf-8")
    if not rules_text.startswith("#!/usr/bin/make -f"):
        raise VerificationFailure("debian/rules missing standard '#!/usr/bin/make -f' shebang")
    log_pass("debian/rules executable and shebang verified")

    # 4. debian/copyright
    c_path = debian_dir / "copyright"
    if not c_path.is_file():
        raise VerificationFailure("debian/copyright missing")
    c_text = c_path.read_text(encoding="utf-8")
    if "https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/" not in c_text:
        raise VerificationFailure("debian/copyright does not declare machine-readable DEP-5 Format 1.0")
    log_pass("debian/copyright format specification verified")

    # 5. debian/install
    inst_path = debian_dir / "install"
    if not inst_path.is_file():
        raise VerificationFailure("debian/install missing")
    inst_text = inst_path.read_text(encoding="utf-8")
    if "app.alpatix.Blitzlens.desktop" not in inst_text:
        raise VerificationFailure("debian/install missing desktop entry mapping")
    log_pass("debian/install asset mappings verified")


# ── Check 2: .deb Binary Container Integrity ────────────────────────────

def parse_deb_archive(deb_path: Path) -> Dict[str, bytes]:
    raw = deb_path.read_bytes()
    if not raw.startswith(b"!<arch>\n"):
        raise VerificationFailure(f"{deb_path.name}: Missing '!<arch>\\n' magic header")

    offset = 8
    members: Dict[str, bytes] = {}
    while offset < len(raw):
        hdr = raw[offset:offset + 60]
        if len(hdr) < 60:
            break
        name = hdr[:16].decode("ascii", errors="replace").strip().rstrip("/")
        size = int(hdr[48:58].decode("ascii", errors="replace").strip())
        offset += 60
        data = raw[offset:offset + size]
        members[name] = data
        offset += size
        if size % 2 != 0:
            offset += 1

    expected_members = ["debian-binary", "control.tar.gz", "data.tar.gz"]
    for em in expected_members:
        matching = [k for k in members.keys() if k == em or k.startswith(em.split(".")[0])]
        if not matching:
            raise VerificationFailure(f"{deb_path.name}: Missing expected member {em}")

    if members.get("debian-binary") != b"2.0\n":
        raise VerificationFailure(f"{deb_path.name}: Invalid debian-binary content: {members.get('debian-binary')}")

    return members


def verify_deb_archive(deb_path: Path) -> None:
    if not deb_path.is_file():
        raise VerificationFailure(f"Package file not found: {deb_path}")

    members = parse_deb_archive(deb_path)

    # Verify control.tar.gz
    ctrl_key = [k for k in members.keys() if k.startswith("control.tar")][0]
    with tarfile.open(fileobj=io.BytesIO(members[ctrl_key]), mode="r:*") as tar:
        names = tar.getnames()
        if "./control" not in names and "control" not in names:
            raise VerificationFailure(f"{deb_path.name}: control.tar does not contain control file")
        if "./md5sums" not in names and "md5sums" not in names:
            raise VerificationFailure(f"{deb_path.name}: control.tar does not contain md5sums file")

    # Verify data.tar.gz
    data_key = [k for k in members.keys() if k.startswith("data.tar")][0]
    with tarfile.open(fileobj=io.BytesIO(members[data_key]), mode="r:*") as tar:
        names = tar.getnames()
        has_bin = any("usr/bin/photo-selector-linux" in n for n in names)
        has_desktop = any("app.alpatix.Blitzlens.desktop" in n or "photo-selector-linux.desktop" in n for n in names)
        has_meta = any("app.alpatix.Blitzlens.metainfo.xml" in n or "photo-selector-linux.metainfo.xml" in n
                       for n in names)
        has_lib = any("usr/lib/python3/dist-packages/photo_selector_linux" in n for n in names)

        if not has_bin:
            raise VerificationFailure(f"{deb_path.name}: data.tar missing /usr/bin/photo-selector-linux launcher")
        if not has_desktop:
            raise VerificationFailure(f"{deb_path.name}: data.tar missing desktop entry")
        if not has_meta:
            raise VerificationFailure(f"{deb_path.name}: data.tar missing AppStream metainfo XML")
        if not has_lib:
            raise VerificationFailure(f"{deb_path.name}: data.tar missing python package sources")

        # Verify permissions of launcher
        for ti in tar.getmembers():
            if "usr/bin/photo-selector-linux" in ti.name:
                if (ti.mode & 0o111) == 0:
                    raise VerificationFailure(f"{deb_path.name}: launcher is not executable (mode {oct(ti.mode)})")

    log_pass(f".deb archive container validity verified ({deb_path.name})")


# ── Check 3: Metadata Version Synchronization ──────────────────────────

def verify_version_synchronization(product_dir: Path, deb_path: Path) -> None:
    # 1. Read Python __version__
    init_path = product_dir / "src" / "photo_selector_linux" / "__init__.py"
    init_text = init_path.read_text(encoding="utf-8")
    m = re.search(r'__version__\s*=\s*["\']([^"\']+)["\']', init_text)
    if not m:
        raise VerificationFailure(f"Could not parse __version__ from {init_path}")
    py_version = m.group(1)

    # 2. Read pyproject.toml version
    pyproject_path = product_dir / "pyproject.toml"
    pyproject_text = pyproject_path.read_text(encoding="utf-8")
    m_toml = re.search(r'version\s*=\s*["\']([^"\']+)["\']', pyproject_text)
    if not m_toml:
        raise VerificationFailure("Could not parse version from pyproject.toml")
    toml_version = m_toml.group(1)
    if toml_version != py_version:
        raise VerificationFailure(f"pyproject.toml version ({toml_version}) != __init__.py ({py_version})")

    # 3. Read .deb control Version
    members = parse_deb_archive(deb_path)
    ctrl_key = [k for k in members.keys() if k.startswith("control.tar")][0]
    with tarfile.open(fileobj=io.BytesIO(members[ctrl_key]), mode="r:*") as tar:
        target = "./control" if "./control" in tar.getnames() else "control"
        extracted = tar.extractfile(target)
        if not extracted:
            raise VerificationFailure("Failed to extract control file")
        ctrl_raw = extracted.read().decode("utf-8")

    cv_match = re.search(r"^Version:\s*([^\s]+)", ctrl_raw, re.MULTILINE)
    if not cv_match:
        raise VerificationFailure("Could not parse Version from package control")
    deb_version = cv_match.group(1)
    base_deb_version = deb_version.split("-")[0]

    if base_deb_version != py_version:
        raise VerificationFailure(
            f"Version mismatch: package control Version '{deb_version}' != Python package '{py_version}'"
        )
    log_pass(f"Version synchronization verified (package {deb_version} matches python {py_version})")


# ── Check 4: APT Repository Structure ───────────────────────────────────
# The repository is built by package_debian.sh into dist/apt (validation) or into a
# checkout of alexpp90/apt (publish job); it is no longer committed to this repository.

def verify_apt_structure(apt_dir: Path) -> Path:
    if not apt_dir.is_dir():
        raise VerificationFailure(
            f"APT repository directory not found: {apt_dir} — build it with scripts/package_debian.sh")

    # Check dists structure
    release_path = apt_dir / "dists" / "trixie" / "Release"
    inrelease_path = apt_dir / "dists" / "trixie" / "InRelease"
    if not release_path.is_file():
        raise VerificationFailure(f"Missing Release file: {release_path}")
    if not inrelease_path.is_file():
        raise VerificationFailure(f"Missing InRelease file: {inrelease_path}")

    # Check Packages indices
    for arch in ("binary-all", "binary-amd64"):
        pkg_path = apt_dir / "dists" / "trixie" / "main" / arch / "Packages"
        pkg_gz = apt_dir / "dists" / "trixie" / "main" / arch / "Packages.gz"
        if not pkg_path.is_file():
            raise VerificationFailure(f"Missing Packages index: {pkg_path}")
        if not pkg_gz.is_file():
            raise VerificationFailure(f"Missing Packages.gz index: {pkg_gz}")

        # Check that Packages contains photo-selector-linux stanza
        pkg_text = pkg_path.read_text(encoding="utf-8")
        if "Package: photo-selector-linux" not in pkg_text:
            raise VerificationFailure(f"{pkg_path} does not index photo-selector-linux")
        if not pkg_text.endswith("\n"):
            raise VerificationFailure(f"{pkg_path} does not terminate with newline")

    # Check pool directory
    pool_dir = apt_dir / "pool" / "main" / "p" / "photo-selector-linux"
    if not pool_dir.is_dir():
        raise VerificationFailure(f"Missing pool directory: {pool_dir}")

    debs = list(pool_dir.glob("photo-selector-linux_*.deb"))
    if not debs:
        raise VerificationFailure(f"No .deb packages found in pool: {pool_dir}")

    # Check deb822 sources and setup.sh
    sources_file = apt_dir / "photo-selector.sources"
    if not sources_file.is_file():
        raise VerificationFailure(f"Missing sources file: {sources_file}")

    setup_file = apt_dir / "setup.sh"
    if not setup_file.is_file():
        raise VerificationFailure(f"Missing setup script: {setup_file}")
    if not os.access(setup_file, os.X_OK):
        raise VerificationFailure(f"setup.sh is not executable: {setup_file}")

    sources_text = sources_file.read_text(encoding="utf-8")
    if "https://alexpp90.github.io/apt/" not in sources_text:
        raise VerificationFailure(f"{sources_file} does not point at https://alexpp90.github.io/apt/")

    log_pass("APT repository directory structure and configuration verified")
    return debs[0]


# ── Check 5: Release Checksum Integrity ─────────────────────────────────

def verify_release_checksums(apt_dir: Path) -> None:
    trixie_dir = apt_dir / "dists" / "trixie"
    release_path = trixie_dir / "Release"
    rel_text = release_path.read_text(encoding="utf-8")

    # Parse SHA256 block
    sections = re.split(r"\n(?=[A-Z0-9]+Sum:|SHA256:|SHA512:)", rel_text)
    sha256_entries: Dict[str, Tuple[str, int]] = {}
    for sec in sections:
        if sec.startswith("SHA256:"):
            lines = sec.strip().splitlines()[1:]
            for line in lines:
                parts = line.strip().split()
                if len(parts) == 3:
                    sha, size, rel_path = parts
                    sha256_entries[rel_path] = (sha.lower(), int(size))

    if not sha256_entries:
        raise VerificationFailure("Release manifest contains no valid SHA256 entries")

    for rel_path, (expected_sha, expected_size) in sha256_entries.items():
        disk_path = trixie_dir / rel_path
        if not disk_path.is_file():
            raise VerificationFailure(f"File listed in Release does not exist on disk: {disk_path}")

        actual_size = disk_path.stat().st_size
        if actual_size != expected_size:
            raise VerificationFailure(
                f"Size mismatch for {rel_path}: Release states {expected_size}, disk has {actual_size}"
            )

        actual_sha = hashlib.sha256(disk_path.read_bytes()).hexdigest().lower()
        if actual_sha != expected_sha:
            raise VerificationFailure(
                f"SHA256 mismatch for {rel_path}:\n  Expected: {expected_sha}\n  Actual:   {actual_sha}"
            )

    log_pass(f"Release manifest checksum integrity verified ({len(sha256_entries)} index targets match)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Linux Desktop packaging & APT repository integrity")
    parser.add_argument("--product-dir", type=Path, default=None, help="Path to products/linux-desktop")
    parser.add_argument("--apt-dir", type=Path, default=None,
                        help="APT repository to verify (default: products/linux-desktop/dist/apt)")
    args = parser.parse_args()

    script_path = Path(__file__).resolve()
    product_dir = args.product_dir.resolve() if args.product_dir else script_path.parents[1]
    apt_dir = args.apt_dir.resolve() if args.apt_dir else product_dir / "dist" / "apt"

    print("━━━ Linux Desktop Debian Packaging & APT Verification ━━━")
    try:
        verify_debian_definitions(product_dir)
        target_deb = verify_apt_structure(apt_dir)
        verify_deb_archive(target_deb)
        verify_version_synchronization(product_dir, target_deb)
        verify_release_checksums(apt_dir)
        print("✔ ALL PACKAGING & APT VERIFICATION GATES PASSED")
        return 0
    except VerificationFailure as e:
        log_fail(str(e))
        return 1
    except Exception as e:
        log_fail(f"Unexpected verification error: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
