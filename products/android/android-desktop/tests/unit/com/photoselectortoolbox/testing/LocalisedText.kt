package com.photoselectortoolbox.testing

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Frameduel's wording resolved from its own resource files (`res/values/strings.xml` and the
 * German twin) on the plain JVM, so a behaviour test can say "the message names the folder"
 * without re-typing the English sentence (`[USER 2026-11-12]` decision #34), with or without
 * Robolectric. It reads the files the app ships, maps ids to names through `R`, and formats the
 * way `Resources.getString` does; English and German plurals both pick `one` for 1 and `other`
 * otherwise.
 */
fun UiText.en(): String = LocalisedStrings.EN.resolve(this)

/** The same text in German (`values-de`). */
fun UiText.de(): String = LocalisedStrings.DE.resolve(this)

/** A string resource in English. */
fun str(@StringRes id: Int, vararg args: Any): String = LocalisedStrings.EN.resolve(UiText.Res(id, args.toList()))

/** The same string in German. */
fun strDe(@StringRes id: Int, vararg args: Any): String = LocalisedStrings.DE.resolve(UiText.Res(id, args.toList()))

/** A plural resource in English; with no [args] the count is the only argument. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalisedStrings.EN.resolve(UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList()))

class LocalisedStrings private constructor(dir: String, private val locale: Locale) {

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        listOf("strings.xml", "formats.xml").forEach { name -> read(resFile("$dir/$name")) }
    }

    fun resolve(text: UiText): String = when (text) {
        is UiText.Raw -> text.value
        is UiText.Res -> format(strings.getValue(nameOf(STRING, text.id)), text.args)
        is UiText.Plural -> {
            val forms = plurals.getValue(nameOf(PLURALS, text.id))
            format(forms[if (text.count == 1) "one" else "other"] ?: forms.getValue("other"), text.args)
        }
        is UiText.Date -> DateTimeFormatter.ofPattern(strings.getValue(nameOf(STRING, text.pattern)), locale).format(text.value)
        is UiText.Joined -> text.parts.map(::resolve).filter { it.isNotEmpty() }.joinToString(text.separator)
    }

    private fun format(template: String, args: List<Any>): String {
        if (args.isEmpty()) return template
        val resolved = args.map { (it as? UiText)?.let(::resolve) ?: it }.toTypedArray()
        return String.format(locale, template, *resolved)
    }

    private fun read(file: File?) {
        file ?: return
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> strings[name] = unescape(el.textContent)
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    companion object {
        val EN by lazy { LocalisedStrings("values", Locale.ENGLISH) }
        val DE by lazy { LocalisedStrings("values-de", Locale.GERMAN) }

        private const val STRING = "string"
        private const val PLURALS = "plurals"
        private val names = mutableMapOf<Pair<String, Int>, String>()

        private fun nameOf(kind: String, id: Int): String = synchronized(names) {
            names.getOrPut(kind to id) {
                val cls = Class.forName("${R::class.java.name}\$$kind")
                cls.fields.firstOrNull { it.getInt(null) == id }?.name ?: error("no R.$kind with id $id")
            }
        }

        private fun resFile(path: String): File? =
            listOf(File("res/$path"), File("products/android/android-desktop/res/$path")).firstOrNull { it.exists() }

        /** What aapt does to a resource string: a quoted run kept whole, escapes resolved. */
        internal fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        when (val n = raw[i + 1]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                            else -> out.append(n)
                        }
                        i += 2
                        continue
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
                    else -> out.append(c)
                }
                i++
            }
            return out.toString().trim()
        }
    }
}
