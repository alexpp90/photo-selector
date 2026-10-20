package com.photoselectortoolbox.domain

/**
 * Single source of truth for Frameduel's public legal document URLs.
 *
 * The documents live in `docs/products/android-desktop/` in the repository and
 * are hosted by GitHub Pages from the `docs/` folder of `main`. The same URLs must be
 * entered in the Google Play Console store listing. `scripts/tests/test_legal_links.py`
 * checks that every hosted URL maps to a tracked file under `docs/`.
 */
object LegalLinks {
    const val PRIVACY_POLICY = "https://alexpp90.github.io/photo-selector/products/android-desktop/privacy-policy.html"
    const val IMPRESSUM = "https://alexpp90.github.io/photo-selector/products/android-desktop/impressum.html"
    const val SOURCE_CODE = "https://github.com/alexpp90/photo-selector"
}
