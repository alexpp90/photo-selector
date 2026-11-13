package com.photoselectortoolbox.domain.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Wording that has not met a locale yet (`[USER 2026-11-12]` decision #34: Frameduel speaks
 * English and German).
 *
 * The domain and the view models stay free of `Context`, so they hand the UI a string-resource
 * id with its arguments, and the UI resolves it where a `Context` exists
 * (`com.photoselectortoolbox.ui.text.asString`). A test asserts the id and the arguments, or
 * resolves them against the real resources in either language. An argument that is itself a
 * [UiText] is resolved first; anything else (a number, a file name) is passed through.
 */
sealed interface UiText {

    /** A `<string>` resource and its format arguments. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A `<plurals>` resource: [count] picks the quantity; [args] are the format arguments. */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Data, not wording (a file or folder name, a camera model): shown as it is. */
    data class Raw(val value: String) : UiText

    /** Several texts in a row, e.g. a sentence and the warning that follows it. */
    data class Joined(val parts: List<UiText>, val separator: String = " ") : UiText

    companion object {
        val Empty: UiText = Raw("")
    }
}

/** A string resource with its arguments. */
fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** A plural resource; with no [args] the count is the only argument. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

/** Data shown as it is. */
fun raw(value: String): UiText = UiText.Raw(value)

/** This text, then [other], separated by [separator]. */
fun UiText.then(other: UiText, separator: String = " "): UiText = UiText.Joined(listOf(this, other), separator)
