package com.fieldtap.ui.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The words of the Live, Sessions and Session detail screens, read from
 * `src/main/res/values/strings_session_ui.xml` (unit tests run in the module directory). Some of these
 * mistakes fail only the resource build, others only at run time (a format argument that does not match),
 * and wording rules fail nowhere; these checks catch all three on a JVM.
 */
class SessionStringsTest {
    private val valuesDir = File("src/main/res/values")
    private val sessionFile = File(valuesDir, "strings_session_ui.xml")

    @Test
    fun namesAreUniqueAndTextsNotEmpty() {
        val resources = resources(sessionFile)
        assertTrue("no resources read from ${sessionFile.path}", resources.isNotEmpty())
        val duplicates = resources.groupBy { it.kind to it.name }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate names", emptySet<Pair<String, String>>(), duplicates)
        for (resource in resources) {
            assertTrue("${resource.name} has no name", resource.name.isNotEmpty())
            assertTrue("${resource.name} is empty", resource.texts.isNotEmpty() && resource.texts.all { it.isNotBlank() })
        }
    }

    @Test
    fun noNameIsAlsoDefinedInAnotherValuesFile() {
        val ours = resources(sessionFile).map { it.kind to it.name }.toSet()
        val others = valuesDir.listFiles { file -> file.isFile && file.name.endsWith(".xml") && file.name != sessionFile.name }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no other values files found in ${valuesDir.path}", others.isNotEmpty())
        for (other in others) {
            val clashes = resources(other).map { it.kind to it.name }.filter { it in ours }
            assertEquals("names also defined in ${other.name}; the resource merge would fail", emptyList<Pair<String, String>>(), clashes)
        }
    }

    @Test
    fun everyApostropheIsEscaped() {
        for (resource in resources(sessionFile)) {
            for (text in resource.texts) {
                val quoted = text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")
                if (!quoted && UNESCAPED_APOSTROPHE.containsMatchIn(text)) {
                    fail("${resource.name}: unescaped apostrophe in \"$text\"")
                }
            }
        }
    }

    @Test
    fun formatArgumentsArePositionalStringsOrWholeNumbersCountedFromOne() {
        for (resource in resources(sessionFile)) {
            val perText = resource.texts.map { formatArguments(resource.name, it) }
            for (arguments in perText) {
                val indices = arguments.map { it.first }.toSet()
                if (indices.isEmpty()) continue
                assertEquals("${resource.name}: argument numbers must run from 1 without gaps", (1..indices.max()).toSet(), indices)
                for ((index, conversions) in arguments.groupBy({ it.first }, { it.second })) {
                    assertEquals("${resource.name}: argument $index is used with two conversions", 1, conversions.toSet().size)
                }
            }
            if (resource.kind == "plurals") {
                assertEquals("${resource.name}: every quantity must take the same arguments", 1, perText.map { it.toSet() }.toSet().size)
            }
        }
    }

    @Test
    fun pluralsHaveOneAndOther() {
        val plurals = pluralQuantities(sessionFile)
        assertTrue("no plurals read", plurals.isNotEmpty())
        for ((name, quantities) in plurals) {
            assertTrue("$name lacks quantity one", "one" in quantities)
            assertTrue("$name lacks quantity other", "other" in quantities)
        }
    }

    @Test
    fun noWordingSuggestsDecodingOrSignalling() {
        for (resource in resources(sessionFile)) {
            for (text in resource.texts) {
                FORBIDDEN_WORDING.find(text)?.let { fail("${resource.name}: \"${it.value}\" suggests decoding or signalling in \"$text\"") }
            }
        }
    }

    @Test
    fun theSharedWordsTheDesignSystemReadsAreDefined() {
        val strings = resources(sessionFile).filter { it.kind == "string" }.associate { it.name to it.texts.single() }
        for (name in listOf("quality_excellent", "quality_good", "quality_fair", "quality_poor", "quality_unknown")) {
            assertTrue("$name is missing", name in strings)
        }
        assertEquals("%1\$s s old", strings["age_old"])
        assertEquals("10 s cadence", strings["live_cadence_long"])
    }

    private data class Resource(val kind: String, val name: String, val texts: List<String>)

    private fun resources(file: File): List<Resource> = elements(file).mapNotNull { element ->
        when (element.tagName) {
            "string" -> Resource("string", element.getAttribute("name"), listOf(element.textContent))
            "plurals" -> {
                val items = element.getElementsByTagName("item")
                Resource("plurals", element.getAttribute("name"), (0 until items.length).map { items.item(it).textContent })
            }
            else -> null
        }
    }

    private fun pluralQuantities(file: File): Map<String, Set<String>> = elements(file)
        .filter { it.tagName == "plurals" }
        .associate { element ->
            val items = element.getElementsByTagName("item")
            element.getAttribute("name") to (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
        }

    private fun elements(file: File): List<Element> {
        assertTrue("missing ${file.path}; run the tests from the app module directory", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val children = root.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    /** The `%n$s` and `%n$d` arguments of [text], as (n, conversion); any other `%` except `%%` fails. */
    private fun formatArguments(name: String, text: String): List<Pair<Int, Char>> {
        val found = mutableListOf<Pair<Int, Char>>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '%') {
                index += 1
                continue
            }
            if (text.startsWith("%%", index)) {
                index += 2
                continue
            }
            val match = POSITIONAL_ARGUMENT.find(text, index)?.takeIf { it.range.first == index }
                ?: throw AssertionError("$name: \"$text\" has a % that is neither %% nor a positional %n\$s or %n\$d")
            found += match.groupValues[1].toInt() to match.groupValues[2].single()
            index = match.range.last + 1
        }
        return found
    }

    private companion object {
        val POSITIONAL_ARGUMENT = Regex("%([1-9][0-9]*)\\$([sd])")
        val UNESCAPED_APOSTROPHE = Regex("(?<!\\\\)'")
        val FORBIDDEN_WORDING = Regex("\\b(decod\\w*|signall?ing|handovers?|hand-?offs?|rrc|nas|sib[0-9]*|layer[ -]?3)\\b", RegexOption.IGNORE_CASE)
    }
}
