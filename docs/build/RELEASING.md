# Releasing

Every product is released **on its own**: its own [semantic version](https://semver.org/),
its own changelog and its own tag. A tag push runs only that product's workflow, which checks
the tag, runs the product's CI gates, builds, and publishes only that product.

| Product | Tag | Version lives in | Changelog | Workflow publishes to |
|---|---|---|---|---|
| Jackpix | `phototok-vX.Y.Z` | `products/android/phototok/build.gradle.kts` (`versionName` default) | [`products/android/phototok/CHANGELOG.md`](../../products/android/phototok/CHANGELOG.md) | GitHub release, Google Play internal track |
| Frameduel (Android Desktop) | `android-desktop-vX.Y.Z` | `products/android/android-desktop/build.gradle.kts` (`versionName` default) | [`products/android/android-desktop/CHANGELOG.md`](../../products/android/android-desktop/CHANGELOG.md) | GitHub release, Google Play internal track |
| Lensjet (macOS Desktop) | `macos-desktop-vX.Y.Z` | `products/macos-desktop/VERSION` | [`products/macos-desktop/CHANGELOG.md`](../../products/macos-desktop/CHANGELOG.md) | GitHub release, Homebrew cask |
| Blitzlens (Linux Desktop) | `linux-desktop-vX.Y.Z` | `pyproject.toml`, `photo_selector_linux/__init__.py`, `debian/changelog` | [`products/linux-desktop/CHANGELOG.md`](../../products/linux-desktop/CHANGELOG.md) | GitHub release (`.deb`), APT repository |
| Desktop (legacy) | `desktop-vX.Y.Z` | `pyproject.toml`, `photo_selector_toolbox/__init__.py` | [`products/desktop/CHANGELOG.md`](../../products/desktop/CHANGELOG.md) | GitHub release, Homebrew formula + cask |

The shared `vX.Y.Z` tags (last: `v0.4.0`) released all products at once; they are history.
A product with no tag of its own yet counts its changes from the last shared tag. The
`nightly` pre-release is unchanged: every push to `main` refreshes it.

Android `versionCode`s stay CI-computed (`android.yml`, from the run number), so Google Play
always sees an increasing code; `versionName` is the product version.

## The tool

[`scripts/release.py`](../../scripts/release.py) — standard library only, Python 3.9+.

```bash
python3 scripts/release.py status            # every product: last release, unreleased changes, due?, next version
python3 scripts/release.py status phototok   # one product, every merged PR listed
python3 scripts/release.py prepare phototok  # bump the version, close the changelog section
python3 scripts/release.py tag phototok --push   # on merged main: tag and push → the workflow publishes
python3 scripts/release.py check             # metadata consistent (a repo-hygiene gate)
```

**What counts.** The first-parent history of `main` since the product's last tag, one entry
per merged pull request, limited to files the product ships (tests, benchmarks and Markdown do
not ship; `products/android/core/` and the shared Gradle files count for both Android
products). PR titles follow [Conventional Commits](https://www.conventionalcommits.org/):

| Title | Counts as | Bump |
|---|---|---|
| `feat: …` | feature | minor |
| `fix: …`, `perf: …`, `revert: …`, anything unconventional | fix | patch |
| `feat!: …` or `BREAKING CHANGE:` in the description | breaking | major (minor before 1.0) |
| `docs`, `test`, `chore`, `ci`, `build`, `refactor`, `style` | maintenance | none on its own |
| `release(<product>): …` | the release itself | — |

**When a release is due.** At least one feature, at least three fixes, or a fix that has
waited 21 days. The thresholds are constants at the top of `release.py`.

## Procedure

1. **Status.** `git fetch --tags origin && python3 scripts/release.py status <product>`.
2. **Prepare** on a branch from `origin/main`: `python3 scripts/release.py prepare <product>`.
   It writes the next version into every version source and turns the changelog's
   `[Unreleased]` section into `[X.Y.Z] - <date>`. If that section was empty it drafts it
   from the PR titles: rewrite the draft for users. Linux Desktop also gets a new
   `debian/changelog` entry.
3. **Test, and fix every failure.** Run the product's gates with the CI mirror:

   | Product | Command |
   |---|---|
   | Jackpix, Frameduel | `./scripts/run_tests.sh --android --android-device` (device attached) |
   | Lensjet | `./scripts/run_tests.sh --macos` (on a Mac) |
   | Blitzlens | `./scripts/run_tests.sh --linux` |
   | Desktop (legacy) | `./scripts/run_tests.sh --python` |

   Android releases also go through the product's `RELEASE_CHECKLIST.md`.
4. **Release PR.** Commit `release(<product>): X.Y.Z`, open the PR, get CI green, merge.
5. **Tag.** On the merged commit (`git switch --detach origin/main`):
   `python3 scripts/release.py tag <product> --push`. The tool refuses unless:
   - the working tree is clean and HEAD is on `origin/main`;
   - every version source agrees, the version is newer than the last release and the
     changelog has its section;
   - `scripts/run_tests.sh` recorded a passing run of the product's gates on **exactly this
     tree** (the verification record in `.git/pst-verification/`). Gates a machine cannot run
     (macOS gates on Linux) are overridden explicitly with `--accept-untested "<reason>"`, and
     the reason is written into the tag message.
6. **Watch the workflow.** It runs `release.py verify-tag` first (the tag, the in-tree version
   and the changelog must agree, or nothing is published), then the product's test jobs, the
   build, and the publish steps. The GitHub release is named `<Product> X.Y.Z` and its body
   is the changelog section (`release.py notes <tag>`).

A failed publish is fixed forward with a new patch release; a pushed tag is never moved.

## Installing a specific product's release

GitHub's "latest release" is whichever product was released last. To find a product's
releases, filter the releases page by its tag prefix, for example
<https://github.com/alexpp90/photo-selector/releases?q=macos-desktop-v&expanded=true>.
