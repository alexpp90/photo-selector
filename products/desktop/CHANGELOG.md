# Changelog — Desktop (legacy)

Notable changes to Desktop (legacy), released under the tags `desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

## [0.5.0] - 2026-11-13

### Added
- Cross-product supported-format contract and conformance corpus: the extension sets are defined once in `exif/reader.py` (RAW incl. 3FR, IIQ, MRW, X3F, ERF, MEF; JPEG, HEIC/HEIF, PNG, TIFF, WebP).
- Apple Vision aesthetic scoring (macOS 15+) through the PyObjC bridge, picked automatically when installed, with the chosen engine and the reason shown in the scoring dialog.
- Sharpness scores are normalised and compensate for high-ISO sensor noise.
- Filter preferences persist between runs; action buttons carry tooltips with their keyboard shortcuts.
- Photographs are turned the way the camera was held (EXIF orientation).

### Changed
- Python 3.12 or newer is required (was 3.10): the code already used `int.is_integer()`, which only exists from 3.12, and CI only ever tested 3.12. Source installs on 3.10 or 3.11 must move to a newer Python; the prebuilt downloads are unaffected.
- The product is archived (see the root `CHANGELOG.md`, 0.5.0): the native Lensjet (macOS) and Blitzlens (Linux) apps replace it for new work. Its source stays buildable and installable.
- Releases are tagged `desktop-vX.Y.Z`; Homebrew formulae and casks live in `alexpp90/homebrew-tap`.
- Lower memory use on large folders, faster scan routines and smoother scrolling while a scan runs.
- The release build no longer calls the dead icon generator; `build.py` takes `--help` and `--dry-run`.

### Fixed
- Move and Copy to Selection no longer overwrite a file of the same name: a collision gives the whole bundle (RAW, JPEG, `.xmp`, `-Edit`) the next free `-2`, `-3`, … suffix, and a name that is taken after the check is reported instead of replaced.

### Security
- Pillow 13.0.0 (was 12.3.0, which carries about sixty published image-decoder advisories fixed in 13.0.0). The nightly and release binaries bundle it.
