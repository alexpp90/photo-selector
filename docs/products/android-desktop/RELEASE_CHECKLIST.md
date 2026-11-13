# Frameduel (`:android-desktop`) — Release & Compliance Checklist

This checklist is the **single source of truth** for Google Play release readiness, DSGVO/GDPR privacy compliance, German DDG/Impressum compliance, and EU Digital Services Act (DSA) status for **Frameduel** (`com.photoselectortoolbox`).

---

## 0. Store listing

- [ ] **Title:** "Frameduel – Photo Culling Desk" (English; add the German (de-DE) listing text only once the app itself has a German interface — it is English-only today). Short and full descriptions name the app Frameduel; no earlier name appears in the listing.
- [ ] **Icon and screenshots:** the lavender Frameduel icon (adaptive, with themed monochrome layer); no screenshot shows the previous name or palette.

---

## 1. Architecture & Privacy Invariants (Repo-Side)

- [x] **100% Local On-Device SAF Architecture:** Frameduel accesses user photos exclusively via Android Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE`). All sharpness, noise, exposure, subject, composition and technique (OpenCV), and duplicate analyses execute locally on-device.
- [x] **Zero Runtime Permissions:** `products/android/android-desktop/AndroidManifest.xml` declares zero `<uses-permission>` entries, and so does the merged manifest: `LegalLinksTest` fails if any dependency merges in a permission (only `*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is allowed). WorkManager was removed for this reason (it merged `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`).
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

---

## 4. Play Console Manual Steps (Human-Only)

Mirrors the Jackpix checklist; the automated internal-track uploads from `android.yml` already
succeed for `com.photoselectortoolbox`, so the first manual upload has been done — confirm each
step in Play Console before ticking it.

