#!/usr/bin/env python3
"""generate_apt_repo.py — Pure Python Debian APT repository generator.

Scans debian packages in <repo-root>/pool/, generates Packages and Packages.gz indexes,
and creates the Release, InRelease, and Release.gpg manifests for Debian 13 (Trixie).
Requires zero external tools (pure Python standard library), with optional GPG signing.
The published repository is alexpp90/apt (GitHub Pages); see scripts/package_debian.sh.
"""

from __future__ import annotations

import argparse
import datetime
import email.utils
import gzip
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from typing import Dict, List, Optional, Tuple


def parse_deb_control(deb_path: Path) -> Dict[str, str]:
    """Extract and parse DEBIAN/control from a .deb file using pure Python."""
    with open(deb_path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise ValueError(f"Not a valid ar/deb archive: {deb_path}")

        control_tar_bytes: Optional[bytes] = None

        while True:
            header = f.read(60)
            if not header or len(header) < 60:
                break

            name = header[0:16].decode("ascii", errors="replace").strip().rstrip("/")
            size_str = header[48:58].decode("ascii", errors="replace").strip()
            size = int(size_str)

            data = f.read(size)
            if size % 2 != 0:
                f.read(1)  # ar 2-byte alignment padding

            if name.startswith("control.tar"):
                control_tar_bytes = data
                break

    if not control_tar_bytes:
        raise ValueError(f"control.tar not found in {deb_path}")

    # Parse control.tar (.gz or .xz)
    with tarfile.open(fileobj=io.BytesIO(control_tar_bytes), mode="r:*") as tar:
        control_member = None
        for member in tar.getmembers():
            if member.name in ("control", "./control"):
                control_member = member
                break
        if not control_member:
            raise ValueError(f"No control file inside control.tar of {deb_path}")

        f_control = tar.extractfile(control_member)
        if not f_control:
            raise ValueError(f"Failed to extract control file from {deb_path}")
        raw_text = f_control.read().decode("utf-8", errors="replace")

    # Parse deb822 key-values
    fields: Dict[str, str] = {}
    current_key: Optional[str] = None
    lines = raw_text.splitlines()

    for line in lines:
        if line.startswith(" ") or line.startswith("\t"):
            if current_key:
                if current_key == "Description":
                    fields[current_key] += "\n" + line
                else:
                    fields[current_key] = (fields[current_key] + " " + line.strip()).strip()
        elif ":" in line:
            key, val = line.split(":", 1)
            current_key = key.strip()
            fields[current_key] = val.strip()

    return fields


def compute_hashes_and_size(file_path: Path) -> Tuple[int, str, str, str]:
    """Compute size in bytes, MD5, SHA256, and SHA512 for a file."""
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    sha512 = hashlib.sha512()
    size = 0

    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            size += len(chunk)
            md5.update(chunk)
            sha256.update(chunk)
            sha512.update(chunk)

    return size, md5.hexdigest(), sha256.hexdigest(), sha512.hexdigest()


def write_deterministic_gzip(content_bytes: bytes, target_path: Path) -> None:
    """Write gzip file with mtime=0 for 100% reproducible builds."""
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0.0) as gz:
        gz.write(content_bytes)
    target_path.write_bytes(buf.getvalue())


def ensure_gpg_keyring(repo_root: Path) -> Optional[str]:
    """Ensure dedicated GPG archive signing key exists and return key ID."""
    gpg_bin = shutil.which("gpg")
    if not gpg_bin:
        return None

    keyring_dir = repo_root / ".gnupg_repo"
    keyring_dir.mkdir(parents=True, exist_ok=True)
    keyring_dir.chmod(0o700)

    env = os.environ.copy()
    env["GNUPGHOME"] = str(keyring_dir)

    # Check if a key already exists
    list_res = subprocess.run(
        [gpg_bin, "--list-secret-keys", "--with-colons"],
        capture_output=True,
        text=True,
        env=env,
    )

    key_id: Optional[str] = None
    for line in list_res.stdout.splitlines():
        parts = line.split(":")
        if parts[0] == "sec" and len(parts) > 4:
            key_id = parts[4]
            break

    if not key_id:
        batch_script = (
            "Key-Type: RSA\n"
            "Key-Length: 2048\n"
            "Subkey-Type: RSA\n"
            "Subkey-Length: 2048\n"
            "Name-Real: Photo Selector Archive Automatic Signing Key\n"
            "Name-Email: alexanderpatz@users.noreply.github.com\n"
            "Expire-Date: 0\n"
            "%no-protection\n"
            "%commit\n"
        )
        gen_res = subprocess.run(
            [gpg_bin, "--batch", "--generate-key"],
            input=batch_script,
            text=True,
            capture_output=True,
            env=env,
        )
        if gen_res.returncode != 0:
            return None

        # Re-query key_id
        list_res = subprocess.run(
            [gpg_bin, "--list-secret-keys", "--with-colons"],
            capture_output=True,
            text=True,
            env=env,
        )
        for line in list_res.stdout.splitlines():
            parts = line.split(":")
            if parts[0] == "sec" and len(parts) > 4:
                key_id = parts[4]
                break

    if key_id:
        # Export binary keyring (.gpg) and ASCII armored (.asc / KEY.gpg)
        bin_keyring = repo_root / "photo-selector-archive-keyring.gpg"
        subprocess.run(
            [gpg_bin, "--export", "-o", str(bin_keyring), key_id],
            check=False,
            env=env,
        )

        asc_keyring = repo_root / "photo-selector-archive-keyring.asc"
        subprocess.run(
            [gpg_bin, "--armor", "--export", "-o", str(asc_keyring), key_id],
            check=False,
            env=env,
        )

        key_gpg = repo_root / "KEY.gpg"
        subprocess.run(
            [gpg_bin, "--armor", "--export", "-o", str(key_gpg), key_id],
            check=False,
            env=env,
        )

    return key_id


