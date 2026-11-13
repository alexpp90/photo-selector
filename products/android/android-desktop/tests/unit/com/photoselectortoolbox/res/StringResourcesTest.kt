package com.photoselectortoolbox.res

import com.photoselector.core.Requirement
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Frameduel speaks English and German (`[USER 2026-11-12]` decision #34, REQ-AND-THEME.06):
 * every English string has its German entry with the same format arguments, every plural has
 * the quantities both languages need, every key names its element so the UI-text budget can be
 * checked (`scripts/check_ui_text_budget.py`, `mode: fail`), and no resource is left unused.
 */
class StringResourcesTest {

    private fun file(path: String): File =
        listOf(File(path), File("products/android/android-desktop/$path"), File("../../../$path"))
            .firstOrNull { it.exists() } ?: error("$path not found from ${File(".").absolutePath}")

    private fun read(path: String): Map<String, Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(path))
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { it.getAttribute("name").isNotEmpty() }
            .associateBy { it.getAttribute("name") }
    }

    private val en = read("res/values/strings.xml")
    private val de = read("res/values-de/strings.xml")

    /** The element prefixes of `scripts/ui_text_budget.json`, the budget table's single source. */
    private val prefixes: List<String> by lazy {
        val json = listOf(File("scripts/ui_text_budget.json"), File("../../../scripts/ui_text_budget.json"))
            .first { it.exists() }.readText()
        Regex("\"prefix\":\\s*\"([a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toList()
            .also { assertTrue("no prefixes read from ui_text_budget.json", it.isNotEmpty()) }
    }

    private val exempt = setOf("app_name")

    private fun texts(el: Element): List<String> =
        if (el.tagName == "plurals") {
            val items = el.getElementsByTagName("item")
            (0 until items.length).map { items.item(it).textContent }
        } else {
            listOf(el.textContent)
        }

    private fun args(text: String): Set<String> =
        Regex("%(\\d+\\$)?[sdf]|%\\.\\d+f|%(\\d+\\$)\\.\\d+f").findAll(text).map { it.value }.toSet()

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `every English string has a German entry and no German entry is orphaned`() {
        assertEquals("keys missing in values-de", emptySet<String>(), en.keys - de.keys - exempt)
        assertEquals("keys only in values-de", emptySet<String>(), de.keys - en.keys)
    }

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `German keeps the format arguments of the English string`() {
        en.forEach { (name, el) ->
            val german = de[name] ?: return@forEach
            assertEquals("format arguments of $name", texts(el).flatMap(::args).toSet(), texts(german).flatMap(::args).toSet())
        }
    }

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `every German entry is translated text, not empty`() {
        de.forEach { (name, el) -> texts(el).forEach { assertTrue("$name is empty in values-de", it.isNotBlank()) } }
    }

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `every key carries an element prefix of the budget table`() {
        (en.keys - exempt).forEach { key ->
            assertTrue("$key has no element prefix of scripts/ui_text_budget.json", prefixes.any { key.startsWith(it) })
        }
    }

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `plurals define one and other in both languages`() {
        listOf(en, de).forEach { strings ->
            strings.filterValues { it.tagName == "plurals" }.forEach { (name, el) ->
                val items = el.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                assertTrue("$name needs one and other", quantities.containsAll(listOf("one", "other")))
            }
        }
    }

    @Test
    @Requirement("REQ-AND-THEME.06")
    fun `every date pattern has its German twin and formats a date in both languages`() {
        val enFormats = read("res/values/formats.xml")
        val deFormats = read("res/values-de/formats.xml")
        assertEquals(enFormats.keys, deFormats.keys)
        val day = java.time.LocalDateTime.of(2026, 10, 12, 14, 32)
        for ((name, el) in enFormats) {
            java.time.format.DateTimeFormatter.ofPattern(el.textContent, java.util.Locale.ENGLISH).format(day)
            java.time.format.DateTimeFormatter.ofPattern(deFormats.getValue(name).textContent, java.util.Locale.GERMAN).format(day)
        }
        assertEquals("12. Okt. 2026", java.time.format.DateTimeFormatter.ofPattern(deFormats.getValue("format_date").textContent, java.util.Locale.GERMAN).format(day))
    }

    @Test
    fun `every string resource is used by code or a resource file`() {
        val roots = listOf(file("src"), file("res"), file("AndroidManifest.xml"))
        val sources = roots.flatMap { r -> if (r.isDirectory) r.walkTopDown().filter { it.isFile }.toList() else listOf(r) }
            .filterNot { it.path.endsWith("strings.xml") || it.path.endsWith("formats.xml") }
            .filter { it.extension in setOf("kt", "xml") }
            .joinToString("\n") { it.readText() }
        val unused = en.keys.filter { name ->
            !Regex("R\\.(string|plurals)\\.$name\\b").containsMatchIn(sources) &&
                !Regex("@(string|plurals)/$name\\b").containsMatchIn(sources)
        }
        assertEquals("unused string resources", emptyList<String>(), unused)
    }
}
