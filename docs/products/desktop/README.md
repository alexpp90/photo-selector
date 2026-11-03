# Desktop

Python + Tkinter culling suite for macOS, Linux and Windows workstations. One of five independent
products in the repository, and the archived one: it provides the CLI, statistics plots, duplicate finder and the only
local-VLM aesthetic scoring.

> [!WARNING]
> **Deprecation & Archival Notice:**
> The multiplatform Python + Tkinter desktop application has been deprecated in favor of native platform applications (macOS Desktop in Swift 6 and Linux Desktop in GNOME/GTK4).
> The historical multiplatform desktop codebase prior to deprecation is fully preserved and can be restored at any time via:
> ```bash
> git checkout archive/legacy-desktop
> ```

| | |
|---|---|
| Code | `products/desktop/src/photo_selector_toolbox/` |
| Tests | `products/desktop/tests/` (mirrors the package layout) |
| Benchmarks | `products/desktop/benchmarks/` |
| Build | `scripts/`, `.github/workflows/desktop.yml`; Homebrew formula/cask in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap) |
| Entry points | `photo-selector-toolbox` (CLI), `photo-selector-gui` (GUI) |

## Documents

- `REQUIREMENTS.md` (maintainer-private) — authoritative behaviour specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — package layout and the rules that keep it layered
- Cross-product feature policy: [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md)
- CI: [`../../build/CI_PARITY.md`](../../build/CI_PARITY.md)

## Run from source

```bash
poetry install
poetry run photo-selector-gui          # GUI
poetry run photo-selector-toolbox ...  # CLI
```

## Test

```bash
./scripts/run_tests.sh                 # the CI mirror — use this
pytest                                 # subset only; not sufficient evidence
```

GUI tests need a display; on headless Linux use `xvfb-run`. Visual regression tests are marked
`visual` and are Linux-only — see [`../../build/CI_PARITY.md`](../../build/CI_PARITY.md).

## Not in this product

Local AI aesthetic evaluation via Ollama is Desktop-only, but the reverse exclusions matter
too: the gesture feed, SAF storage and adaptive window size classes belong to the Android
products and have no Desktop equivalent. See
[`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).
