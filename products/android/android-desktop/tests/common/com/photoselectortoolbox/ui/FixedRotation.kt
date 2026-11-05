package com.photoselectortoolbox.ui

/**
 * The window manager's "fixed to user rotation" switch, as `cmd window` spells it.
 *
 * The spelling changed between releases: API 30 has only `set-fix-to-user-rotation`, API 31 and later
 * only `fixed-to-user-rotation` (measured on a Pixel Tablet, API 37, 2026-11-05: the old spelling
 * answers `Unknown command: set-fix-to-user-rotation`). `WindowGeometryRule` used the old one, on
 * every device, for as long as it existed: it never fixed the rotation anywhere, and the launcher's
 * NOSENSOR hold on a phone was left to decide it. The answer of an unknown command is text on
 * stdout, not an exception, so nothing noticed; [ShellOutput.failure] is what makes it loud.
 *
 * Modes, as the command prints and accepts them: `default`, `enabled`, `disabled` and, from API 31,
 * `enabled_if_no_auto_rotation`.
 */
internal object FixedRotation {
    const val ENABLED = "enabled"
    const val DEFAULT = "default"

    private val modes = setOf("default", "enabled", "disabled", "enabled_if_no_auto_rotation")

    /** The `cmd window` subcommand of the release that runs [sdkInt]. */
    fun subcommand(sdkInt: Int): String = if (sdkInt >= 31) "fixed-to-user-rotation" else "set-fix-to-user-rotation"

    /** The shell command that sets [mode] on the default display. */
    fun setCommand(sdkInt: Int, mode: String): String {
        require(mode in modes) { "unknown fixed-to-user-rotation mode '$mode'" }
        return "cmd window ${subcommand(sdkInt)} $mode"
    }

    /** The shell command that prints the current mode (API 31 and later; earlier builds print nothing useful). */
    fun queryCommand(sdkInt: Int): String = "cmd window ${subcommand(sdkInt)}"

    /**
     * The mode in the output of [queryCommand], or null when it is not one (an old build, an unknown
     * command, an empty answer). The caller then restores [DEFAULT], which is what an untouched
     * display holds.
     */
    fun parseQuery(output: String): String? = output.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it in modes }

    /** Whether [mode] is one this object knows, for a record read back from a device. */
    fun isMode(mode: String): Boolean = mode in modes
}

/** What a `shell` call's text says about whether the command ran. */
internal object ShellOutput {
    /**
     * The reason [output] shows that its command did not run, or null. `cmd` answers an unknown
     * subcommand with `Unknown command: …` on stdout, and a command-line error with an exception
     * trace, with exit code 255 that `UiAutomation.executeShellCommand` does not hand back.
     */
    fun failure(output: String): String? {
        val first = output.trimStart().lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            first.startsWith("Unknown command") -> first
            first.startsWith("Error: Unknown command") -> first
            first.startsWith("Exception occurred while executing") -> first
            else -> null
        }
    }
}
