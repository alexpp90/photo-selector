package com.photoselectortoolbox.testing

import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.ui.text.resolve
import java.util.Locale

/**
 * Frameduel's wording, resolved against its real resources inside a Robolectric test, so a
 * behaviour test can say "the message names the folder" without re-typing the English sentence
 * (`[USER 2026-11-12]` decision #34). Only for tests that run under Robolectric.
 */
fun UiText.en(): String = resolve(resources(Locale.ENGLISH))

/** The same text in German (`values-de`). */
fun UiText.de(): String = resolve(resources(Locale.GERMAN))

/** A string resource in English. */
fun str(@StringRes id: Int, vararg args: Any): String = resources(Locale.ENGLISH).getString(id, *args)

/** A plural resource in English; with no [args] the count is the only argument. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    resources(Locale.ENGLISH).getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args))

private fun resources(locale: Locale): Resources {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
    return app.createConfigurationContext(config).resources
}
