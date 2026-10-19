package com.phototok.domain

import com.photoselector.core.Requirement
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * REQ-TOK-UX.05: every English string has a German translation with the same format arguments,
 * every plural has the quantities each language needs, and no key lacks an element prefix. The
 * word and character budgets are checked by `scripts/check_ui_text_budget.py`.
 */
class StringResourcesTest {

    private fun read(path: String): Map<String, Element> {
        val file = listOf(File(path), File("products/android/phototok/$path")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { it.getAttribute("name").isNotEmpty() }
            .associateBy { it.getAttribute("name") }
    }

    private val en = read("res/values/strings.xml")
    private val de = read("res/values-de/strings.xml")

    private val prefixes = listOf(
        "action_", "chip_", "label_", "title_", "dialog_title_", "dialog_body_", "snackbar_", "snackbar_action_",
        "error_", "empty_", "hint_title_", "hint_body_", "setting_title_", "setting_summary_", "guide_", "cd_",
    )

    private fun args(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value.replace(Regex("^%"), "%") }.sorted().toList()

    private fun texts(el: Element): List<String> =
        if (el.tagName == "plurals") {
            val items = el.getElementsByTagName("item")
            (0 until items.length).map { items.item(it).textContent }
        } else {
            listOf(el.textContent)
        }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun `every english string has a german translation and no german string is orphaned`() {
        assertEquals("keys missing in values-de", emptySet<String>(), en.keys - de.keys - "app_name")
        assertEquals("keys only in values-de", emptySet<String>(), de.keys - en.keys)
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun `german keeps the format arguments of the english string`() {
        en.forEach { (name, el) ->
            val enArgs = texts(el).flatMap(::args).toSet()
            val deArgs = texts(de[name] ?: return@forEach).flatMap(::args).toSet()
            assertEquals("format arguments of $name", enArgs, deArgs)
        }
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun `every key carries an element prefix`() {
        en.keys.filter { it != "app_name" }.forEach { key ->
            assertTrue("$key has no element prefix", prefixes.any { key.startsWith(it) })
        }
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun `plurals define one and other in both languages`() {
        listOf(en, de).forEach { file ->
            file.filterValues { it.tagName == "plurals" }.forEach { (name, el) ->
                val items = el.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                assertTrue("$name needs one and other", quantities.containsAll(listOf("one", "other")))
            }
        }
    }
}
