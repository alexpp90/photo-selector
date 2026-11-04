package com.photoselectortoolbox.ui.components

import com.photoselector.core.Requirement
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-AND-INPUT.08: every interactive control shows the hand cursor under a mouse or trackpad.
 * Material buttons, switches and checkboxes do not set it themselves, and a custom `clickable`
 * does not either. Compose has no hook to ask a rendered node, so this reads the sources: a control
 * call with neither `handCursor()` nor `pointerHoverIcon` is listed (FD-UX-2, frameduel-ui.md).
 */
class PointerCursorTest {

    private val materialControls = Regex(
        """(?<![\w.])(Button|TextButton|IconButton|FilledTonalButton|OutlinedButton|DropdownMenuItem|""" +
            """FilterChip|AssistChip|Switch|Checkbox|RadioButton|FilledIconButton)\(""",
    )
    private val customClickables = Regex("""\.(clickable|toggleable|selectable|combinedClickable)\(""")

    private fun uiSources() = File("src/com/photoselectortoolbox/ui").walkTopDown()
        .filter { it.extension == "kt" && it.name != "PointerCursor.kt" }.toList()

    private fun callEnd(source: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return source.length - 1
    }

    private fun hasCursor(text: String) = "handCursor" in text || "pointerHoverIcon" in text

    @Test
    @Requirement("REQ-AND-INPUT.08")
    fun `every Material control has the hand cursor`() {
        val missing = mutableListOf<String>()
        uiSources().forEach { file ->
            val source = file.readText()
            materialControls.findAll(source).forEach { m ->
                val lineStart = source.lastIndexOf('\n', m.range.first) + 1
                if (source.substring(lineStart, m.range.first).isNotBlank()) return@forEach // an expression, not a call
                val call = source.substring(m.range.first, callEnd(source, m.range.last) + 1)
                if (!hasCursor(call)) missing += "${file.name}:${source.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        assertTrue("controls without the hand cursor: $missing", missing.isEmpty())
    }

    @Test
    @Requirement("REQ-AND-INPUT.08")
    fun `every custom clickable sits in a chain with the hand cursor`() {
        val missing = mutableListOf<String>()
        uiSources().forEach { file ->
            val source = file.readText()
            customClickables.findAll(source).forEach { m ->
                val window = source.substring(maxOf(0, m.range.first - 500), minOf(source.length, m.range.last + 400))
                if (!hasCursor(window)) missing += "${file.name}:${source.substring(0, m.range.first).count { it == '\n' } + 1}"
            }
        }
        assertTrue("clickables without the hand cursor: $missing", missing.isEmpty())
    }
}
