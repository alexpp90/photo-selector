package com.phototok.domain

/**
 * Single source of truth for Jackpix's public legal document URLs.
 *
 * The documents live in `docs/products/phototok/` in the repository and are hosted by
 * GitHub Pages from the `docs/` folder of `main` (Google Play requires a reachable
 * privacy-policy link). Update these constants if the hosting location changes; the same
 * URLs must be entered in the Play Console store listing. `scripts/tests/test_legal_links.py`
 * checks that every hosted URL maps to a tracked file under `docs/`.
 */
object LegalLinks {
    const val PRIVACY_POLICY = "https://alexpp90.github.io/photo-selector/products/phototok/privacy-policy.html"
    const val IMPRESSUM = "https://alexpp90.github.io/photo-selector/products/phototok/impressum.html"
    const val SOURCE_CODE = "https://github.com/alexpp90/photo-selector"
}
