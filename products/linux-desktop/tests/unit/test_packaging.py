"""Unit tests for Linux Desktop Debian packaging and the generated APT repository.

Adheres to:
- R-LINUX-PKG-01: Debian 13 (Trixie) packaging metadata (control, rules, changelog)
- R-LINUX-PKG-02: DFSG compliance and machine-readable copyright (DEP-5)
- R-LINUX-PKG-03: APT repository architecture (dists/trixie/..., pool/main/...), published to alexpp90/apt
- R-LINUX-PKG-04: Modern deb822 repository source format
- R-LINUX-PKG-05: Automated packaging and distribution tooling
"""

import gzip
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile

import pytest
from pathlib import Path


def get_product_root() -> Path:
    """Return products/linux-desktop directory."""
    return Path(__file__).resolve().parents[2]


def get_repo_root() -> Path:
    """Return repository root directory."""
    return get_product_root().parents[1]


def test_debian_control_metadata():
    """Verify debian/control fields and dependency definitions (R-LINUX-PKG-01)."""
    product_dir = get_product_root()
    control_file = product_dir / "debian" / "control"
    assert control_file.is_file(), f"Missing control file at {control_file}"

    content = control_file.read_text(encoding="utf-8")
    stanzas = content.strip().split("\n\n")
    assert len(stanzas) >= 2, "debian/control must contain source and binary stanzas"

    # Source stanza
    src_stanza = stanzas[0]
    assert "Source: photo-selector-linux" in src_stanza
    assert "debhelper-compat (= 13)" in src_stanza
    assert "Section: graphics" in src_stanza
    assert "Standards-Version: 4.7.0" in src_stanza or "Standards-Version: 4.6.2" in src_stanza

    # Binary stanza
    bin_stanza = stanzas[1]
    assert "Package: photo-selector-linux" in bin_stanza
    assert "Architecture: all" in bin_stanza

    # Mandatory runtime dependencies
    for dep in (
        "python3",
        "python3-gi",
        "gir1.2-gtk-4.0",
        "gir1.2-adw-1",
        "gir1.2-gexiv2-0.10",
        "gir1.2-gdkpixbuf-2.0",
        "libraw-bin",
        "python3-numpy",
        "python3-pil",
    ):
        assert dep in bin_stanza, f"Missing dependency '{dep}' in binary stanza"


def test_dfsg_compliance():
    """Verify machine-readable DEP-5 copyright and license terms (R-LINUX-PKG-02)."""
    product_dir = get_product_root()
    copyright_file = product_dir / "debian" / "copyright"
    assert copyright_file.is_file(), f"Missing copyright file at {copyright_file}"

    text = copyright_file.read_text(encoding="utf-8")
    assert "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/" in text
    assert "Upstream-Name: photo-selector-linux" in text
    assert "License: MIT" in text
    assert "Copyright:" in text


def test_debian_rules_syntax():
    """Verify debian/rules executable permissions and pybuild sequence (R-LINUX-PKG-01)."""
    product_dir = get_product_root()
    rules_file = product_dir / "debian" / "rules"
    assert rules_file.is_file(), f"Missing rules file at {rules_file}"
    assert os.access(rules_file, os.X_OK), "debian/rules must be executable"

    content = rules_file.read_text(encoding="utf-8")
    assert content.startswith("#!/usr/bin/make -f"), "debian/rules must start with #!/usr/bin/make -f"
    assert "pybuild" in content or "dh $@" in content


def test_debian_changelog_format():
    """Verify debian/changelog syntax and target suite trixie (R-LINUX-PKG-01)."""
    product_dir = get_product_root()
    changelog_file = product_dir / "debian" / "changelog"
    assert changelog_file.is_file(), f"Missing changelog file at {changelog_file}"

    lines = changelog_file.read_text(encoding="utf-8").splitlines()
    assert len(lines) > 0, "debian/changelog is empty"

    header = lines[0]
    match = re.match(r"^photo-selector-linux\s+\(([^)]+)\)\s+([a-zA-Z0-9_-]+);", header)
    assert match is not None, f"Invalid changelog header: {header}"
    suite = match.group(2)
    assert suite in ("trixie", "unstable"), f"Target distribution must be trixie, got: {suite}"

    trailer = [line for line in lines if line.strip().startswith("--")]
    assert len(trailer) > 0, "debian/changelog missing trailer line"
    assert re.search(r"\+\d{4}|UTC|GMT", trailer[0]), "Trailer must contain timezone offset"


