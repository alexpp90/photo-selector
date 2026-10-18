# Linux Desktop

Native GNOME photo selection and comparison culling application built in Python 3.12+,
PyGObject, GTK4, and Libadwaita for Debian 13 (Trixie) and GNOME 46+. Designed specifically for
lightning-fast SD-card photo ingestion, large-scale RAW/JPEG triage, native Libadwaita look-and-feel,
and seamless Debian APT package distribution.

| | |
|---|---|
| Code | `products/linux-desktop/` (planned for M3) |
| Modules | `photo_selector_linux` (domain, optical metadata, UI) |
| Tests | `products/linux-desktop/tests/` (unit and integration tests) |
| Target | Debian 13 (Trixie), GNOME 46+ workstations |
| Packaging | Debian `.deb` package, signed APT repository published to [`alexpp90/apt`](https://github.com/alexpp90/apt) by `linux.yml` |
| Entry point | `python3 -m photo_selector_linux` |

## Documents

- `REQUIREMENTS.md` (maintainer-private) — Authoritative behaviour specification
- `ARCHITECTURE.md` — Modular architecture and packaging (planned for M2)
- Cross-product feature policy: [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md)
- CI: [`../../build/CI_PARITY.md`](../../build/CI_PARITY.md)

## Run from source (Planned)

```bash
cd products/linux-desktop
python3 -m photo_selector_linux
```

## Test

```bash
# Repository CI mirror (recommended):
./scripts/run_tests.sh --linux

# Direct pytest execution (planned):
cd products/linux-desktop
pytest tests/
```

## Architecture & Layering

The product is partitioned into a clean modular architecture following the repository's uniform product layout:

1. **Optical & Metadata Extraction Layer**:
   - Parses EXIF/TIFF tags, camera profiles, and orientation without UI thread blocking.
   - Computes Laplacian focus variance metrics for sharpness assessment.
   - Manages RAW + JPEG sibling file linking.

2. **Culling Workspace & Transactional Operations**:
   - Manages photo queues, 1-Up, 2-Up, and 3-Up comparison viewports.
   - Transactional file routing (`Selection/`, Trash) with instant feedback and undo support.

3. **GNOME HIG / Libadwaita Presentation Layer**:
   - Modern Libadwaita window with `AdwHeaderBar`, dark styling conforming to GNOME HIG.
   - Keyboard shortcuts (`←`/`→`, `h`/`l`, `M`, `C`, `Delete`).

4. **Debian APT Packaging**:
   - In-repo `debian/` packaging definitions producing `.deb` binaries.
   - APT repository structure for Debian 13 (Trixie) distribution.

## Relationship to the Other Products

Linux Desktop is an independent Python / GTK4 / Libadwaita implementation. It shares photographic concepts (the EXIF contract, quality score semantics, what "Selection" means) with Desktop, macOS Desktop, Android Desktop, and PhotoTok, but shares no implementation code:

- **Distinct from legacy Python Desktop (`products/desktop/`)**: Modern Libadwaita / GTK4 architecture replacing Tkinter, custom dark theme, and PyInstaller with native GNOME HIG and Debian APT packaging.
- **Distinct from macOS Desktop (`products/macos-desktop/`)**: Pure GNOME desktop experience tailored to Debian 13 and FreeDesktop standards rather than macOS AppKit/SwiftUI.
- See [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).

## Not in This Product

- No Tkinter, PyInstaller, or Ollama server dependencies (legacy Desktop-only).
- No Apple Vision or Accelerate vImage frameworks (macOS-exclusive).
- No Android Storage Access Framework (SAF), Jetpack Compose, or Room database.
