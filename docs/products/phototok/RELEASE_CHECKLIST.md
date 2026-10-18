# PhotoTok (`:phototok`) — Release & Compliance Checklist

This checklist is the **single source of truth** for Google Play release readiness, DSGVO/GDPR privacy compliance, German DDG/Impressum compliance, and EU Digital Services Act (DSA) status for **PhotoTok** (`com.phototok`).

---

## 1. Architecture & Privacy Invariants (Repo-Side)

- [x] **100% Local On-Device SAF Architecture:** PhotoTok accesses user photos exclusively via Android Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE`). No cloud backend, no Google Drive OAuth, and no WebView Picker are used.
- [x] **Zero Runtime Permissions & Zero Package Queries:** `products/android/phototok/AndroidManifest.xml` declares zero `<uses-permission>` and zero `<queries>` entries (no `INTERNET`, no `READ_MEDIA_IMAGES`, no `READ_EXTERNAL_STORAGE`). System Share / Open With (`Intent.createChooser`) and Android 11+ `MediaStore` favourite/trash/delete requests (`createFavoriteRequest`, `createTrashRequest`, `createDeleteRequest`) run strictly through user-confirmed system sheets without storage permissions.
- [x] **Cloud Auto-Backup Hardened:** `AndroidManifest.xml` configures `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, and `android:fullBackupContent="@xml/backup_rules"` so persisted SAF URI grants, local favourites/pending-delete journals, seen-photo fingerprints (`seen_photos.log`), and local culling statistics in app-private storage are never silently uploaded to cloud backup.
- [x] **Target & Compile SDK:** `targetSdk = 36` (meets Google Play August 31, 2026 API 36 requirement) and `compileSdk = 37` in `products/android/phototok/build.gradle.kts`.
- [x] **Hosted Legal Documents Present in Repo:**
  - `docs/products/phototok/privacy-policy.html` (bilingual EN/DE GDPR/DSGVO Art. 13 policy)
  - `docs/products/phototok/impressum.html` (bilingual DE/EN § 5 DDG legal notice)
- [x] **In-App Legal Links Wired:** `products/android/phototok/src/com/phototok/domain/LegalLinks.kt` defines `PRIVACY_POLICY`, `IMPRESSUM`, and `SOURCE_CODE`, surfaced in the Settings sheet ("About" section).

---

## 2. Google Play Data Safety Form Answers

Because PhotoTok requests zero permissions, has no network access, and processes all photos locally on-device:

| Play Console Question | Answer | Rationale |
|---|---|---|
| **Does your app collect or share any of the required user data types?** | **No** | All photo viewing, EXIF reading, seen-photo fingerprinting, culling statistics, `MediaStore` favourite/trash updates, and file operations (Copy/Move/Delete) happen strictly locally on the user's device. Explicit user-initiated sharing via the Android system share sheet (`Intent.createChooser`) does not constitute developer data collection or sharing under Play policy. |
| **Is all of the user data collected by your app encrypted in transit?** | **N/A** (No data collected) | No network transmission occurs. |
| **Do you provide a way for users to request that their data is deleted?** | **N/A** (No data collected) | Users control all local files directly and can clear app preferences or uninstall the app at any time. |
| **Advertising ID declaration** | **No** | App does not use Advertising ID and includes no ad SDKs. |

---

## 3. EU Digital Services Act (DSA) & German Legal Status

- [x] **Non-Trader Declaration:** PhotoTok is free, open-source (MIT), ad-free, and has no in-app purchases or monetization. Declared as **Non-Trader** in Google Play Console (private physical address is not publicly displayed on the store listing).
- [ ] **Human Review Before Public Store Launch:** If personal contact details in `docs/products/phototok/impressum.html` need to be customized beyond the GitHub contact address prior to public production release in Germany (§ 5 DDG), update `impressum.html` and verify GitHub Pages serving at the URLs in `LegalLinks.kt` (`https://alexpp90.github.io/photo-selector/products/phototok/privacy-policy.html` and `…/impressum.html`; GitHub Pages must serve `main:/docs`).

---

## 4. Play Console Manual Steps (Human-Only)

- [x] Personal developer account registered (2026-07).
- [ ] **Create the app:** name per §5, default language English (United States), *App*, *Free*, category **Photography**. Add the German (de-DE) listing as a translation.
- [ ] Verify GitHub Pages is enabled and serves `privacy-policy.html` and `impressum.html` with HTTP 200.
- [ ] Paste the Privacy Policy URL into **Play Console → Policy and programs → App content → Privacy policy**.
- [ ] Submit the Data Safety form using the table in §2 above.
- [ ] Upload the first signed release `.aab` manually to the **Internal testing** track to initialize the `com.phototok` package in Play Console, then enable automated GitHub Actions publishing via `PHOTOTOK_GP_SERVICE_ACCOUNT_JSON`.
- [ ] **App content → Content rating:** complete the IARC questionnaire (utility app, no user-generated content, no violence/nudity). **Target audience:** 13+, not directed at children.
- [ ] **Closed testing gate (mandatory for this account):** personal accounts created after 13 Nov 2023 must run a closed test with **at least 12 testers opted in for 14 consecutive days** before they can apply for production access. Create a *Closed testing* track, upload the CI-built AAB, add the testers by e-mail list and send them the opt-in link; keep them opted in for the full 14 days while iterating.
- [ ] After the 14 days: **Dashboard → Apply for production** (short questionnaire about the closed test).

