package com.photoselectortoolbox.ui.navigation

import com.photoselectortoolbox.domain.guidance.SidebarAction
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Screen sealed class — routes, labels, and the
 * Screen.all list used by navigation bars.
 */
class ScreenTest {

    @Test
    fun `all screens have unique routes`() {
        val routes = Screen.all.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `all screens have non-empty labels`() {
        Screen.all.forEach { screen ->
            assertTrue("${screen.route} has empty label", screen.label.isNotEmpty())
        }
    }

    @Test
    fun `Screen all contains exactly 5 desktop screens`() {
        assertEquals(5, Screen.all.size)
    }

    @Test
    fun `PhotoSelector is start destination`() {
        assertEquals("selector", Screen.PhotoSelector.route)
        assertEquals("Cull", Screen.PhotoSelector.label)
    }

    @Test
    fun `Inspect route and label`() {
        assertEquals("inspect", Screen.Inspect.route)
        assertEquals("Learn", Screen.Inspect.label)
        assertEquals("Learn", Screen.Inspect.shortLabel)
    }

    @Test
    fun `Statistics route and label`() {
        assertEquals("statistics", Screen.Statistics.route)
        assertEquals("Stats", Screen.Statistics.label)
    }

    @Test
    fun `DuplicateFinder route and label`() {
        assertEquals("duplicates", Screen.DuplicateFinder.route)
        assertEquals("Dupes", Screen.DuplicateFinder.label)
    }

    @Test
    fun `Settings route and label`() {
        assertEquals("settings", Screen.Settings.route)
        assertEquals("Setup", Screen.Settings.label)
    }

    @Test
    fun `Screen all order is Selector, Inspect, Statistics, Duplicates, Settings`() {
        assertEquals(Screen.PhotoSelector, Screen.all[0])
        assertEquals(Screen.Inspect, Screen.all[1])
        assertEquals(Screen.Statistics, Screen.all[2])
        assertEquals(Screen.DuplicateFinder, Screen.all[3])
        assertEquals(Screen.Settings, Screen.all[4])
    }

    /**
     * One name per screen (backlog 2026-11-11): the sidebar draws [Screen.shortLabel], the rail
     * and the bottom bar [Screen.label] (`AdaptiveNavigation`), so the two must be one word.
     */
    @Test
    fun `each screen has one name on the sidebar, the rail and the bottom bar`() {
        assertEquals(listOf("Cull", "Learn", "Stats", "Dupes", "Setup"), Screen.all.map { it.label })
        Screen.all.forEach { assertEquals(it.label, it.shortLabel) }
    }

    /** No visible or spoken string calls the cull screen "Selector" or "Culling", or Bursts by another name. */
    @Test
    fun `no user-facing string uses a retired name`() {
        val retired = Regex("""\b(Selector|Culling|Group Similar Series)\b""")
        val bad = mutableListOf<String>()
        listOf("src/com/photoselectortoolbox/ui", "src/com/photoselectortoolbox/domain/format", "src/com/photoselectortoolbox/domain/guidance")
            .flatMap { dir -> File(dir).walkTopDown().filter { it.extension == "kt" }.toList() }
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    val code = line.trimStart()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || "Log." in line) return@forEachIndexed
                    Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(line).forEach { literal ->
                        if (retired.containsMatchIn(literal.value)) bad += "${file.name}:${i + 1} ${literal.value}"
                    }
                }
            }
        assertTrue("retired screen or feature name in a string: $bad", bad.isEmpty())
    }

    /** The guide's meaning lines name only what the More menu and Setup offer. */
    @Test
    fun `the More and Setup meanings name what exists`() {
        assertEquals("Undo, open folder, scan, sort, go to, guide.", SidebarAction.MORE.meaning)
        assertEquals("Where frames go, bursts, fullscreen, cache, about.", Screen.Settings.meaning)
        assertFalse(Screen.Settings.meaning.contains("measurement"))
        assertFalse(SidebarAction.MORE.meaning.contains("settings"))
    }
}
