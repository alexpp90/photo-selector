# Changelog — Desktop (legacy)

Notable changes to Desktop (legacy), released under the tags `desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- Move and Copy to Selection no longer overwrite a file of the same name: a collision gives the whole bundle (RAW, JPEG, `.xmp`, `-Edit`) the next free `-2`, `-3`, … suffix, and a name that is taken after the check is reported instead of replaced.

### Changed
- Python 3.12 or newer is required (was 3.10): the code already used `int.is_integer()`, which only exists from 3.12, and CI only ever tested 3.12. Source installs on 3.10 or 3.11 must move to a newer Python; the prebuilt downloads are unaffected.

### Security
- Pillow 13.0.0 (was 12.3.0, which carries about sixty published image-decoder advisories fixed in 13.0.0). The nightly and release binaries bundle it.
