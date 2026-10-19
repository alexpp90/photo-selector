package com.phototok

import android.content.res.Resources
import com.phototok.domain.UiText
import com.phototok.ui.resolve
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * Resolves a [UiText] against the app's real resources inside a Robolectric test, so a behaviour
 * test can still say "the hint names the configured verb" without re-typing the English sentence.
 * Use it only inside a `@RunWith(RobolectricTestRunner::class)` class.
 */
fun UiText.en(): String = resolve(localised(Locale.ENGLISH))

/** The same text in German (`values-de`). */
fun UiText.de(): String = resolve(localised(Locale.GERMAN))

private fun localised(locale: Locale): Resources {
    val base = RuntimeEnvironment.getApplication().resources
    val config = android.content.res.Configuration(base.configuration).apply { setLocale(locale) }
    return RuntimeEnvironment.getApplication().createConfigurationContext(config).resources
}
