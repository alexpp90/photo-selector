# Frameduel (`:android-desktop`) — Release & Compliance Checklist

This checklist is the **single source of truth** for Google Play release readiness, DSGVO/GDPR privacy compliance, German DDG/Impressum compliance, and EU Digital Services Act (DSA) status for **Frameduel** (`com.photoselectortoolbox`).

---

## 0. Store listing

- [ ] **Title:** "Frameduel – Photo Culling Desk" (English and German). Short and full descriptions name the app Frameduel; no earlier name appears in the listing.
- [ ] **Icon and screenshots:** the lavender Frameduel icon (adaptive, with themed monochrome layer); no screenshot shows the previous name or palette.

---

## 1. Architecture & Privacy Invariants (Repo-Side)

- [x] **100% Local On-Device SAF Architecture:** Frameduel accesses user photos exclusively via Android Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE`). All sharpness, noise, exposure, subject, composition and technique (OpenCV), and duplicate analyses execute locally on-device.
- [x] **Zero Runtime Permissions:** `products/android/android-desktop/AndroidManifest.xml` declares zero `<uses-permission>` entries.
- [x] **Cloud Auto-Backup Hardened:** `AndroidManifest.xml` configures `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="@xml/backup_rules"` so the local Room score cache (`photo_selector_database`) and persisted SAF URI grants are never uploaded to cloud backup.
- [x] **Target SDK & 16 KB Page-Size Compliance:** `targetSdk = 36` (meets Google Play August 31, 2026 API 36 requirement) and `compileSdk = 37` in `products/android/android-desktop/build.gradle.kts`; every 64-bit native `.so` (`lib/{arm64-v8a,x86_64}/*.so`) is verified for `PT_LOAD` `p_align >= 16384` (16 KB) by `scripts/check_native_alignment.py` in CI and `scripts/run_tests.sh --android`.
- [x] **Hosted Legal Documents Present in Repo:**
  - `docs/products/android-desktop/privacy-policy.html` (bilingual EN/DE GDPR/DSGVO Art. 13 policy)
  - `docs/products/android-desktop/impressum.html` (bilingual DE/EN § 5 DDG legal notice)
- [x] **In-App Legal Links Wired:** `products/android/android-desktop/src/com/photoselectortoolbox/domain/LegalLinks.kt` defines `PRIVACY_POLICY`, `IMPRESSUM`, and `SOURCE_CODE`, surfaced in `SettingsScreen.kt` ("About" section).

---

## 2. Google Play Data Safety Form Answers

Because Frameduel requests zero permissions, has no network access, and processes all photos locally on-device:

| Play Console Question | Answer | Rationale |
|---|---|---|
| **Does your app collect or share any of the required user data types?** | **No** | All photo viewing, EXIF reading, quality scoring, and file operations (Copy/Move/Trash) happen strictly locally on the user's device via SAF. No data leaves the device. |
| **Is all of the user data collected by your app encrypted in transit?** | **N/A** (No data collected) | No network transmission occurs. |
| **Do you provide a way for users to request that their data is deleted?** | **N/A** (No data collected) | Users can clear the local score cache in Settings (`Clear Cache`) or uninstall the app at any time. |
| **Advertising ID declaration** | **No** | App does not use Advertising ID and includes no ad SDKs. |

---

## 3. EU Digital Services Act (DSA) & German Legal Status

- [x] **Non-Trader Declaration:** Frameduel is free, open-source (MIT), ad-free, and has no in-app purchases or monetization. Declared as **Non-Trader** in Google Play Console.
- [ ] **Human Review Before Public Store Launch:** Verify GitHub Pages serving (`https://alexpp90.github.io/photo-selector/products/android-desktop/privacy-policy.html` and `impressum.html`) and customize contact details in `impressum.html` if required before public store launch.
