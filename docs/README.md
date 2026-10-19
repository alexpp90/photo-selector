# Documentation

Documentation is organised by **product**, using the same slugs as the code. Every file
states at the top which product it governs. If a file would need to say "except on PhotoTok",
it belongs in [`shared/`](shared/) or it belongs in two files.

Start with the [Glossary](GLOSSARY.md) — the five products (four active native solutions
alongside the archived legacy Desktop) have accumulated several names each, and using the wrong
one is how a change lands in the wrong app.

## The repository in one screen

```
products/                   ALL product code. One directory per product, same shape in each.
  desktop/                    src/ tests/ benchmarks/ scripts/ pyproject.toml
  macos-desktop/              src/ tests/ Package.swift
  linux-desktop/              src/ tests/ debian/ scripts/ pyproject.toml (planned)
  android/                    Gradle build root for both Android products
    android-desktop/          src/ tests/ res/ AndroidManifest.xml build.gradle.kts
    phototok/                 src/ tests/ res/ AndroidManifest.xml build.gradle.kts
    core/                     src/ build.gradle.kts   (shared Android code: EXIF)
    saf/                      src/ build.gradle.kts   (shared Android code: SAF file operations)
docs/                       This tree — product documentation
ai/                         Agent framework: routing, agents, skills, memory
scripts/                    Cross-product tooling (run_tests.sh — the local CI mirror)
assets/                     Shared branding
.github/                    CI workflows
```

Code slug ↔ docs slug:

| Product | Code | Docs |
|---|---|---|
| Desktop | `products/desktop/` | [`products/desktop/`](products/desktop/) |
| macOS Desktop | `products/macos-desktop/` | [`products/macos-desktop/`](products/macos-desktop/) |
| Linux Desktop | `products/linux-desktop/` | [`products/linux-desktop/`](products/linux-desktop/) |
| Android Desktop | `products/android/android-desktop/` | [`products/android-desktop/`](products/android-desktop/) |
| PhotoTok | `products/android/phototok/` | [`products/phototok/`](products/phototok/) |
| *(shared Android code)* | `products/android/core/`, `products/android/saf/` | [`shared/`](shared/) |

## Map of this tree

```
docs/
  README.md            This file
  GLOSSARY.md          Canonical product names, slugs, modules, packages

  products/
    desktop/           Desktop — Python + Tkinter
      README.md          What it is, how to run it, where the code lives
      REQUIREMENTS.md    Authoritative behaviour spec  [private]
      ARCHITECTURE.md    Package layout and layering rules
    macos-desktop/     Native macOS Desktop — Swift 6 + SwiftUI
      README.md          What it is, how to build/run it, where the code lives
      REQUIREMENTS.md    Authoritative behaviour spec  [private]
      ARCHITECTURE.md    Layering rules, concurrency models, and service interfaces
    linux-desktop/     Native Linux Desktop — GNOME HIG / Libadwaita (planned)
      README.md          What it is, how to test it, where the code lives
      REQUIREMENTS.md    Authoritative behaviour spec  [private]
      ARCHITECTURE.md    Layering rules and GNOME HIG conventions (planned)
    android-desktop/   Android Desktop — :android-desktop, tablets and DeX
      README.md
      REQUIREMENTS.md    Authoritative behaviour spec  [private]
      ARCHITECTURE.md    Layers, adaptive layout, performance, theme implementation
      DESIGN.md          Visual specification (layout maths, tokens, component sheet)
      DESIGN_PROMPT.md   Design-tool brief derived from DESIGN.md  [private]
    phototok/          PhotoTok — :phototok, phones
      README.md
      REQUIREMENTS.md    Authoritative behaviour spec, incl. architecture conventions  [private]
      ARCHITECTURE.md    Theme and structural notes
      DESIGN.md          Visual specification

  shared/              Rules that must hold for MORE THAN ONE product
    README.md            What is shared, what is deliberately not, and the admission rule
    ANDROID_PLATFORM.md  Android baseline both Android products obey (EXIF, storage, stack)
    FEATURE_PARITY.md    Feature sync policy, permanent exclusions, desktop → Android mapping

  build/               How the products are built, signed and shipped
    CI_PARITY.md                 What runs where and why; local mirror vs GitHub Actions
    BRANCH_PROTECTION.md         Recommended protection for main, as gh api commands (not applied)
    RELEASING.md                 Per-product versions, changelogs, tags and the release procedure
    ANDROID_SIGNING_PLAN.md      Rationale for per-app cloud/signing isolation  [private]
    ANDROID_SIGNING_SETUP.md     Step-by-step setup instructions  [private]
    ANDROID_SIGNING_RUNBOOK.md   Operational runbook for the isolation migration  [private]
```

Files marked **[private]** — requirement specifications, design prompts, audits and the
signing runbooks — are kept in the maintainer's private overlay repository and are linked
into this tree only in the maintainer's checkout; a public clone does not contain them. The
same holds for the AI-agent framework (`ai/`). The public documents here (READMEs,
architecture, design specs, CI parity, shared rules) describe the products completely
enough to build, test and contribute.

## Which file do I edit?

| I changed… | Edit |
|---|---|
| Desktop behaviour | `products/desktop/REQUIREMENTS.md` |
| macOS Desktop behaviour | `products/macos-desktop/REQUIREMENTS.md` |
| macOS Desktop architecture | `products/macos-desktop/ARCHITECTURE.md` |
| Linux Desktop behaviour | `products/linux-desktop/REQUIREMENTS.md` |
| Linux Desktop architecture | `products/linux-desktop/ARCHITECTURE.md` (planned) |
| Android Desktop behaviour | `products/android-desktop/REQUIREMENTS.md` |
| PhotoTok behaviour | `products/phototok/REQUIREMENTS.md` |
| Android Desktop visuals | `products/android-desktop/DESIGN.md` |
| PhotoTok visuals | `products/phototok/DESIGN.md` |
| Something both Android products must obey | `shared/ANDROID_PLATFORM.md` |
| Whether a feature crosses products | `shared/FEATURE_PARITY.md` |
| A CI gate | `build/CI_PARITY.md` **and** `scripts/run_tests.sh` **and** the workflow, in one commit |
| A release, a version number, a changelog | `build/RELEASING.md` and the product's `CHANGELOG.md` |
| Signing, secrets, store publishing | `build/ANDROID_SIGNING_SETUP.md` [private], the product's `RELEASE_CHECKLIST.md` |
| The directory layout itself | `../products/README.md` (the rules) and this file (the map) |

## Known gaps

- **Desktop has no visual design document.** Its UI is specified inside
  `products/desktop/REQUIREMENTS.md` § 3 (layout constraints, display modes, dark theme), which
  is a requirements file doing a design file's job. The two Android products each have a proper
  `DESIGN.md`; Desktop should get one.
