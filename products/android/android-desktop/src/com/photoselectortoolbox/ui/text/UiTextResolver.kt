package com.photoselectortoolbox.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.photoselectortoolbox.domain.text.UiText

/** This text in the language of [resources]; nested [UiText] arguments are resolved first. */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *argsFor(args, resources))
    is UiText.Plural -> resources.getQuantityString(id, count, *argsFor(args, resources))
    is UiText.Date -> java.time.format.DateTimeFormatter
        .ofPattern(resources.getString(pattern), resources.configuration.locales[0])
        .format(value)
    is UiText.Joined -> parts.map { it.resolve(resources) }.filter { it.isNotEmpty() }.joinToString(separator)
}

/** This text for a `Context` that may hold one (the application, an Activity). */
fun UiText.resolve(context: Context): String = resolve(context.resources)

private fun argsFor(args: List<Any>, resources: Resources): Array<Any> =
    Array(args.size) { i -> (args[i] as? UiText)?.resolve(resources) ?: args[i] }

/** This text in the current language, resolved again when the configuration changes. */
@Composable
fun UiText.asString(): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(this, context, configuration) { resolve(context.resources) }
}
