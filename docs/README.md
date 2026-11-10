# Documentation

Documentation is organised by **product**, using the same slugs as the code. Every file
states at the top which product it governs. If a file would need to say "except on Jackpix",
it belongs in [`shared/`](shared/) or it belongs in two files.

Start with the [Glossary](GLOSSARY.md) — the five products (four active native solutions
alongside the archived legacy Desktop) have accumulated several names each, and using the wrong
one is how a change lands in the wrong app.

## The repository in one screen

```
products/                   ALL product code. One directory per product, same shape in each.
  desktop/                    src/ tests/ benchmarks/ scripts/ pyproject.toml
  macos-desktop/              src/ tests/ Package.swift
  linux-desktop/              src/ tests/ debian/ scripts/ pyproject.toml
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
| Jackpix | `products/android/phototok/` | [`products/phototok/`](products/phototok/) |
| Frameduel (Android Desktop) | `products/android/android-desktop/` | [`products/android-desktop/`](products/android-desktop/) |
| Lensjet (macOS Desktop) | `products/macos-desktop/` | [`products/macos-desktop/`](products/macos-desktop/) |
| Blitzlens (Linux Desktop) | `products/linux-desktop/` | [`products/linux-desktop/`](products/linux-desktop/) |
| Desktop (legacy, archived) | `products/desktop/` | [`products/desktop/`](products/desktop/) |
| *(shared Android code)* | `products/android/core/`, `products/android/saf/` | [`shared/`](shared/) |

## Map of this tree

Every public document below is a link; `docs/README.md` is the index, and a test
(`scripts/tests/test_check_doc_links.py`) fails when a file under `docs/` is not linked from here.

| Where | Document | What it holds |
|---|---|---|
| `docs/` | [`GLOSSARY.md`](GLOSSARY.md) | Canonical product names, slugs, modules, packages |
| | [`index.html`](index.html) | The landing page of the GitHub Pages site that serves the legal pages |
| `products/phototok/` Jackpix | [`README.md`](products/phototok/README.md) | What it is, how to run it, where the code lives |
| | `REQUIREMENTS.md` | Authoritative behaviour spec, incl. architecture conventions  [private] |
| | [`ARCHITECTURE.md`](products/phototok/ARCHITECTURE.md) | Theme and structural notes |
| | [`DESIGN.md`](products/phototok/DESIGN.md) | Visual specification |
| | [`RELEASE_CHECKLIST.md`](products/phototok/RELEASE_CHECKLIST.md) | Play release checklist (permissions, store listing, legal) |
| | [`privacy-policy.html`](products/phototok/privacy-policy.html), [`impressum.html`](products/phototok/impressum.html) | Legal pages the app links to (GitHub Pages; favicons and the touch icon beside them belong to these pages) |
| | `LEGAL_COMPLIANCE.md`, `SECURITY_AUDIT.md`, `UX_INVESTIGATION.md` | Dated investigations  [private] |
| `products/android-desktop/` Frameduel | [`README.md`](products/android-desktop/README.md) | What it is, how to run it |
| | `REQUIREMENTS.md` | Authoritative behaviour spec  [private] |
| | [`ARCHITECTURE.md`](products/android-desktop/ARCHITECTURE.md) | Layers, adaptive layout, performance, theme implementation |
| | [`DESIGN.md`](products/android-desktop/DESIGN.md) | Visual specification (layout maths, tokens, component sheet) |
| | `DESIGN_PROMPT.md` | Design-tool brief derived from DESIGN.md  [private] |
| | [`RELEASE_CHECKLIST.md`](products/android-desktop/RELEASE_CHECKLIST.md) | Play release checklist (permissions, store listing, legal) |
| | [`privacy-policy.html`](products/android-desktop/privacy-policy.html), [`impressum.html`](products/android-desktop/impressum.html) | Legal pages the app links to (GitHub Pages) |
| `products/macos-desktop/` Lensjet | [`README.md`](products/macos-desktop/README.md) | What it is, how to build/run it, where the code lives |
| | `REQUIREMENTS.md` | Authoritative behaviour spec  [private] |
| | [`ARCHITECTURE.md`](products/macos-desktop/ARCHITECTURE.md) | Layering rules, concurrency models, and service interfaces |
| `products/linux-desktop/` Blitzlens | [`README.md`](products/linux-desktop/README.md) | What it is, how to test it, where the code lives |
| | [`INSTALL.md`](products/linux-desktop/INSTALL.md) | Installing from the APT repository |
| | `REQUIREMENTS.md` | Authoritative behaviour spec  [private] |
| `products/desktop/` legacy Desktop | [`README.md`](products/desktop/README.md) | What it is, how to run it, where the code lives |
| | `REQUIREMENTS.md` | Authoritative behaviour spec  [private] |
| | [`ARCHITECTURE.md`](products/desktop/ARCHITECTURE.md) | Package layout and layering rules |
| `shared/` | [`README.md`](shared/README.md) | What is shared, what is deliberately not, and the admission rule |
| | [`ANDROID_PLATFORM.md`](shared/ANDROID_PLATFORM.md) | Android baseline both Android products obey (EXIF, storage, stack) |
| | [`FEATURE_PARITY.md`](shared/FEATURE_PARITY.md) | Feature sync policy, permanent exclusions, cross-product mapping |
| | [`KEYBOARD.md`](shared/KEYBOARD.md) | The key map of Frameduel, Lensjet and Blitzlens today, and the proposed aligned map |
| | [`FILE_OPERATIONS.md`](shared/FILE_OPERATIONS.md) | The non-destructive Move / Copy / Trash / Undo contract and its messages |
| | [`SUPPORTED_FORMATS.md`](shared/SUPPORTED_FORMATS.md) | The supported-format contract read by every product's tests |
| | [`UI_TEXT.md`](shared/UI_TEXT.md) | Icon-first UI text rules and EN/DE text budgets |
| | [`BRANDING.md`](shared/BRANDING.md) | Names, icons and colours of the four apps |
| `build/` | [`CI_PARITY.md`](build/CI_PARITY.md) | What runs where and why; local mirror vs GitHub Actions |
| | [`BRANCH_PROTECTION.md`](build/BRANCH_PROTECTION.md) | Recommended protection for main, as gh api commands (not applied) |
| | [`RELEASING.md`](build/RELEASING.md) | Per-product versions, changelogs, tags and the release procedure |
| | [`ANDROID_BUILD_AND_RELEASE.md`](build/ANDROID_BUILD_AND_RELEASE.md) | Android build, CI and release requirements for both Android apps |
| | `ANDROID_SIGNING_PLAN.md`, `ANDROID_SIGNING_SETUP.md`, `ANDROID_SIGNING_RUNBOOK.md` | Per-app cloud/signing isolation: rationale, setup, runbook  [private] |

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
| Lensjet behaviour | `products/macos-desktop/REQUIREMENTS.md` |
| Lensjet architecture | `products/macos-desktop/ARCHITECTURE.md` |
| Blitzlens behaviour | `products/linux-desktop/REQUIREMENTS.md` |
| Frameduel behaviour | `products/android-desktop/REQUIREMENTS.md` |
| Jackpix behaviour | `products/phototok/REQUIREMENTS.md` |
| Frameduel visuals | `products/android-desktop/DESIGN.md` |
| Jackpix visuals | `products/phototok/DESIGN.md` |
| Something both Android products must obey | `shared/ANDROID_PLATFORM.md` |
| Whether a feature crosses products | `shared/FEATURE_PARITY.md` |
| What a key does in each desk app | `shared/KEYBOARD.md` |
| A CI gate | `build/CI_PARITY.md` **and** `scripts/run_tests.sh` **and** the workflow, in one commit |
| A release, a version number, a changelog | `build/RELEASING.md` and the product's `CHANGELOG.md` |
| Signing, secrets, store publishing | `build/ANDROID_SIGNING_SETUP.md` [private], the product's `RELEASE_CHECKLIST.md` |
| The directory layout itself | `../products/README.md` (the rules) and this file (the map) |

## Known gaps

- **No visual design document** for Lensjet, Blitzlens or the legacy Desktop. Their UI is
  specified inside their `REQUIREMENTS.md` (the legacy Desktop in § 3), which is a requirements
  file doing a design file's job; Jackpix and Frameduel each have a `DESIGN.md`. Only the
  active apps matter here: the legacy Desktop is archived.
- **Blitzlens has no `ARCHITECTURE.md`**; Lensjet, Jackpix, Frameduel and the legacy Desktop do.