def generate_repository(
    repo_root: Path,
    suite: str = "trixie",
    component: str = "main",
    gpg_key_id: Optional[str] = None,
    sign: bool = True,
) -> None:
    """Build complete APT repository tree, metadata, and indexes.

    With sign=False (validation builds) no key is generated or used and InRelease is the
    unsigned Release text — CI's publish job signs with the key from its secrets.
    """
    repo_root = repo_root.resolve()
    pool_dir = repo_root / "pool" / component
    dist_dir = repo_root / "dists" / suite
    main_dir = dist_dir / component

    if not pool_dir.exists():
        pool_dir.mkdir(parents=True, exist_ok=True)

    # Discover all .deb packages
    deb_files = sorted(list(pool_dir.rglob("*.deb")))
    if not deb_files:
        print(f"Warning: No .deb files found in {pool_dir}")

    # Build package records
    all_packages: List[Tuple[Dict[str, str], Path]] = []
    for deb in deb_files:
        try:
            fields = parse_deb_control(deb)
            all_packages.append((fields, deb))
        except Exception as e:
            print(f"Warning: Skipping corrupted .deb {deb.name}: {e}")

    # Supported architectures (amd64 and all)
    architectures = ["all", "amd64"]

    for arch in architectures:
        arch_dir = main_dir / f"binary-{arch}"
        arch_dir.mkdir(parents=True, exist_ok=True)

        package_stanzas: List[str] = []
        for fields, deb_path in all_packages:
            pkg_arch = fields.get("Architecture", "all")
            # If arch is 'all', it belongs in both binary-all and binary-amd64
            if pkg_arch != "all" and pkg_arch != arch:
                continue

            size, md5, sha256, sha512 = compute_hashes_and_size(deb_path)
            rel_filename = deb_path.relative_to(repo_root).as_posix()

            # Construct RFC 822 stanza
            lines = [
                f"Package: {fields.get('Package', '')}",
                f"Version: {fields.get('Version', '')}",
                f"Architecture: {pkg_arch}",
                f"Maintainer: {fields.get('Maintainer', '')}",
                f"Installed-Size: {fields.get('Installed-Size', '0')}",
            ]
            if fields.get("Depends"):
                lines.append(f"Depends: {fields['Depends']}")
            if fields.get("Recommends"):
                lines.append(f"Recommends: {fields['Recommends']}")
            if fields.get("Section"):
                lines.append(f"Section: {fields['Section']}")
            if fields.get("Priority"):
                lines.append(f"Priority: {fields['Priority']}")
            if fields.get("Homepage"):
                lines.append(f"Homepage: {fields['Homepage']}")
            lines.append(f"Filename: {rel_filename}")
            lines.append(f"Size: {size}")
            lines.append(f"SHA256: {sha256}")
            lines.append(f"SHA512: {sha512}")
            lines.append(f"Description: {fields.get('Description', 'No description')}")

            package_stanzas.append("\n".join(lines))

        # Write Packages and Packages.gz
        if package_stanzas:
            packages_text = "\n\n".join(package_stanzas) + "\n"
        else:
            packages_text = ""
        packages_content = packages_text.encode("utf-8")
        packages_path = arch_dir / "Packages"
        packages_gz_path = arch_dir / "Packages.gz"

        packages_path.write_bytes(packages_content)
        write_deterministic_gzip(packages_content, packages_gz_path)
        print(f"Generated {packages_path.relative_to(repo_root)}")
        print(f"Generated {packages_gz_path.relative_to(repo_root)}")

    # Index files to hash in Release manifest
    files_to_hash = []
    for arch in architectures:
        files_to_hash.append(main_dir / f"binary-{arch}" / "Packages")
        files_to_hash.append(main_dir / f"binary-{arch}" / "Packages.gz")

    # Build Release manifest
    rfc2822_date = email.utils.format_datetime(datetime.datetime.now(datetime.timezone.utc))

    release_headers = [
        "Origin: PhotoSelector",
        "Label: Photo Selector Toolbox",
        f"Suite: {suite}",
        f"Codename: {suite}",
        "Version: 13",
        f"Date: {rfc2822_date}",
        "Architectures: amd64 all",
        f"Components: {component}",
        "Description: Official Debian 13 (Trixie) repository for Blitzlens",
    ]

    md5_entries = []
    sha256_entries = []
    sha512_entries = []

    for fpath in files_to_hash:
        rel_to_dist = fpath.relative_to(dist_dir).as_posix()
        size, md5_val, sha256_val, sha512_val = compute_hashes_and_size(fpath)
        md5_entries.append(f" {md5_val} {size} {rel_to_dist}")
        sha256_entries.append(f" {sha256_val} {size} {rel_to_dist}")
        sha512_entries.append(f" {sha512_val} {size} {rel_to_dist}")

    release_content = (
        "\n".join(release_headers)
        + "\nMD5Sum:\n"
        + "\n".join(md5_entries)
        + "\nSHA256:\n"
        + "\n".join(sha256_entries)
        + "\nSHA512:\n"
        + "\n".join(sha512_entries)
        + "\n"
    )

    release_path = dist_dir / "Release"
    release_path.write_text(release_content, encoding="utf-8")
    print(f"Generated {release_path.relative_to(repo_root)}")

    # Handle InRelease and Release.gpg cryptographic signing
    inrelease_path = dist_dir / "InRelease"
    release_gpg_path = dist_dir / "Release.gpg"

    gpg_bin = shutil.which("gpg") if sign else None
    signed = False

    if gpg_bin:
        key_id = gpg_key_id or ensure_gpg_keyring(repo_root)
        if key_id:
            env = os.environ.copy()
            keyring_dir = repo_root / ".gnupg_repo"
            if keyring_dir.exists():
                env["GNUPGHOME"] = str(keyring_dir)

            res_inrelease = subprocess.run(
                [
                    gpg_bin,
                    "--batch",
                    "--yes",
                    "--clearsign",
                    "--digest-algo",
                    "SHA512",
                    "-u",
                    key_id,
                    "-o",
                    str(inrelease_path),
                    str(release_path),
                ],
                capture_output=True,
                text=True,
                env=env,
            )
            res_gpg = subprocess.run(
                [
                    gpg_bin,
                    "--batch",
                    "--yes",
                    "--detach-sign",
                    "--armor",
                    "--digest-algo",
                    "SHA512",
                    "-u",
                    key_id,
                    "-o",
                    str(release_gpg_path),
                    str(release_path),
                ],
                capture_output=True,
                text=True,
                env=env,
            )
            if res_inrelease.returncode == 0 and res_gpg.returncode == 0:
                signed = True
                print(f"Generated {inrelease_path.relative_to(repo_root)} (GPG clearsigned)")
                print(f"Generated {release_gpg_path.relative_to(repo_root)} (detached signature)")

    if not signed:
        # Fallback inline release if GPG not available
        inrelease_path.write_text(release_content, encoding="utf-8")
        print(f"Generated {inrelease_path.relative_to(repo_root)} (unsigned fallback)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Debian APT repository metadata")
    parser.add_argument("--repo-root", type=Path, required=True,
                        help="APT repository directory (a checkout of alexpp90/apt, or a staging dir)")
    parser.add_argument("--suite", default="trixie", help="Distribution suite (default: trixie)")
    parser.add_argument("--component", default="main", help="Component (default: main)")
    parser.add_argument("--gpg-key-id", default=os.getenv("GPG_KEY_ID"), help="GPG Key ID to sign Release")
    parser.add_argument("--unsigned", action="store_true",
                        help="do not sign (and never generate a key) — for validation builds")
    args = parser.parse_args()

    try:
        generate_repository(args.repo_root, args.suite, args.component, args.gpg_key_id,
                            sign=not args.unsigned)
        return 0
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
