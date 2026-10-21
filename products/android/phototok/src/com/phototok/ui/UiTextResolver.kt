package com.phototok.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.phototok.domain.UiText

/** Resolves this text against [resources] (nested [UiText] arguments are resolved first). */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> resources.getString(id, *resolveArgs(args, resources))
    is UiText.Plural -> resources.getQuantityString(id, count, *resolveArgs(args, resources))
}

/** Resolves this text for a `Context` that is allowed to hold one (an Activity, a Composable). */
fun UiText.resolve(context: Context): String = resolve(context.resources)

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    Array(args.size) { i ->
        when (val a = args[i]) {
            is UiText -> a.resolve(resources)
            else -> a
        }
    }

/** The text in the current locale, re-resolved when the configuration (locale, font) changes. */
@Composable
fun UiText.asString(): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(this, context, configuration) { resolve(context.resources) }
}
