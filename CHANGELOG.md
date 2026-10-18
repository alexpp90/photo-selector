# Changelog

**Since 2026-10-17 every product is released on its own and keeps its own changelog:**
[PhotoTok](products/android/phototok/CHANGELOG.md) ·
[Android Desktop](products/android/android-desktop/CHANGELOG.md) ·
[macOS Desktop](products/macos-desktop/CHANGELOG.md) ·
[Linux Desktop](products/linux-desktop/CHANGELOG.md) ·
[Desktop (legacy)](products/desktop/CHANGELOG.md) — see [`docs/build/RELEASING.md`](docs/build/RELEASING.md).
This file is the history of the shared `vX.Y.Z` releases. Version 0.5.0 below was prepared but
never tagged; its changes ship with each product's first per-product release.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Homebrew:** formulae and casks now live only in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap) (`brew tap alexpp90/tap`). Users of the old in-repo tap are forwarded automatically (`tap_migrations.json`).
- **Debian/APT:** the Linux Desktop repository moved to [`alexpp90/apt`](https://github.com/alexpp90/apt), served at `https://alexpp90.github.io/apt/` and signed by CI on every release. Re-run `curl -fsSL https://alexpp90.github.io/apt/setup.sh | sudo bash` to switch.
- **Licensing:** the code stays MIT; added [`TRADEMARKS.md`](TRADEMARKS.md) for the names and logos and a [Contributor License Agreement](CLA.md) for outside contributions.
- **Repository:** the maintainer's AI-agent framework, requirement specifications and design prompts are no longer part of the public repository; `AGENTS.md` keeps the public instructions for AI coding agents.

## [0.5.0] - 2026-10-10

### Added
- **Native macOS Desktop (`products/macos-desktop/`):**
  - High-performance native Swift 6 and SwiftUI photo culling suite for macOS 14+ and 15+.
  - Sub-millisecond focus quality scoring via Apple Accelerate vImage + vDSP with sensor noise-floor compensation.
  - Native Apple Vision aesthetic scoring using Neural Engine hardware (`VNCalculateImageAestheticsScoresRequest`).
  - Maximize viewport culling layouts (1-Up, 2-Up, and Focus 3-Up sliding triplet).
  - Instant zero-latency keyboard router and transactional undo-safe culling engine.
- **Native Linux Desktop (`products/linux-desktop/`):**
  - GNOME Libadwaita photograph culling and selection suite for Debian 13 (Trixie).
  - Vectorized Laplacian focus scoring with MAD noise floor compensation.
  - Transactional file operations with copy-undo safety.
  - In-repo APT repository and Debian packaging.

### Deprecated
- **Legacy Desktop (`products/desktop/`):**
  - The multiplatform Python/Tkinter desktop application is deprecated and archived.
  - Historical source code preserved at tag/branch `archive/legacy-desktop` for restoration.

### Changed & Fixed
- **Agent framework: modernised registration, split the lifecycle into skills, and made the rules enforceable.**
  - *Fixed stale instructions.* Seven agents pointed at a repository-root `REQUIREMENTS.md` that has not existed since the `products/` reorganisation; `android-shared-build-agent` claimed `.github/workflows/build-android.yml` (actual: `android.yml`); `desktop-build-agent`'s scope listed build scripts at `scripts/` (actual: `products/desktop/scripts/`). `ai/README.md` also claimed `ai/memory/` was untracked by git, which it is not.
  - *Naming.* All 14 agents and all skills renamed to lowercase-hyphen (`desktop_gui_agent` → `desktop-gui-agent`, `self_improvement` → split, `refactoring_guide` → `refactoring-guide`, `playbook_TEMPLATE` → `playbook-template`). Skill `name:` fields did not match their directories, which prevented reliable registration.
  - *Capability.* Agent frontmatter now declares `tools:` and `model:`. `shared-ux-agent` and `shared-photo-researcher-agent`, whose prompts state they do not modify source files, are now read-only in fact; validation fails if that ever diverges again.
  - *Lifecycle split into skills.* The `self_improvement` monolith — injected in full into every session — became `task-lifecycle` (pre-work) and `retrospective` (post-work), plus `verify-build`, `sync-requirements`, `record-lesson`, `create-playbook`, `sync-framework` and `release-compliance`. Each loads when its moment arrives. Procedures duplicated across `AGENTS.md`, `ai/ROUTING.md`, `ai/README.md` and individual agent bodies were collapsed to a single home apiece.
  - *Commands.* `/route`, `/verify`, `/retro`, `/sync-framework`, canonical in `ai/commands/`, exposed to Claude Code via `.claude/commands` and to Antigravity via `.agents/workflows`.
  - *Enforcement.* `.claude/settings.json` dropped the per-prompt reminder echo and gained blocking `PreToolUse` and `Stop` hooks; the same guards are registered for Antigravity in `.agents/hooks.json`. `ai/hooks/` scripts detect the calling host from the payload shape and answer in its dialect. They block scratch files, writes through the mirrored `.claude/`/`.gemini/`/`.agents/` trees, hand-edits of the generated `.gemini/settings.json`, code at the repository root, committing staged scratch artifacts, `--no-verify`, pushing a chain of speculative `fix(ci)` commits, and ending a session that changed product source with no sign of a retrospective. `PST_SKIP_HOOKS=1` bypasses them.
  - *Framework as code.* `.gemini/settings.json` is now generated by `gen_gemini_settings.py`; `validate_framework.py` checks naming, registration, symlinks, roster and — most usefully — that every repository path an instruction names actually exists. Both run as gates in `scripts/run_tests.sh`.
  - *Antigravity.* `.agents/` previously exposed only skills; it now also carries `rules/`, `workflows/` and `hooks.json`, the locations Antigravity actually reads.
- **PhotoTok: removed the dedicated Google Drive integration in favor of SAF (Storage Access Framework):**
  - Cloud folders (including Google Drive) are now selected through the standard system folder picker, whose document providers expose cloud storage directly — verified working on-device. One generic code path covers local, SD-card, and cloud sources.
  - Deleted the entire Drive stack: Google Sign-In/auth, Drive REST client, Drive image source and Coil fetcher, the WebView-hosted Google Picker, picked-selection persistence (`gdrive-picked://` URIs), and the `SourceUris`/`ImageItem.isRemote` scheme classification.
  - Dropped the `play-services-auth` / `kotlinx-coroutines-play-services` dependencies, the Picker `BuildConfig` credentials (`PHOTOTOK_PICKER_API_KEY`, `PHOTOTOK_GCP_PROJECT_NUMBER`), the corresponding CI verification step, and the `INTERNET` permission — the app performs no network I/O of its own.
  - Removed the Drive-only "Trash Confirmation" dialog and setting; all deletions use the existing Direct Delete Confirmation flow with the revert window.
- **PhotoTok Google Drive: switched to the non-restricted `drive.file` scope (Play-Store readiness):**
  - The app no longer requests the restricted full `drive` scope, removing the need for Google restricted-scope verification and the annual CASA security assessment when publishing to Google Play.
  - The in-app Drive folder browser (`DriveFolderPickerDialog`/`DriveFolderPickerViewModel`) was removed — under `drive.file` the app cannot browse the user's Drive. Users now grant access by multi-selecting photos in the WebView-hosted **Google Picker** (`DrivePhotoPickerDialog` + `DrivePickerViewModel`); folder selection is disabled because a folder grant does not extend to its children under `drive.file`.
  - Picked photo sets are persisted as named selections (`DrivePickedStore`/`DrivePickedCodec`, capped at 10) and addressed as `gdrive-picked://<key>` source URIs, so they work with recents and last-folder restore. Re-opening a selection intersects the stored IDs with a flat `listAccessibleImages()` listing.
  - Collection copy/move on picked sources defaults to an app-created `PhotoTok` folder in the Drive root; Drive moves now resolve the file's real parents via the API (previously the destination folder was wrongly assumed to be the old parent) and fall back to copy + trash when a move is rejected.
  - Picker credentials come from `BuildConfig` (`PHOTOTOK_PICKER_API_KEY`, `PHOTOTOK_GCP_PROJECT_NUMBER` env vars); without them, Drive photo picking is disabled with an explanatory error instead of a crash.
- **PhotoTok Play-Store compliance groundwork:**
  - Added bilingual privacy policy and Impressum pages (`docs/phototok/*.html`, personal-data placeholders to fill in) plus a step-by-step release checklist with Data Safety form answers (`docs/phototok/PLAY_RELEASE_CHECKLIST.md`).
  - Settings screen now links "Privacy Policy" and "Legal Notice (Impressum)" (URLs centralized in `com.phototok.domain.LegalLinks`), satisfying Play's in-app privacy-policy requirement and § 5 DDG.
- **PhotoTok Architecture Refactor:**
  - Introduced an `ImageSource` abstraction (local SAF + Google Drive) with a single resolver; all URI-scheme dispatch now lives in the data layer and `ImageRepository` no longer takes `Context` parameters. Cross-source copy/move is rejected explicitly.
  - Split `PhoneModeViewModel`: the read-only selection viewer moved to `SelectionViewerViewModel` (folder traversal moved into the local image source), and the Drive folder picker to `DriveFolderPickerViewModel`; Compose no longer receives `GoogleDriveClient`/`GoogleDriveAuth` objects (sign-in state is part of the UI state).
  - Replaced the fragile 12/13-flow positional `combine` of settings with a single typed `PhoneSettings` flow mapped from one DataStore snapshot (both feed and settings ViewModels).
  - Copy/move actions now track per-file results: partial failures are reported ("Moved 1 of 2 …, 1 failed") and only successfully moved files leave the feed.
  - Image dimensions are applied to the UI state in batches instead of per image, bounding recompositions in folders with thousands of photos.
  - Per-folder last positions are now keyed by the full URI (collision-free; legacy hash keys read as fallback and cleaned up) and pruned when a folder falls out of the recents list.
  - Google Drive client: all HTTP verbs (including downloads and uploads) share one authorized-request helper with a single token-refresh retry on 401; recursive folder listings run concurrently (bounded); corrected the upload documentation (multipart, not resumable).
- **PhotoTok Reliability Refactor:**
  - Fixed a bug where sibling files (e.g. `.ARW` next to `.JPG`) of a pending deletion were not removed from disk when the app was closed during the undo window; pending deletions now finalize on an application-scoped coroutine so they survive screen exit.
  - Fixed a coroutine leak where re-selecting a source folder started a new image-discovery collector without cancelling the previous one.
  - Failed file copies no longer leave zero-byte placeholder files in the destination folder.
  - Google Drive: user folder names are now escaped in Drive queries (names containing `'` no longer break folder lookup); HTTP 401 responses trigger a one-time token refresh and retry; the Drive download cache is capped at 512 MB with LRU eviction.
  - Replaced raw setting strings ("delete"/"copy"/"move", "all"/"raw"/"jpg") with typed enums (`SwipeAction`, `CollectionAction`, `FileTypeFilter`); DataStore wire format is unchanged.
  - Extracted pending-delete list transitions (`PendingDeleteLogic`), feed filtering, and portrait-split computation into the pure `domain` layer with unit-test coverage; deduplicated RAW/JPEG extension sets (`PhotoExtensions`).
- **CI:** Unit-test reports are now uploaded for both Android modules (previously `:phototok` reports were dropped); added an advisory Android Lint job; the Android build gate now fails when no test run is found; `versionName` can be overridden from CI via environment variables.
- **Repo Hygiene:** Removed committed scratch/debug files and report dumps; moved stray root benchmarks into `benchmarks/`; added `CLAUDE.md`/`GEMINI.md` agent stubs and a REQUIREMENTS.md drift-check workflow.

## [0.4.0] - 2026-06-27

### Added
- **Visual Regression Tests:** Added screenshot capture and baseline pixel-matching regression test suite for main desktop GUI panels.
- **Keyboard Shortcut Discoverability:** Appended keyboard shortcut hints directly onto action buttons (e.g., Copy, Move, Delete) in the Tkinter GUI and fullscreen viewer.

### Changed & Fixed
- **Google Play & Firebase deployment:** Updated Google Play action and service account format, ensuring robust automatic publishing of AABs for both the main app and PhotoTok.
- **Android Cloud Project Isolation:** Isolated Google Cloud / Firebase projects, signing keys, and secrets between the main app (`:app`) and PhotoTok (`:phototok`).
- **Keyboard Accessibility:** Configured explicit focus states mapping for `TCheckbutton` in the Tkinter clam theme to restore keyboard navigation visibility.
- **Robust CI Pipelines:** Optimized wait-on-check preflight gating to prevent CI blockages and added manual build-android triggers.

## [0.3.0] - 2026-06-27

### Added
- **Android Platform & UI Improvements:**
  - Added sample mockup card slideshow layout to the PhotoTok landing screen using custom landscape, portrait, and architectural photography assets for enhanced design aesthetics.
  - Configured explicit Web Client ID in Google Drive authentication to support multiple package names/SHA-1 signatures.
  - Integrated launcher foreground logos into navigation components, top bar, and hero cards.
  - Added full-screen image loading indicators and orientation-responsive safety padding for image viewing.
- **Distribution & CI/CD Pipelines:**
  - Integrated Google Play Store publishing to automate uploading AABs of both apps to the `internal` testing track.
  - Documented over-the-air installation procedures (via Obtainium direct from GitHub Releases, or via Firebase App Tester app) in the project documentation.

### Changed & Fixed
- Hidden the bottom navigation bar when on the landing/sources screen to prevent empty navigation actions.

## [0.2.0] - 2026-06-21

### Added
- **Android Platform & Google Drive Integration:**
  - Full Google Drive file browsing, silent sign-in sync, and folder picking on both PhotoTok (`phototok`) and Tablet/Desktop (`app`) clients.
  - Custom `gdrive://` URI schema implementation in Coil (`DriveCoilFetcher`) to handle background downloading and caching.
  - Support for discovering RAW files (e.g., `.CR2`, `.NEF`) on Google Drive by fallback mapping of `application/octet-stream` MIME types with supported image extensions.
  - Detailed error stream diagnostics logging for Google Drive REST API calls (debugging 403 Forbidden and other network issues).
  - Native 16KB page size alignment compatibility on Android 16+ devices, using NDK 28's precompiled `libc++_shared.so` binaries and restricting default ABI filters to 64-bit architectures (`arm64-v8a` and `x86_64`).
- **Desktop Features & Distribution:**
  - Three configurable grouping levels (Time & Filename, Time + Fast Similarity, Detailed Similarity) with a persistent GUI selector.
  - Calibrated Ollama VLM prompt structure and aesthetic reason tag extraction display.
  - Homebrew Formula support for stable and nightly CLI-only builds (`photo-selector-toolbox` and `photo-selector-toolbox@nightly`) for macOS and Linux.
  - Added Linux installation shell script `scripts/install-linux.sh`.

### Changed & Optimized
- Optimized visual similarity grouping by restricting dHash checks to temporal burst candidates (reducing image loading by up to 90% in large folders).
- Optimized raw image preview loading on desktop using embedded JPEG camera thumbnail extraction.
- Automatic filtering to exclude hidden files and macOS Apple Double metadata files (starting with `.`) during both local and Google Drive image discovery.
- Automated version and checksum updates in GitHub Actions build workflows for Homebrew tap files.

---

## [0.1.0] - 2026-06-07

### Added
- **Photo Selector Tool**:
  - Center-cropped 8x8 block variance grid sharpness analysis.
  - Noise analysis estimation via Median Absolute Deviation (MAD) of the Laplacian filter.
  - Blown highlights ($\ge 254$ intensity) and crushed shadows ($\le 2$ intensity) percentage evaluation.
  - Multi-image layout comparison showing standard and focus mode triplets.
  - Fullscreen viewer with dynamic metadata overlay, delete with confirm, and move-to-selection functionality.
  - Dynamic file type filtering for candidates (RAW vs. JPEG vs. All Supported).
  - Parallel background analysis scanner with logging progress tab.
- **Duplicate Finder**:
  - Content-based SHA256 duplicate detection.
  - Safe trashing system (using `send2trash` with fallback warning to permanent deletion).
- **Metadata Analyzer**:
  - EXIF extraction using unified `ExifData` schema (Shutter Speed, Aperture, ISO, Focal Length, Lens Model).
  - EXIF statistics visualization and plotting (excluding values $\le 0.0$ mm for focal length).
- **Path Resolution Utility**:
  - Network share path resolver (`smb://` URLs) to local mount points for macOS and Linux.
- **Packaging and Distribution**:
  - Dual-track Homebrew Tap cask support (`photo-selector-toolbox` and `photo-selector-toolbox@nightly`).
  - Standalone executable bundling with bundled `exiftool` binary.
  - Multi-platform CI build matrix (Linux x64, macOS Apple Silicon, Windows x64).
  - Multi-platform CI build matrix (Linux x64, macOS Apple Silicon, Windows x64).
