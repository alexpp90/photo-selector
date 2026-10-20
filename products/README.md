# Products

One directory per product. Same shape in each, whatever the language.

```
products/
  desktop/              Desktop — Python + Tkinter
  macos-desktop/        Lensjet — Swift 6 + SwiftUI
  linux-desktop/        Blitzlens — GNOME HIG / Libadwaita
  android/              The Android platform group (one Gradle build)
    android-desktop/    Frameduel — :android-desktop, tablets and Samsung DeX
    phototok/           Jackpix — :phototok, phones
    core/               :core — the only code the two Android products share
```

## The uniform shape

Every product directory exposes the same four things:

| | |
|---|---|
| `src/` | Sources. Nothing else. |
| `tests/` | Tests. `tests/unit/` everywhere; Android additionally has `tests/instrumented/`. |
| build config | `pyproject.toml` for Desktop, `Package.swift` for macOS Desktop, `pyproject.toml` / `debian/` for Linux Desktop, `build.gradle.kts` for the Android modules. |
| `README.md` | What the product is, how to build it, how to test it, what it must not do. |

The Android modules do not use Gradle's `src/main | src/test | src/androidTest` convention.
Each one overrides `sourceSets` in its `build.gradle.kts` so its layout matches Desktop's.
That is a deliberate trade: the repository reads consistently, at the cost of a five-line
block per module that Android tooling does not expect. Keep the three copies in sync.

`res/`, `jniLibs/` and `AndroidManifest.xml` sit at the Android module root next to `src/`,
for the same reason.

## Why `android/` groups two products

Frameduel and Jackpix are independent products, but they are built by **one** Gradle
build — one `settings.gradle.kts`, one wrapper, one version catalog, one CI workflow — and
they share one library module. Splitting them into two sibling directories would mean either
two Gradle builds or a settings file pointing sideways into unrelated directories. Grouping
them under `products/android/` keeps the build root next to the things it builds.

That grouping is a *build* boundary, not a product boundary. The two apps still do not share
code beyond `core/`, do not share layouts, and are owned by separate agents. See
`../ai/ROUTING.md` (maintainer-private agent framework).

## What is not here

- **Documentation** — in [`../docs/`](../docs/README.md), organised by the same product slugs.
- **Agent configuration** — in `../ai/` (maintainer-private agent framework).
- **Distribution** — Homebrew formulae/casks in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap),
  the Debian repository in [`alexpp90/apt`](https://github.com/alexpp90/apt); CI updates both.
  `tap_migrations.json` at the root forwards users of the old in-repo tap.
- **`scripts/run_tests.sh`** — cross-product (it drives the Python, Swift SPM, Linux GTK, and Gradle gates),
  so it lives in the root `scripts/`. Desktop's own build scripts are in
  `desktop/scripts/`.
