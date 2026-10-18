#!/usr/bin/env python3
"""package_deb.py — Pure Python cross-platform Debian package (.deb) builder.

Constructs fully compliant .deb packages on any operating system (macOS, Linux, Windows)
using only the Python standard library (ar + tarfile + gzip + hashlib).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Dict, List, Tuple

FIXED_MTIME = 1760097600  # Sat, 10 Oct 2026 12:00:00 UTC


def make_ar_header(name: str, size: int, mtime: int = FIXED_MTIME, mode: int = 0o100644) -> bytes:
    """Construct a standard 60-byte Unix ar archive member header."""
    file_id = f"{name}/".ljust(16)
    mtime_str = str(mtime).ljust(12)
    uid_str = "0".ljust(6)
    gid_str = "0".ljust(6)
    mode_str = oct(mode)[2:].ljust(8)
    size_str = str(size).ljust(10)
    trailer = b"`\n"
    header = (file_id + mtime_str + uid_str + gid_str + mode_str + size_str).encode("ascii") + trailer
    if len(header) != 60:
        raise ValueError(f"Invalid ar header length: {len(header)} (expected 60)")
    return header


def gzip_compress_deterministic(content: bytes) -> bytes:
    """Compress bytes with gzip using fixed mtime=0 for reproducible builds."""
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0.0) as gz:
        gz.write(content)
    return buf.getvalue()


def stage_package_payload(product_dir: Path, staging_root: Path) -> int:
    """Stage files into target FHS layout and return installed size in KiB."""
    usr_bin = staging_root / "usr" / "bin"
    usr_lib = staging_root / "usr" / "lib" / "python3" / "dist-packages" / "photo_selector_linux"
    usr_apps = staging_root / "usr" / "share" / "applications"
    usr_meta = staging_root / "usr" / "share" / "metainfo"
    usr_icons_svg = staging_root / "usr" / "share" / "icons" / "hicolor" / "scalable" / "apps"
    usr_icons_png = staging_root / "usr" / "share" / "icons" / "hicolor" / "128x128" / "apps"
    usr_doc = staging_root / "usr" / "share" / "doc" / "photo-selector-linux"

    for d in (usr_bin, usr_lib, usr_apps, usr_meta, usr_icons_svg, usr_icons_png, usr_doc):
        d.mkdir(parents=True, exist_ok=True)

    # 1. Launcher in /usr/bin/photo-selector-linux
    launcher = usr_bin / "photo-selector-linux"
    launcher_content = (
        "#!/usr/bin/env python3\n"
        "# Photo Selector Linux executable launcher\n"
        "import sys\n"
        "from photo_selector_linux.__main__ import main\n"
        "if __name__ == '__main__':\n"
        "    sys.exit(main())\n"
    )
    launcher.write_text(launcher_content, encoding="utf-8")
    launcher.chmod(0o755)

    # 2. Python package sources
    src_pkg = product_dir / "src" / "photo_selector_linux"
    if src_pkg.exists():
        for root, dirs, files in os.walk(src_pkg):
            dirs[:] = [d for d in dirs if d not in ("__pycache__", ".pytest_cache")]
            rel = Path(root).relative_to(src_pkg)
            target_dir = usr_lib / rel
            target_dir.mkdir(parents=True, exist_ok=True)
            for f in files:
                if f.endswith((".pyc", ".pyo")):
                    continue
                src_f = Path(root) / f
                dst_f = target_dir / f
                shutil.copy2(src_f, dst_f)
                dst_f.chmod(0o644)

    # 3. Desktop Entry files
    desktop_src = product_dir / "data" / "org.photoselector.Linux.desktop"
    if desktop_src.exists():
        shutil.copy2(desktop_src, usr_apps / "org.photoselector.Linux.desktop")
        (usr_apps / "org.photoselector.Linux.desktop").chmod(0o644)

    # 4. AppStream Metainfo
    meta_src = product_dir / "data" / "org.photoselector.Linux.metainfo.xml"
    if meta_src.exists():
        shutil.copy2(meta_src, usr_meta / "org.photoselector.Linux.metainfo.xml")
        (usr_meta / "org.photoselector.Linux.metainfo.xml").chmod(0o644)

    # 5. Icons
    svg_src = product_dir / "data" / "icons" / "hicolor" / "scalable" / "apps" / "org.photoselector.Linux.svg"
    if svg_src.exists():
        shutil.copy2(svg_src, usr_icons_svg / "org.photoselector.Linux.svg")
        (usr_icons_svg / "org.photoselector.Linux.svg").chmod(0o644)

    png_src = product_dir / "data" / "icons" / "hicolor" / "128x128" / "apps" / "org.photoselector.Linux.png"
    if png_src.exists():
        shutil.copy2(png_src, usr_icons_png / "org.photoselector.Linux.png")
        (usr_icons_png / "org.photoselector.Linux.png").chmod(0o644)

    # 6. Documentation (copyright, changelog.Debian.gz)
    copyright_src = product_dir / "debian" / "copyright"
    if copyright_src.exists():
        shutil.copy2(copyright_src, usr_doc / "copyright")
        (usr_doc / "copyright").chmod(0o644)

    changelog_src = product_dir / "debian" / "changelog"
    if changelog_src.exists():
        ch_bytes = changelog_src.read_bytes()
        gz_bytes = gzip_compress_deterministic(ch_bytes)
        ch_dst = usr_doc / "changelog.Debian.gz"
        ch_dst.write_bytes(gz_bytes)
        ch_dst.chmod(0o644)

    # Calculate Installed-Size in KiB
    total_bytes = 0
    for root, _, files in os.walk(staging_root):
        for f in files:
            total_bytes += (Path(root) / f).stat().st_size
    return max(1, (total_bytes + 1023) // 1024)


def parse_control_template(control_path: Path) -> Tuple[Dict[str, str], str]:
    """Parse debian/control into field dictionary and description block."""
    raw = control_path.read_text(encoding="utf-8")
    stanzas = raw.strip().split("\n\n")
    bin_stanza = stanzas[-1]

    fields: Dict[str, str] = {}
    current_key = None
    desc_lines: List[str] = []
    in_desc = False

    for line in bin_stanza.splitlines():
        if line.startswith("Description:"):
            in_desc = True
            current_key = "Description"
            fields["Description"] = line.split(":", 1)[1].strip()
        elif in_desc and (line.startswith(" ") or line.startswith("\t")):
            desc_lines.append(line)
        elif ":" in line and not line.startswith(" "):
            in_desc = False
            k, v = line.split(":", 1)
            current_key = k.strip()
            fields[current_key] = v.strip()
        elif current_key and (line.startswith(" ") or line.startswith("\t")):
            # Continuation line for a non-description field (e.g. Depends)
            val = line.strip()
            if fields.get(current_key):
                fields[current_key] += " " + val
            else:
                fields[current_key] = val

    full_desc = fields.get("Description", "")
    if desc_lines:
        full_desc += "\n" + "\n".join(desc_lines)

    # Clean debhelper macro substitutions if present
    if "Depends" in fields:
        deps = [
            d.strip()
            for d in fields["Depends"].split(",")
            if d.strip() and not d.strip().startswith("${")
        ]
        fields["Depends"] = ", ".join(deps)

    return fields, full_desc


def build_control_file(
    control_template_path: Path,
    version: str,
    installed_kib: int,
) -> str:
    """Format standard binary DEBIAN/control file."""
    # debian/control is the single source of Depends/Recommends/Description; no inline copy.
    if not control_template_path.is_file():
        raise FileNotFoundError(f"debian/control not found at {control_template_path}")
    fields, full_desc = parse_control_template(control_template_path)

    # Standard order of fields
    lines = [
        f"Package: {fields.get('Package', 'photo-selector-linux')}",
        f"Version: {version}",
        f"Architecture: {fields.get('Architecture', 'all')}",
        f"Maintainer: {fields.get('Maintainer', 'Alexander Patz <alexpp90@users.noreply.github.com>')}",
        f"Installed-Size: {installed_kib}",
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
    lines.append(f"Description: {full_desc}")

    return "\n".join(lines) + "\n"


def build_deb_pure_python(
    product_dir: Path,
    staging_root: Path,
    output_deb: Path,
    version: str,
) -> None:
    """Assemble compliant .deb archive using pure Python ar and tarfile."""
    installed_kib = stage_package_payload(product_dir, staging_root)

    control_content = build_control_file(
        product_dir / "debian" / "control",
        version,
        installed_kib,
    )

    # Build md5sums
    md5_lines = []
    for root, _, files in os.walk(staging_root):
        for f in sorted(files):
            fp = Path(root) / f
            rel = fp.relative_to(staging_root).as_posix()
            digest = hashlib.md5(fp.read_bytes()).hexdigest()
            md5_lines.append(f"{digest}  {rel}")
    md5_content = "\n".join(md5_lines) + "\n"

    # Assemble control.tar.gz
    ctrl_buf = io.BytesIO()
    with tarfile.open(fileobj=ctrl_buf, mode="w:gz", format=tarfile.PAX_FORMAT) as tar:
        # ./control
        c_bytes = control_content.encode("utf-8")
        ti = tarfile.TarInfo(name="./control")
        ti.size = len(c_bytes)
        ti.mode = 0o644
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = "root"
        ti.mtime = FIXED_MTIME
        tar.addfile(ti, io.BytesIO(c_bytes))

        # ./md5sums
        m_bytes = md5_content.encode("utf-8")
        ti = tarfile.TarInfo(name="./md5sums")
        ti.size = len(m_bytes)
        ti.mode = 0o644
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = "root"
        ti.mtime = FIXED_MTIME
        tar.addfile(ti, io.BytesIO(m_bytes))

    control_tar_gz = ctrl_buf.getvalue()

    # Assemble data.tar.gz
    data_buf = io.BytesIO()
    with tarfile.open(fileobj=data_buf, mode="w:gz", format=tarfile.PAX_FORMAT) as tar:
        # Root ./ directory
        ti = tarfile.TarInfo(name="./")
        ti.type = tarfile.DIRTYPE
        ti.mode = 0o755
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = "root"
        ti.mtime = FIXED_MTIME
        tar.addfile(ti)

        # Walk staged hierarchy
        for root, dirs, files in os.walk(staging_root):
            dirs.sort()
            files.sort()
            rel_dir = Path(root).relative_to(staging_root)
            if str(rel_dir) != ".":
                ti = tarfile.TarInfo(name=f"./{rel_dir.as_posix()}")
                ti.type = tarfile.DIRTYPE
                ti.mode = 0o755
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = "root"
                ti.mtime = FIXED_MTIME
                tar.addfile(ti)

            for f in files:
                fp = Path(root) / f
                rel_f = fp.relative_to(staging_root).as_posix()
                content = fp.read_bytes()
                ti = tarfile.TarInfo(name=f"./{rel_f}")
                ti.size = len(content)
                ti.mode = 0o755 if "bin/" in rel_f else 0o644
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = "root"
                ti.mtime = FIXED_MTIME
                tar.addfile(ti, io.BytesIO(content))

    data_tar_gz = data_buf.getvalue()

    # Assemble .deb container (ar archive)
    debian_binary = b"2.0\n"
    output_deb.parent.mkdir(parents=True, exist_ok=True)
    with open(output_deb, "wb") as f:
        f.write(b"!<arch>\n")

        # 1. debian-binary
        f.write(make_ar_header("debian-binary", len(debian_binary)))
        f.write(debian_binary)
        if len(debian_binary) % 2 != 0:
            f.write(b"\n")

        # 2. control.tar.gz
        f.write(make_ar_header("control.tar.gz", len(control_tar_gz)))
        f.write(control_tar_gz)
        if len(control_tar_gz) % 2 != 0:
            f.write(b"\n")

        # 3. data.tar.gz
        f.write(make_ar_header("data.tar.gz", len(data_tar_gz)))
        f.write(data_tar_gz)
        if len(data_tar_gz) % 2 != 0:
            f.write(b"\n")


def build_deb_native(
    product_dir: Path,
    staging_root: Path,
    output_deb: Path,
    version: str,
) -> None:
    """Build .deb using host dpkg-deb command."""
    debian_dir = staging_root / "DEBIAN"
    debian_dir.mkdir(parents=True, exist_ok=True)

    installed_kib = stage_package_payload(product_dir, staging_root)
    control_content = build_control_file(
        product_dir / "debian" / "control",
        version,
        installed_kib,
    )
    (debian_dir / "control").write_text(control_content, encoding="utf-8")

    # -Zxz: recent dpkg defaults to zstd, which Python's tarfile (generate_apt_repo.py,
    # verify_package.py) cannot read; xz is universally supported by apt.
    cmd = ["dpkg-deb", "-Zxz", "--build", "--root-owner-group", str(staging_root), str(output_deb)]
    subprocess.run(cmd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Pure Python Debian Package Builder")
    parser.add_argument("--product-dir", type=Path, required=True, help="Path to products/linux-desktop")
    parser.add_argument("--output-dir", type=Path, required=True, help="Output destination folder")
    parser.add_argument("--version", type=str, default="0.1.0", help="Package version")
    parser.add_argument("--output-filename", type=str, default="", help="Optional explicit output filename")
    parser.add_argument("--mode", choices=["python", "native"], default="python", help="Packaging engine")
    args = parser.parse_args()

    product_dir = args.product_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    filename = args.output_filename or f"photo-selector-linux_{args.version}_all.deb"
    output_deb = output_dir / filename

    with tempfile.TemporaryDirectory(prefix="staged_deb_") as tmp_dir:
        staging_root = Path(tmp_dir)
        if args.mode == "native" and shutil.which("dpkg-deb"):
            build_deb_native(product_dir, staging_root, output_deb, args.version)
        else:
            build_deb_pure_python(product_dir, staging_root, output_deb, args.version)
        print(f"Generated Debian package: {output_deb} ({output_deb.stat().st_size} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
