# Desktop

Python + Tkinter culling suite for macOS, Linux and Windows. **Archived**: the native apps
(Lensjet, Blitzlens) replace it, and it is kept buildable, installable and documented, with no new
features. It has the CLI, the statistics plots, the duplicate finder and local-VLM aesthetic scoring.

```
products/desktop/
  src/photo_selector_toolbox/   core/ exif/ tools/ gui/ cli.py
  tests/                        conftest.py, unit/{core,exif,tools,gui}/, visual/
  benchmarks/
  scripts/                      build.py, generate_notices.py,
                                update_formula.py
  pyproject.toml  poetry.lock  .flake8
```

Package layout and the layering rules: [`../../docs/products/desktop/ARCHITECTURE.md`](../../docs/products/desktop/ARCHITECTURE.md).
Behaviour specification: `../../docs/products/desktop/REQUIREMENTS.md` (maintainer-private).

## Run

Python 3.12 or newer, Tkinter and [Poetry](https://python-poetry.org/).

```bash
cd products/desktop
poetry install
poetry run photo-selector-gui          # GUI
poetry run photo-selector-toolbox ...  # CLI
```

## Supported formats

The shared contract in [`../../testdata/formats/manifest.json`](../../testdata/formats/manifest.json)
([human version](../../docs/shared/SUPPORTED_FORMATS.md)); the extension sets are defined once,
in `exif/reader.py`.

- **RAW:** ARW, CR2, CR3, NEF, NRW, RAF, RW2, ORF, PEF, DNG, SRW, plus 3FR/FFF, IIQ, RWL/RAW,
  SR2/SRF, MRW, X3F, ERF, MEF/MOS. Previews use the embedded JPEG when it is at least as large as
  the view, otherwise LibRaw (rawpy) decodes the RAW (phone DNGs embed ~672 px previews).
- **Bitmaps:** JPEG, HEIC/HEIF, PNG, TIFF, WebP.
- Extensions match case-insensitively; a RAW and a JPEG with the same stem are companions and
  move together (RAW into `Selection/RAW/`).
- Move and Copy to Selection never overwrite: when a file of the bundle (RAW, JPEG, `.xmp`,
  `-Edit`) is already in the destination, the whole bundle takes the next free `-2`, `-3`, … after
  the stem (`DSC_0001-2.JPG`), as in [`FILE_OPERATIONS.md`](../../docs/shared/FILE_OPERATIONS.md).

Known gaps, enforced by `tests/unit/test_format_conformance.py`:

- **HEIC/HEIF** is scanned and its EXIF read, but not displayed or scored: Pillow has no HEIF
  decoder and `pillow-heif` is not a dependency of this archived product.
- **CR3, RAF, RW2 EXIF needs ExifTool.** The release builds bundle it; a source install without
  `exiftool` on `PATH` shows these files without metadata (exifread cannot parse them).
- The camera make and model are not extracted.

## Test

```bash
./scripts/run_tests.sh                 # from the repository root — the CI mirror, use this
cd products/desktop && poetry run pytest   # subset only; not sufficient evidence
```

GUI tests need a display; on headless Linux use `xvfb-run`. Visual regression tests are marked
`visual` and are Linux-only — see [`../../docs/build/CI_PARITY.md`](../../docs/build/CI_PARITY.md).

## Things that deliberately live elsewhere

- **Homebrew formulae and casks live in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap)**
  (`brew tap alexpp90/tap`). CI updates them on every release with `scripts/update_formula.py`
  from here. `tap_migrations.json` at the repository root forwards users of the old in-repo
  tap to `alexpp90/tap`.
- **`assets/` is at the repository root** — the logo and screenshots are shared with the
  top-level README, and `scripts/build.py` bundles `logo.png`, `logo.ico` and `logo.icns` from there.
