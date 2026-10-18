# Agent Instructions

Entry point for every AI coding agent (Claude Code, Antigravity, Gemini CLI, Jules, Copilot,
Cursor, …) working in this repository.

> **Maintainer overlay.** If the directory `private/` exists in this checkout, read
> [`private/AGENTS.md`](private/AGENTS.md) **now, before anything else** — it is the
> authoritative instruction set (agent framework, task lifecycle, requirements workflow) and
> overrides this file wherever they differ. It is a separate private repository and is not
> part of the public project. Without it, the rules below are complete.

## The five products

Identifying which product a task targets is the first action of every task.

| Product | Code | Tech | Target |
|---|---|---|---|
| **PhotoTok** | `products/android/phototok/` (`:phototok`) | Kotlin, Compose, DataStore | Phones, gesture-first |
| **Android Desktop** | `products/android/android-desktop/` (`:android-desktop`) | Kotlin, Compose, Room, OpenCV, Vico | Tablets ≥ 10", Samsung DeX, Chromebooks |
| **macOS Desktop** | `products/macos-desktop/` (`PhotoSelectorKit`, `PhotoSelectorApp`) | Swift 6, SwiftUI, Vision, Accelerate | macOS 14+ / 15+ Apple Silicon & Intel |
| **Linux Desktop** | `products/linux-desktop/` (`photo_selector_linux`) | Python, PyGObject, GTK4, Libadwaita | Debian 13 (Trixie), GNOME 46+ |
| **Desktop (legacy, archived)** | `products/desktop/src/photo_selector_toolbox/` | Python + Tkinter | macOS, Linux, Windows |

They share photographic *concepts* — the EXIF contract, score semantics, what "Selection"
means — and nothing else. The only shared implementation is `products/android/core/`
(`:core`), the EXIF model and readers used by both Android products. Canonical names:
[`docs/GLOSSARY.md`](docs/GLOSSARY.md).

## Rules

1. **Identify the product first, and stay inside it.** Leaking code between products is a
   defect, not reuse. `products/android/core/` affects both Android products.
2. **Write and run tests for every feature, fix or logic change**, and run them before you
   call the task done.
3. **Verify against the CI mirror, not a subset.** `./scripts/run_tests.sh` (`--all` for every product)
   reproduces every gate CI enforces except the instrumented device suite, which is run on
   demand with `--android-device` before a release; a missing device never blocks. Gates reported `⊘ SKIPPED` are
   accepted risks — name them in your summary. Changing a gate in `.github/workflows/`
   obliges you to mirror it in `scripts/run_tests.sh` and `docs/build/CI_PARITY.md` in the
   same commit. Never push a speculative fix and let CI adjudicate it.
4. **No scratch files.** Never commit temporary artifacts (`scratch*.py`, `pr_desc.txt`,
   lint/analysis dumps, debug scripts) or code at the repository root.
5. **Keep user-facing docs true.** When observable behaviour changes, update the product's
   `README.md` (and the root `README.md` product sections, canonical order: PhotoTok, Android
   Desktop, macOS Desktop, Linux Desktop, legacy Desktop) in the same commit.
6. **Every PR states where and how far it was tested.** After `./scripts/run_tests.sh`, run
   `python3 scripts/verification_record.py pr` (or paste `… render`) so the PR description
   shows the machine, toolchains and gate results, and fill in the template's *Who did the
   work*, *Not tested locally* and *Manual testing* parts. An agent with limited tooling
   (cloud, sandbox, no device) names itself and lists what it could not run — a gap stated is
   fine, a gap hidden is not. See CONTRIBUTING.md, "Verification record".
7. **Never commit maintainer-private material.** `private/`, `ai/`, `.claude/`, `.agents/`,
   `.gemini/`, requirement specifications and prompt files are ignored on purpose;
   `scripts/check_private_paths.py` fails CI if any of them is tracked.

## Where things live

```
products/
  android/            The Android platform group — one Gradle build for both Android products
    phototok/         PhotoTok product              — src/ tests/ res/ AndroidManifest.xml
    android-desktop/  Android Desktop product       — src/ tests/ res/ AndroidManifest.xml
    core/             The only code the two Android products share — src/
  macos-desktop/      Native macOS Desktop (Swift)  — src/ tests/ Package.swift
  linux-desktop/      Native Linux Desktop (GNOME)  — src/ tests/ debian/ scripts/ pyproject.toml
  desktop/            Legacy Desktop (Python)       — src/ tests/ benchmarks/ scripts/ pyproject.toml
docs/                 Product documentation, organised per product  -> docs/README.md
scripts/              Cross-product tooling — run_tests.sh (the local CI mirror)
testdata/formats/     Supported-format contract + corpus manifest read by every product's tests
assets/               Shared branding used by the README and the desktop icons
```

Distribution lives in separate repositories: the Homebrew tap
[`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap) and the APT repository
[`alexpp90/apt`](https://github.com/alexpp90/apt). CI updates both on release.

The Android modules override Gradle's default source-set convention so that they match the
other products: sources in `src/`, unit tests in `tests/unit/`, instrumented tests in
`tests/instrumented/`. The overriding `sourceSets` block is in each module's
`build.gradle.kts` — keep the copies in sync.
