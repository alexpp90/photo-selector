package com.photoselectortoolbox.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-AND-THEME.03: no elevation or drop shadows; surfaces separate by 1 dp outlines and value steps. */
class NoElevationTest {

    @Test
    fun `no surface is raised or shadowed`() {
        val raised = Regex("""(tonalElevation|shadowElevation|elevation)\s*=\s*(?!0\.dp)[1-9]\d*(\.\d+)?\.dp|\.shadow\(""")
        val bad = mutableListOf<String>()
        File("src/com/photoselectortoolbox/ui").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (!line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && raised.containsMatchIn(line)) {
                    bad += "${file.name}:${i + 1}"
                }
            }
        }
        assertTrue("elevation or shadow: $bad", bad.isEmpty())
    }
}