> Tester counts and the gate are Google policy and change — re-check
> [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
> before starting the closed test.

---

## 5. Store Listing (draft)

Store text promises behaviour, so every line must be true for the build being released. Before pasting, fact-check each feature line against the current code and remove anything that is no longer true.

- **Title rule:** never "Photo-Tok" alone — always with a descriptive subtitle (trademark distance to TikTok). Play limit: **30 characters**.
- **Disclaimer:** the non-affiliation sentence goes at the end of the full description (it does not fit the 80-character short description).

### English (United States) — en-US

**App name** (≤ 30): `Photo-Tok – Photo Selector` (26) · alternative `Photo-Tok: Fast Photo Culler` (28)

**Short description** (≤ 80):
> Swipe through your photos, keep the best, delete the rest. 100% offline.

**Full description** (≤ 4000):
```text
Photo-Tok turns sorting photos into a feed you flick through. Open your camera roll or any folder — on your phone, an SD card or a USB card reader — and swipe:

• Swipe up/down to browse, newest first.
• Swipe right to copy the photo into your "Selection" folder (or move it — your choice).
• Swipe left to delete it. Changed your mind? One tap on Revert brings it back.

Features
• Starts right away: pick "Phone Camera" or any folder, no setup.
• RAW + JPEG pairs: Photo-Tok offers to treat both files of a shot as one, so copy, move and delete apply to both.
• Camera info: tap Info to see the photo's EXIF data.
• Pinch or double-tap to zoom and check focus.
• Recent folders and resume: pick up where you left off.
• Configurable swipes: the left swipe can also copy or move to a folder of your choice.
• Randomize order to rediscover old photos.

Private by design
• No permissions, no internet access, no account.
• No ads, no analytics, no tracking. Your photos never leave your device.
• Free and open source (MIT).

Photo-Tok is an independent, 100% offline photography utility and is not affiliated with, sponsored by, endorsed by, or in any way associated with TikTok, ByteDance Ltd., or their affiliates.
```

### German (Germany) — de-DE

**App-Name** (≤ 30): `Photo-Tok – Foto-Auswahl` (24)

**Kurzbeschreibung** (≤ 80):
> Fotos durchwischen, die besten behalten, den Rest löschen. 100 % offline.

**Vollständige Beschreibung** (≤ 4000):
```text
Photo-Tok macht Fotos-Aussortieren zu einem Feed, durch den du wischst. Öffne deine Kamera-Galerie oder einen beliebigen Ordner — auf dem Handy, einer SD-Karte oder einem USB-Kartenleser — und wische:

• Nach oben/unten wischen: blättern, neueste zuerst.
• Nach rechts wischen: Foto in deinen Ordner „Selection“ kopieren (oder verschieben — du entscheidest).
• Nach links wischen: Foto löschen. Doch behalten? Ein Tipp auf „Revert“ holt es zurück.

Funktionen
• Sofort startklar: „Phone Camera“ oder einen beliebigen Ordner wählen, keine Einrichtung.
• RAW- + JPEG-Paare: Photo-Tok bietet an, beide Dateien einer Aufnahme als eine zu behandeln — Kopieren, Verschieben und Löschen gelten dann für beide.
• Kamera-Infos: Tippe auf „Info“ für die EXIF-Daten des Fotos.
• Mit zwei Fingern oder Doppeltipp zoomen und die Schärfe prüfen.
• Zuletzt verwendete Ordner und Fortsetzen: weitermachen, wo du aufgehört hast.
• Wischgesten einstellbar: Links-Wischen kann auch in einen Ordner deiner Wahl kopieren oder verschieben.
• Zufällige Reihenfolge, um alte Fotos wiederzuentdecken.

Privatsphäre von Anfang an
• Keine Berechtigungen, kein Internetzugriff, kein Konto.
• Keine Werbung, keine Analyse, kein Tracking. Deine Fotos verlassen dein Gerät nie.
• Kostenlos und Open Source (MIT). Die App-Oberfläche ist auf Englisch.

Photo-Tok ist ein unabhängiges, zu 100 % offline arbeitendes Foto-Werkzeug und steht in keiner Verbindung zu TikTok, ByteDance Ltd. oder deren verbundenen Unternehmen; es wird von diesen weder gesponsert noch unterstützt.
```

> The app has no German strings yet (`res/values/` only), so the German text quotes the English UI labels („Selection“, „Revert“, „Phone Camera“, „Info“) and says the UI is English. Update both when a German translation ships.

---

## 6. Store Graphics & Screenshots

- [ ] **App icon:** 512 × 512 px, 32-bit PNG.
- [ ] **Feature graphic:** 1024 × 500 px, JPEG or 24-bit PNG (no alpha).
- [ ] **Phone screenshots:** 2–8, PNG/JPEG, sides between 320 and 3840 px, the long side **at most twice** the short side. Full-screen 1080 × 2400 device captures (20:9) exceed 2:1 — crop or render to 1080 × 1920 (9:16). At least 4 screenshots of ≥ 1080 px are needed to be eligible for featuring.
- [ ] Screenshots show the **current** UI of the release build (no removed features such as a cloud source), contain **no people** (sample photos included), and avoid neon cyan/magenta styling or music-note iconography.

> Asset specs are Google policy and change — re-check
> [Add preview assets to showcase your app](https://support.google.com/googleplay/android-developer/answer/9866151) before producing them.

---

## 7. Ongoing Obligations (after launch)

- Keep the privacy policy, the Data Safety answers (§2) and the store listing (§5) in sync with app behaviour — any new permission, endpoint or SDK changes all three in the same change.
- **Target API:** Play requires staying within one year of the latest Android release; re-check `targetSdk` when a new Android version ships.
- **Monetisation** (ads, in-app purchases, paid tier) ends the non-trader status (§3) and invalidates the "no internet, no permissions" claims — review the legal consequences before the first line of code.
