package com.phototok.domain

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * User-visible text that has not been resolved against a locale yet.
 *
 * Domain wording classes and ViewModels stay pure Kotlin (no `Context`), so they return a
 * [UiText] — a string-resource id plus its arguments — and the UI resolves it where a `Context`
 * legitimately exists (`com.phototok.ui.asString`). Tests therefore assert ids and arguments, not
 * English sentences (`docs/shared/UI_TEXT.md` § Localization).
 *
 * An argument may itself be a [UiText]; it is resolved first.
 */
sealed interface UiText {

    /** A string resource with optional format arguments. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A plural resource; [count] selects the quantity and is also the first format argument. */
    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    /** Text that is data, not wording — a folder or file name — and is never translated. */
    data class Raw(val value: String) : UiText

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

        fun raw(value: String): UiText = Raw(value)
    }
}
