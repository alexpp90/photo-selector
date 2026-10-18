# Photo Selector — APT repository

Debian 13 (Trixie) package repository for **Photo Selector Linux**, the native GNOME app of
[Photo Selector Toolbox](https://github.com/alexpp90/photo-selector). Served by GitHub Pages
at **https://alexpp90.github.io/apt/**.

## Install

```bash
curl -fsSL https://alexpp90.github.io/apt/setup.sh | sudo bash
sudo apt install photo-selector-linux
```

Or by hand (deb822):

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg \
  | sudo tee /etc/apt/keyrings/photo-selector-archive-keyring.gpg > /dev/null
sudo curl -fsSL https://alexpp90.github.io/apt/photo-selector.sources \
  -o /etc/apt/sources.list.d/photo-selector.sources
sudo apt update && sudo apt install photo-selector-linux
```

Verify the signing key before trusting it. The archive key is RSA 4096, "Photo Selector APT signing", with fingerprint:

```
F882 8BAA 9F9A BDC1 AE6E 53CC 4103 98EB 08C8 9ADC
```

Check what you downloaded (read-only, imports nothing):

```bash
curl -fsSL https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg | gpg --show-keys --with-fingerprint
```

## Maintenance

This repository is **generated**. Do not edit it by hand: the `publish-apt` job of
[`linux.yml`](https://github.com/alexpp90/photo-selector/blob/main/.github/workflows/linux.yml)
builds the `.deb`, regenerates `dists/` and signs the metadata on every `v*` release. The
templates for this README, `setup.sh` and `photo-selector.sources` live in
`products/linux-desktop/packaging/apt/` of the main repository.