def test_desktop_integration_files():
    """Verify desktop entry, AppStream metainfo branding, and icon assets (R-LINUX-PKG-01)."""
    product_dir = get_product_root()
    data_dir = product_dir / "data"

    desktop_file = data_dir / "app.alpatix.Blitzlens.desktop"
    assert desktop_file.is_file(), "Missing app.alpatix.Blitzlens.desktop"
    desktop_text = desktop_file.read_text(encoding="utf-8")
    assert "Type=Application" in desktop_text
    assert "Exec=photo-selector-linux %F" in desktop_text
    assert "Icon=app.alpatix.Blitzlens" in desktop_text
    assert "StartupWMClass=app.alpatix.Blitzlens" in desktop_text
    assert "StartupNotify=true" in desktop_text

    meta_file = data_dir / "app.alpatix.Blitzlens.metainfo.xml"
    assert meta_file.is_file(), "Missing app.alpatix.Blitzlens.metainfo.xml"
    meta_text = meta_file.read_text(encoding="utf-8")
    assert "<id>app.alpatix.Blitzlens</id>" in meta_text
    assert "<binary>photo-selector-linux</binary>" in meta_text
    assert "<branding>" in meta_text
    assert "#ffc93c" in meta_text.lower()
    assert "<name>Blitzlens</name>" in meta_text
    assert "<replaces>\n    <id>org.photoselector.Linux</id>" in meta_text
    assert "<id>org.photoselector.Linux.desktop</id>" in meta_text  # provides: the old desktop id
    assert '<launchable type="desktop-id">app.alpatix.Blitzlens.desktop</launchable>' in meta_text
    assert "Name=Blitzlens\n" in desktop_text
    # Studio rule: the word appears only inside the technical id.
    assert "alpatix" not in meta_text.replace("app.alpatix.Blitzlens", "").lower()  # brand-guard: allow
    assert "<developer id=\"io.github.alexpp90\">" in meta_text

    icons = data_dir / "icons" / "hicolor"
    svg_icon = icons / "scalable" / "apps" / "app.alpatix.Blitzlens.svg"
    symbolic = icons / "symbolic" / "apps" / "app.alpatix.Blitzlens-symbolic.svg"
    assert svg_icon.is_file(), "Missing scalable SVG icon"
    assert symbolic.is_file(), "Missing symbolic icon"
    assert "#FFC93C" in svg_icon.read_text(encoding="utf-8").upper()
    assert not (icons / "scalable" / "apps" / "org.photoselector.Linux.svg").exists()
    for size in (16, 24, 128):
        png_icon = icons / f"{size}x{size}" / "apps" / "app.alpatix.Blitzlens.png"
        assert png_icon.is_file(), f"Missing {size}px PNG icon"
        png_bytes = png_icon.read_bytes()
        assert png_bytes[:8] == b"\x89PNG\r\n\x1a\n", "Invalid PNG signature"
        assert (int.from_bytes(png_bytes[16:20], "big"), int.from_bytes(png_bytes[20:24], "big")) == (size, size)
    install = (product_dir / "debian" / "install").read_text(encoding="utf-8")
    for rel in ("scalable/apps/app.alpatix.Blitzlens.svg", "symbolic/apps/app.alpatix.Blitzlens-symbolic.svg",
                "16x16/apps/app.alpatix.Blitzlens.png", "24x24/apps/app.alpatix.Blitzlens.png",
                "128x128/apps/app.alpatix.Blitzlens.png"):
        assert f"data/icons/hicolor/{rel} usr/share/icons/hicolor/{rel.rsplit('/', 1)[0]}/" in install


def test_version_consistent_across_sources():
    product_dir = get_product_root()
    pyproject = re.search(r'^version = "([^"]+)"', (product_dir / "pyproject.toml").read_text(), re.M).group(1)
    deb = re.match(r"photo-selector-linux \(([\d.]+)-", (product_dir / "debian" / "changelog").read_text()).group(1)
    init = re.search(r'__version__ = "([^"]+)"',
                     (product_dir / "src" / "photo_selector_linux" / "__init__.py").read_text()).group(1)
    import xml.etree.ElementTree as ET
    meta = ET.parse(product_dir / "data" / "app.alpatix.Blitzlens.metainfo.xml").getroot().find("releases/release")
    assert pyproject == deb == init == meta.get("version") == "0.2.0"


