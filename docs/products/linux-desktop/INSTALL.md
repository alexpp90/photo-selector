# Photo Selector Linux — Installation Guide for Debian 13 (Trixie)

This guide provides step-by-step instructions for installing and updating **Photo Selector Linux** on Debian 13 (Trixie) and compatible GNOME environments using our official APT repository or standalone packages.

---

## 1. Quick Install (Recommended: Modern deb822 Format)

Debian 13 (Trixie) uses the modern `deb822` repository source format by default. Follow these steps to configure the repository and install the application.

### Step 1: Install Prerequisites
Ensure `curl`, `ca-certificates`, and `gpg` are installed:
```bash
sudo apt update
sudo apt install -y curl ca-certificates gpg
```

### Step 2: Import the Repository GPG Signing Key
Place the official signing key into the system keyrings directory (`/etc/apt/keyrings/`):
```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg | sudo tee /etc/apt/keyrings/photo-selector-archive-keyring.gpg > /dev/null
sudo chmod 0644 /etc/apt/keyrings/photo-selector-archive-keyring.gpg
```

Verify the signing key before trusting it. The archive key is RSA 4096, "Photo Selector APT signing", with fingerprint:

```
F882 8BAA 9F9A BDC1 AE6E 53CC 4103 98EB 08C8 9ADC
```

Check what you downloaded (read-only, imports nothing):

```bash
curl -fsSL https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg | gpg --show-keys --with-fingerprint
```

### Step 3: Add the deb822 Source Configuration
Create `/etc/apt/sources.list.d/photo-selector.sources`:
```bash
sudo tee /etc/apt/sources.list.d/photo-selector.sources <<EOF
Types: deb
URIs: https://alexpp90.github.io/apt/
Suites: trixie
Components: main
Signed-By: /etc/apt/keyrings/photo-selector-archive-keyring.gpg
EOF
```

### Step 4: Update and Install
Refresh your package index and install Photo Selector:
```bash
sudo apt update
sudo apt install -y photo-selector-linux
```

### Step 5: Launch Photo Selector
You can launch the application directly from the GNOME application grid or via terminal:
```bash
photo-selector-linux
```

---

## 2. One-Line Setup Script

For rapid installation, run our automated configuration script:

```bash
curl -fsSL https://alexpp90.github.io/apt/setup.sh | sudo bash
sudo apt install -y photo-selector-linux
```

---

## 3. Legacy One-Line Source Fallback (`.list` format)

If your tooling or configuration management requires the traditional single-line APT format, configure `/etc/apt/sources.list.d/photo-selector.list`:

```bash
# Import key (same as Step 2 above)
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://alexpp90.github.io/apt/KEY.gpg | sudo gpg --dearmor -o /etc/apt/keyrings/photo-selector-archive-keyring.gpg

# Add one-line source entry
echo "deb [signed-by=/etc/apt/keyrings/photo-selector-archive-keyring.gpg] https://alexpp90.github.io/apt/ trixie main" | sudo tee /etc/apt/sources.list.d/photo-selector.list

# Update and install
sudo apt update
sudo apt install -y photo-selector-linux
```

---

## 4. Local / Development / Offline Installation

For developers working locally or users testing in an offline sandbox, you can configure APT to point directly at your local clone.

### Option A: Local File-Based APT Repository (`trusted=yes`)
From the root of your cloned `photo-selector` repository:

```bash
REPO_PATH="$(pwd)/apt"

sudo tee /etc/apt/sources.list.d/photo-selector-local.sources <<EOF
Types: deb
URIs: file://${REPO_PATH}/
Suites: trixie
Components: main
Trusted: yes
EOF

sudo apt update
sudo apt install -y photo-selector-linux
```

### Option B: Direct `.deb` Installation via APT
You can install the `.deb` package directly. `apt` will automatically resolve and install all Debian dependencies from official Debian repositories:

```bash
# build it first: products/linux-desktop/scripts/package_debian.sh
sudo apt install ./products/linux-desktop/dist/photo-selector-linux_0.1.0_all.deb
```
*(Note: The `./` prefix is mandatory so `apt` recognizes it as a local filesystem path).*

---

## 5. Verification and Diagnostic Commands

### Check Package Details
Verify that the package is recognized and inspect its metadata:
```bash
apt show photo-selector-linux
```
Expected output:
```
Package: photo-selector-linux
Version: 0.1.0
Section: graphics
Priority: optional
Architecture: all
Maintainer: Alexander Patz <alexander.patz@example.com>
Depends: python3 (>= 3.12), python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1, gir1.2-gexiv2-0.10, gir1.2-gdkpixbuf-2.0, libraw-bin, python3-numpy, python3-pil
Description: native GNOME photograph culling and selection suite
 ...
```

### Check Repository Policy and Pinning
```bash
apt-cache policy photo-selector-linux
```

---

## 6. Uninstallation and Cleanup

To remove the application while retaining your settings:
```bash
sudo apt remove photo-selector-linux
```

To purge the application and remove the APT repository configuration completely:
```bash
sudo apt purge --autoremove photo-selector-linux
sudo rm -f /etc/apt/sources.list.d/photo-selector.sources
sudo rm -f /etc/apt/sources.list.d/photo-selector.list
sudo rm -f /etc/apt/sources.list.d/photo-selector-local.sources
sudo rm -f /etc/apt/keyrings/photo-selector-archive-keyring.gpg
sudo apt update
```

---

## 7. Troubleshooting

### Issue 1: `GPG error: ... The following signatures couldn't be verified: NO_PUBKEY ...`
- **Cause**: The repository signing key was not imported into `/etc/apt/keyrings/` or the path in `Signed-By:` does not match.
- **Fix**: Re-run Step 2 to ensure the key is placed at `/etc/apt/keyrings/photo-selector-archive-keyring.gpg` with read permissions (`chmod 0644`). For local offline testing, add `Trusted: yes` to the `.sources` file.

### Issue 2: `E: The repository '...' does not have a Release file.`
- **Cause**: The URI or suite path in your source definition is incorrect or unreachable.
- **Fix**: Verify internet connectivity and confirm the URI matches `https://alexpp90.github.io/apt/` and the Suite is `trixie`.

### Issue 3: `E: Unable to locate package photo-selector-linux`
- **Cause**: `sudo apt update` was not run after creating the sources file, or the repository component `main` is missing.
- **Fix**: Run `sudo apt update` and check if the repository was successfully queried in the terminal output.

### Issue 4: Display Server Warnings (Wayland / X11)
- Photo Selector Linux runs natively on both Wayland and X11. In pure headless SSH sessions without X11 forwarding, launching GUI apps fails with `cannot open display`.
- To test headless commands: `photo-selector-linux --help` or run under a virtual frame buffer: `xvfb-run photo-selector-linux`.
