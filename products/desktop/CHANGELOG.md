# Changelog — Desktop (legacy)

Notable changes to Desktop (legacy), released under the tags `desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- Move and Copy to Selection no longer overwrite a file of the same name: a collision gives the whole bundle (RAW, JPEG, `.xmp`, `-Edit`) the next free `-2`, `-3`, … suffix, and a name that is taken after the check is reported instead of replaced.
