package com.photoselectortoolbox

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A string of the app under test in the device's language, so an instrumented test never
 * re-types the wording and passes on a German device as on an English one (`[USER 2026-11-12]`
 * decision #34; the lab devices run German).
 */
fun str(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

/** A plural of the app under test; with no [args] the count is the only argument. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(
        id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args),
    )