@pytest.mark.skipif(not shutil.which("dpkg-deb"), reason="dpkg-deb not available")
def test_package_deb_native_builder(tmp_path):
    """Verify native dpkg-deb builder produces valid .deb archive."""
    product_dir = get_product_root()
    packager_script = product_dir / "scripts" / "package_deb.py"
    assert packager_script.is_file(), "Missing package_deb.py script"

    out_dir = tmp_path / "deb_out"
    out_dir.mkdir()

    cmd = [
        sys.executable,
        str(packager_script),
        "--product-dir", str(product_dir),
        "--output-dir", str(out_dir),
        "--version", "0.2.0",
        "--output-filename", "test-package-native_0.2.0_all.deb",
        "--mode", "native"
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode == 0, f"package_deb.py failed:\n{res.stderr}"

    deb_path = out_dir / "test-package-native_0.2.0_all.deb"
    assert deb_path.is_file(), f"Output package not created at {deb_path}"

    verify_script_dir = product_dir / "scripts"
    sys.path.insert(0, str(verify_script_dir))
    import verify_package
    sys.path.pop(0)

    verify_package.verify_deb_archive(deb_path)


def test_package_deb_builder(tmp_path):
    """Verify pure-Python builder produces valid .deb archive (R-LINUX-PKG-05)."""
    product_dir = get_product_root()
    packager_script = product_dir / "scripts" / "package_deb.py"
    assert packager_script.is_file(), "Missing package_deb.py script"

    out_dir = tmp_path / "deb_out"
    out_dir.mkdir()

    cmd = [
        sys.executable,
        str(packager_script),
        "--product-dir",
        str(product_dir),
        "--output-dir",
        str(out_dir),
        "--version",
        "0.2.0",
        "--output-filename",
        "test-package_0.2.0_all.deb",
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode == 0, f"package_deb.py failed:\n{res.stderr}"

    deb_path = out_dir / "test-package_0.2.0_all.deb"
    assert deb_path.is_file(), f"Output package not created at {deb_path}"
    assert deb_path.stat().st_size > 1024, "Package size suspiciously small"

    # Inspect ar container
    with open(deb_path, "rb") as f:
        magic = f.read(8)
        assert magic == b"!<arch>\n", f"Invalid ar magic: {magic}"

        member_names = []
        while True:
            hdr = f.read(60)
            if not hdr or len(hdr) < 60:
                break
            m_name = hdr[:16].decode("ascii", errors="replace").strip().rstrip("/")
            m_size = int(hdr[48:58].decode("ascii", errors="replace").strip())
            member_names.append(m_name)
            data = f.read(m_size)
            if m_size % 2 != 0:
                f.read(1)

            if m_name == "debian-binary":
                assert data == b"2.0\n", f"Invalid debian-binary content: {data}"
            elif m_name.startswith("control.tar"):
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as t:
                    ctrl_names = t.getnames()
                    assert "./control" in ctrl_names or "control" in ctrl_names
                    assert "./md5sums" in ctrl_names or "md5sums" in ctrl_names
            elif m_name.startswith("data.tar"):
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as t:
                    data_names = t.getnames()
                    assert any("usr/bin/photo-selector-linux" in n for n in data_names)
                    assert any("app.alpatix.Blitzlens.desktop" in n for n in data_names)

        assert "debian-binary" in member_names
        assert any(n.startswith("control.tar") for n in member_names)
        assert any(n.startswith("data.tar") for n in member_names)


@pytest.fixture(scope="module")
def apt_repo(tmp_path_factory) -> Path:
    """Build a complete APT repository the way CI does (package_debian.sh, unsigned).

    The repository is no longer committed here — CI publishes it to alexpp90/apt — so the
    tests build one from the current sources into a temporary directory.
    """
    apt_dir = tmp_path_factory.mktemp("apt")
    script = get_product_root() / "scripts" / "package_debian.sh"
    res = subprocess.run(["bash", str(script), "--apt-dir", str(apt_dir)], capture_output=True, text=True)
    assert res.returncode == 0, f"package_debian.sh failed:\n{res.stdout}\n{res.stderr}"
    return apt_dir


def test_apt_repository_structure(apt_repo):
    """Verify the generated APT repository tree structure and packages (R-LINUX-PKG-03)."""
    apt_dir = apt_repo

    release_file = apt_dir / "dists" / "trixie" / "Release"
    inrelease_file = apt_dir / "dists" / "trixie" / "InRelease"
    assert release_file.is_file(), "Missing dists/trixie/Release"
    assert inrelease_file.is_file(), "Missing dists/trixie/InRelease"

    for arch in ("binary-all", "binary-amd64"):
        pkg_file = apt_dir / "dists" / "trixie" / "main" / arch / "Packages"
        pkg_gz = apt_dir / "dists" / "trixie" / "main" / arch / "Packages.gz"
        assert pkg_file.is_file(), f"Missing {pkg_file}"
        assert pkg_gz.is_file(), f"Missing {pkg_gz}"

        text = pkg_file.read_text(encoding="utf-8")
        assert "Package: photo-selector-linux" in text
        assert "Filename: pool/main/p/photo-selector-linux/photo-selector-linux_" in text

        # Verify Packages.gz decompresses cleanly
        gz_data = pkg_gz.read_bytes()
        decompressed = gzip.decompress(gz_data).decode("utf-8")
        assert decompressed == text, "Packages.gz content mismatch"

    pool = apt_dir / "pool" / "main" / "p" / "photo-selector-linux"
    assert list(pool.glob("photo-selector-linux_*_all.deb")), f"No package in pool: {pool}"


def test_deb822_sources_format(apt_repo):
    """Verify deb822 sources, setup.sh and the published URL (R-LINUX-PKG-04)."""
    template_dir = get_product_root() / "packaging" / "apt"
    for apt_dir in (template_dir, apt_repo):
        sources_file = apt_dir / "photo-selector.sources"
        assert sources_file.is_file(), f"Missing sources file at {sources_file}"
        text = sources_file.read_text(encoding="utf-8")

        assert "Types: deb" in text
        assert "URIs: https://alexpp90.github.io/apt/" in text
        assert "Suites: trixie" in text
        assert "Components: main" in text
        assert "Signed-By: /etc/apt/keyrings/photo-selector-archive-keyring.gpg" in text

        setup_file = apt_dir / "setup.sh"
        assert setup_file.is_file(), f"Missing setup script at {setup_file}"
        assert os.access(setup_file, os.X_OK), "setup.sh must be executable"
        setup = setup_file.read_text(encoding="utf-8")
        assert "https://alexpp90.github.io/apt/" in setup
        assert "alexanderpatz.github.io" not in setup


def test_release_checksum_integrity(apt_repo):
    """Verify cryptographic integrity of Release manifest hashes (R-LINUX-PKG-03)."""
    trixie_dir = apt_repo / "dists" / "trixie"
    release_path = trixie_dir / "Release"
    assert release_path.is_file()

    rel_text = release_path.read_text(encoding="utf-8")
    assert "SHA256:" in rel_text

    sha256_section = rel_text.split("SHA256:")[1].split("SHA512:")[0]
    entries = {}
    for line in sha256_section.strip().splitlines():
        parts = line.strip().split()
        if len(parts) == 3:
            sha, size, rel_path = parts
            entries[rel_path] = (sha.lower(), int(size))

    assert len(entries) >= 4, "Release must index at least 4 files (all + amd64 Packages & Packages.gz)"

    for rel_path, (expected_sha, expected_size) in entries.items():
        disk_path = trixie_dir / rel_path
        assert disk_path.is_file(), f"Index file listed in Release does not exist: {disk_path}"
        actual_size = disk_path.stat().st_size
        assert actual_size == expected_size, f"Size mismatch for {rel_path}"

        actual_sha = hashlib.sha256(disk_path.read_bytes()).hexdigest().lower()
        assert actual_sha == expected_sha, f"SHA256 mismatch for {rel_path}"


def test_no_signing_keys_in_templates():
    """Private signing material must never live in the product tree (R-LINUX-PKG-05)."""
    product = get_product_root()
    leaked = [p for p in product.rglob("*") if p.name in (".gnupg_repo",) or p.suffix in (".key",)]
    assert not leaked, f"Signing material in product tree: {leaked}"


# --- Release-readiness packaging checks (B-03, B-04, B-06, B-07, metainfo) ---


def _build_deb_members(tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    res = subprocess.run(
        [sys.executable, str(get_product_root() / "scripts" / "package_deb.py"),
         "--product-dir", str(get_product_root()), "--output-dir", str(out),
         "--output-filename", "t.deb"],
        capture_output=True, text=True,
    )
    assert res.returncode == 0, res.stderr
    members = {}
    with open(out / "t.deb", "rb") as f:
        f.read(8)
        while len(hdr := f.read(60)) == 60:
            name = hdr[:16].decode().strip().rstrip("/")
            size = int(hdr[48:58].decode().strip())
            members[name] = f.read(size)
            if size % 2:
                f.read(1)
    return members


def test_deb_installs_one_desktop_entry_and_one_icon_set(tmp_path):
    members = _build_deb_members(tmp_path)
    data = next(v for k, v in members.items() if k.startswith("data.tar"))
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as t:
        names = t.getnames()
    assert [n for n in names if n.endswith(".desktop")] == ["./usr/share/applications/app.alpatix.Blitzlens.desktop"]
    icons = [n for n in names if "/icons/" in n and n.endswith((".svg", ".png"))]
    assert len(icons) == 5 and all("app.alpatix.Blitzlens" in n for n in icons), icons
    assert any(n.endswith("symbolic/apps/app.alpatix.Blitzlens-symbolic.svg") for n in icons)


def test_deb_depends_and_recommends_come_from_debian_control(tmp_path):
    members = _build_deb_members(tmp_path)
    ctrl = next(v for k, v in members.items() if k.startswith("control.tar"))
    with tarfile.open(fileobj=io.BytesIO(ctrl), mode="r:*") as t:
        built = t.extractfile("./control").read().decode()
    field = lambda text, key: re.search(rf"^{key}: (.*)$", text, re.M).group(1)  # noqa: E731
    src = (get_product_root() / "debian" / "control").read_text()
    bin_stanza = src.strip().split("\n\n")[-1]
    for key in ("Depends", "Recommends"):
        block = re.search(rf"^{key}:\n((?: .*\n?)+)", bin_stanza, re.M).group(1)
        expected = [
            d.strip() for d in block.replace("\n", " ").split(",")
            if d.strip() and not d.strip().startswith("${")
        ]
        assert field(built, key).split(", ") == expected
    assert "python3 (>= 3.12)" in field(built, "Depends")


def test_desktop_mimetype_covers_every_supported_format():
    sys.path.insert(0, str(get_product_root() / "src"))
    from photo_selector_linux.core import formats

    assert set(formats.MIME_TYPE_BY_EXTENSION) == set(formats.SUPPORTED_EXTENSIONS)
    desktop = (get_product_root() / "data" / "app.alpatix.Blitzlens.desktop").read_text()
    line = next(row for row in desktop.splitlines() if row.startswith("MimeType="))
    assert set(filter(None, line.split("=", 1)[1].split(";"))) == set(formats.MIME_TYPES)


def test_metainfo_release_readiness():
    import xml.etree.ElementTree as ET

    root = ET.parse(get_product_root() / "data" / "app.alpatix.Blitzlens.metainfo.xml").getroot()
    urls = {u.get("type"): u.text for u in root.findall("url")}
    base = "https://github.com/alexpp90/photo-selector"
    assert urls == {"homepage": base, "bugtracker": base + "/issues", "vcs-browser": base}
    release = root.find("releases/release")
    changelog = (get_product_root() / "debian" / "changelog").read_text()
    assert release.get("version") == re.match(r"photo-selector-linux \(([\d.]+)-", changelog).group(1)
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", release.get("date"))
    assert root.find("developer").get("id") == "io.github.alexpp90"
    assert root.find("content_rating").get("type") == "oars-1.1"
    assert root.find("content_rating").findall("content_attribute")


def test_user_facing_text_has_no_internal_design_names():
    data = get_product_root() / "data"
    for f in (data / "app.alpatix.Blitzlens.metainfo.xml", data / "app.alpatix.Blitzlens.desktop"):
        text = f.read_text(encoding="utf-8")
        for word in ("Obsidian", "Darkroom", "Optical Cobalt", "Photo Selector"):
            assert word not in text, f"{word} in {f.name}"


def test_setup_sh_does_not_hide_failures():
    text = (get_product_root() / "packaging" / "apt" / "setup.sh").read_text()
    assert "set -euo pipefail" in text
    assert "|| true" not in text
    assert "2>/dev/null" not in text
    assert ">/dev/null 2>&1" not in text


def test_archive_key_fingerprint_documented():
    fpr = "F882 8BAA 9F9A BDC1 AE6E 53CC 4103 98EB 08C8 9ADC"
    for doc in (get_repo_root() / "docs/products/linux-desktop/INSTALL.md",
                get_product_root() / "packaging/apt/README.md"):
        assert fpr in doc.read_text(encoding="utf-8"), doc


@pytest.mark.skipif(not __import__("shutil").which("appstreamcli"), reason="appstreamcli not installed")
def test_appstream_validates():
    res = subprocess.run(["appstreamcli", "validate", "--no-net",
                          str(get_product_root() / "data" / "app.alpatix.Blitzlens.metainfo.xml")],
                         capture_output=True, text=True)
    assert res.returncode == 0, res.stdout + res.stderr


@pytest.mark.skipif(
    not __import__("shutil").which("desktop-file-validate"), reason="desktop-file-validate not installed"
)
def test_desktop_file_validates():
    desktop = get_product_root() / "data" / "app.alpatix.Blitzlens.desktop"
    res = subprocess.run(["desktop-file-validate", str(desktop)], capture_output=True, text=True)
    assert res.returncode == 0, res.stdout + res.stderr