- [ ] **Create the app:** package `com.photoselectortoolbox`, default language English (United States), *App*, *Free*, category **Photography**.
- [x] Verify GitHub Pages serves `privacy-policy.html` and `impressum.html` with HTTP 200 (the four legal URLs and the site root answered 200 on 2026-11-03; both pages again on 2026-11-12, byte-identical to the tracked files).
- [ ] Paste the Privacy Policy URL into **Play Console → Monitor and improve → Policy and programs → App content → Privacy policy** (Start declaration).
- [ ] Submit the Data Safety form using the table in §2 above.
- [ ] The first signed `.aab` was uploaded manually to the **Internal testing** track; automated publishing runs with `TOOLBOX_GP_SERVICE_ACCOUNT_JSON`.
- [ ] **App content → Content rating:** complete the IARC questionnaire (utility app, no user-generated content). **Target audience:** 13+, not directed at children.
- [ ] **Closed testing gate (mandatory for personal accounts created after 13 Nov 2023):** a closed test with at least 12 testers opted in for 14 consecutive days, then **Dashboard → Apply for production**. Re-check [Google's requirements](https://support.google.com/googleplay/android-developer/answer/14151465) before starting.
- [ ] **Android developer verification (regional enforcement since 2026-09-30, global in 2027):** in Play Console → Home, check that the package `com.photoselectortoolbox` is registered.
- [ ] **Off-Play APKs:** the GitHub Releases and Obtainium APKs are signed with the **upload key**, not the Play app-signing key. Register the upload-key certificate for `com.photoselectortoolbox` as an off-Play key in Play Console before the 2027 global rollout.

> Tester counts and the gate are Google policy and change — re-check
> [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
> before starting the closed test.

- [ ] **Device targeting — tablets, ChromeOS and DeX-capable devices only** (maintainer decision 2026-11-12; phone owners are pointed to Jackpix). Phones are excluded **by screen size**, not by device model. Before the first production rollout, in **Monitor and improve → Reach and devices → Device catalog**:
  - Check that the release build's manifest carries the screen-size filter (`<supports-screens android:smallScreens="false" android:normalScreens="false" android:largeScreens="true" android:xlargeScreens="true" />`). Google Play filters on `<supports-screens>`; the device catalog itself has no screen-size or form-factor rule (its rules are RAM and system-on-chip, plus excluding single device models — checked 2026-11-12 against [View and restrict your app's compatible devices](https://support.google.com/googleplay/android-developer/answer/1286017) and [Filters on Google Play](https://developer.android.com/google/play/filters)). **The manifest carries it since 2026-11-13** (`products/android/android-desktop/AndroidManifest.xml`; `ManifestScreenSizeTest` reads the merged manifest, so a dependency cannot drop it unnoticed), so Play filters phones out by screen size; check the uploaded bundle's manifest in the console says the same.
  - DeX phones: the console cannot re-admit a device the manifest filters out, so with the screen-size filter DeX phones are excluded too; the decision accepts that ("keep DeX phones if the console allows it" — it does not). Revisit if Play adds a form-factor or desktop-mode rule.
  - In **Device catalog → All**, confirm that phones show the status **Unsupported** with the manifest screen size as the reason, that the Galaxy Tab S11 Ultra (reference device) and a Chromebook show **Supported**, and check a Pixel Fold and a Galaxy Z Fold and note their status. *Excluded* covers console exclusions; the console needs no rule. Tick only then.
  - DeX phones can still install the GitHub Releases APK (the manifest filter is a store filter only; no runtime effect).
  - Confirm in the store-listing form that phone screenshots are not demanded (§6).

---

## 5. Store Listing (draft)

Store text promises behaviour, so every line must be true for the build being released. Before pasting, fact-check each feature line against the current code and remove anything that is no longer true. The draft was checked against `origin/main` on 2026-11-10.

- **Title rule:** always the name with a descriptive subtitle. Play limit: **30 characters**.
- **Disclaimer:** the non-affiliation sentence goes at the end of the full description.
- **First line:** the full description opens by saying the app is built for large screens (decision 2026-11-12: tablets, ChromeOS and DeX only, §4).
- **Language:** English (United States) only. Add a German (de-DE) listing only once the app has a German interface; a German listing for an English-only app promises what the app does not do.

### English (United States) — en-US

**App name** (≤ 30): `Frameduel – Photo Culling Desk` (30)

**Short description** (≤ 80):
> Cull photos on a tablet or DeX: compare frames, keep the best, delete the rest.

**Full description** (≤ 4000):
```text
Frameduel is a culling desk built for large screens: tablets, Samsung DeX and Chromebooks. Open a folder of photos — from the device, an SD card or a card reader — and go through it three frames at a time: the current photo on top, the previous and next beside each other below, all the same size, so you compare like with like.

• M moves the photo to your Selection folder, C copies it, Del deletes it. A RAW, its JPEG, its edits and its .xmp sidecar travel together.
• Undo: Ctrl+Z steps back through your last moves and deletes.
• 1, 2 or 3 shows one frame full size; F opens fullscreen.
• Works by touch, mouse and keyboard.

Features
• Quality scores on the device: a scan rates sharpness, noise and blown highlights or blocked shadows, so the soft frame of a burst stands out.
• Burst grouping keeps similar frames together.
• Sort newest first, oldest first, by file name, or blurriest first after a scan.
• Go to photo by its number, a date or its file name.
• Star ratings and a reject flag.
• Statistics: EXIF charts of the folder (focal lengths, apertures, ISO, shutter speeds).
• Tools beside the cull: Duplicates finds byte-identical copies and never lets you delete the last one; Learn shows the composition of your own photos and compares two frames of one scene side by side.
• Camera RAW from Sony, Canon (incl. CR3), Nikon, Fujifilm, Panasonic, OM System, Pentax, Samsung and DNG, plus JPEG, HEIC, PNG, TIFF and WebP.

Private by design
• No permissions, no internet access, no account.
• No ads, no analytics, no tracking. Your photos never leave your device.
• Free and open source (MIT). English interface.

Frameduel is not affiliated with Samsung, Google or any camera maker; brand names only describe compatibility.
```

---

## 6. Store Graphics & Screenshots

- [ ] **App icon:** 512 × 512 px, 32-bit PNG with alpha, at most 1024 KB — the lavender Frameduel icon (`assets/brand/frameduel/frameduel-play-512.png`).
- [ ] **Feature graphic:** 1024 × 500 px, JPEG or 24-bit PNG (no alpha).
- [ ] **Tablet screenshots (7-inch and 10-inch):** at least 4 per device type, at most 8; 16:9 landscape (the selector) or 9:16 portrait; each side between 1,080 and 7,680 px, JPEG or 24-bit PNG. Render on a 16:9 canvas (e.g. 1920 × 1080 or 2560 × 1440 px), not at the 16:10 reference geometry (1480 × 924 dp) and not by cropping a capture.
- [ ] **Chromebook screenshots:** at least 4, same rules as tablets (16:9 landscape).
- [ ] **Phone screenshots:** only if phones stay in the device catalog (§4); then 2–8, sides between 320 and 3,840 px, the long side at most twice the short side.
- [ ] Screenshots show the **current** UI of the release build, contain **no people** (sample photos included), and no earlier name or palette.

> Asset specs are Google policy and change. Checked 2026-11-10 against
> [Add preview assets to showcase your app](https://support.google.com/googleplay/android-developer/answer/9866151);
> that page gives 3,840 px as the general maximum and 7,680 px for large screens — keep large-screen
> captures at or below 3,840 px to satisfy both. Re-check before producing them.

---

## 7. Ongoing Obligations (after launch)

- Keep the privacy policy, the Data Safety answers (§2) and the store listing (§5) in sync with app behaviour — any new permission, endpoint or SDK changes all three in the same change. The merged manifest stays permission-free (`LegalLinksTest`).
- **Target API:** Play requires staying within one year of the latest Android release; re-check `targetSdk` when a new Android version ships.
- **16 KB page size:** every new native library (OpenCV today) must keep `p_align >= 16384` (`scripts/check_native_alignment.py`).
- **Monetisation** (ads, in-app purchases, paid tier) ends the non-trader status (§3) and invalidates the "no internet, no permissions" claims — review the legal consequences before the first line of code.
