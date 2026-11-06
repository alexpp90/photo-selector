package com.photoselectortoolbox.ui

/**
 * The display state `WindowGeometryRule` found before it forced the reference geometry, in the
 * form it is kept on the device between runs.
 *
 * Why it is kept: the matrix kills an instrumentation process that has produced no output for ten
 * minutes (Moto G77, 2026-11-05), and a killed process never reaches the rule's `finally`. The
 * next run then read the leftover tablet geometry as the device's own and "restored" to it, so one
 * hang left the phone at 1480 x 924 dp for the rest of the run. The rule writes this record before
 * it forces anything and deletes it after a restore; a record that is still there at the next run
 * is the true original.
 *
 * The encoding is `key=value` pairs joined by commas, with `-` for "none", so it is safe inside a
 * single-quoted shell argument and in a `settings` value.
 */
internal data class DisplayRecord(
    /** `wm size` override as `WxH`, or null when the display has none. */
    val sizeOverride: String?,
    /** `wm density` override, or null. */
    val densityOverride: String?,
    val accelerometerRotation: String,
    val userRotation: String,
    /**
     * The `fixed-to-user-rotation` mode the display had (`default`, `enabled`, `disabled`,
     * `enabled_if_no_auto_rotation`, see [FixedRotation]); null when the build did not say, which is
     * restored as `default`.
     */
    val fixedToUserRotation: String?,
    /** Width of the display in dp before anything was forced, when it could be read. */
    val widthDpBefore: Int?,
) {
    fun encode(): String = listOf(
        "size" to (sizeOverride ?: NONE),
        "density" to (densityOverride ?: NONE),
        "accel" to safe(accelerometerRotation),
        "rot" to safe(userRotation),
        "fixed" to (fixedToUserRotation ?: NONE),
        "width" to (widthDpBefore?.toString() ?: NONE),
    ).joinToString(",") { (key, value) -> "$key=$value" }

    companion object {
        private const val NONE = "-"

        /** A value that must survive `key=value,` splitting and shell quoting; anything else becomes "none". */
        private fun safe(value: String): String =
            value.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } } ?: NONE

        /** The record in [text] (the output of `settings get`), or null when there is none or it is damaged. */
        fun decode(text: String?): DisplayRecord? {
            val fields = text?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                ?.split(',')
                ?.mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                ?.toMap()
                ?: return null
            fun optional(key: String): String? = fields[key]?.takeIf { it != NONE }
            val size = optional("size")
            if (size != null && !Regex("""\d+x\d+""").matches(size)) return null
            return DisplayRecord(
                sizeOverride = size,
                densityOverride = optional("density")?.also { if (it.toIntOrNull() == null) return null },
                accelerometerRotation = fields["accel"] ?: return null,
                userRotation = fields["rot"] ?: return null,
                fixedToUserRotation = optional("fixed")?.also { if (!FixedRotation.isMode(it)) return null },
                widthDpBefore = optional("width")?.toIntOrNull(),
            )
        }
    }
}
