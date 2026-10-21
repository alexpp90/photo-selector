package com.phototok

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolves a string resource of the app under test, so an instrumented test never re-types (and
 * drifts from) the wording and keeps passing when the device language is not English
 * (REQ-TOK-UX.05).
 */
fun str(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** Resolves a plural resource of the app under test. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(
        id,
        count,
        *(if (args.isEmpty()) arrayOf<Any>(count) else args),
    )
