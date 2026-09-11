package com.fieldtap.ui.setup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the setup screens' string file. Several workstreams add string files to the same module, and a name defined
 * twice fails the resource merge, so every setup name carries a setup prefix and none may appear elsewhere. Runs from
 * the app module directory, like AppStringsTest.
 */
class SetupStringsTest {
    private val valuesDir = File("src/main/res/values")
    private val setupFile = File(valuesDir, "strings_setup_ui.xml")
    private val setupSources = listOf("onboarding", "readiness", "probe", "settings", "about", "setup")
        .map { File("src/main/kotlin/com/fieldtap/ui/$it") }

    @Test
    fun everySetupNameHasASetupPrefixAndIsDefinedOnce() {
        val names = namesIn(setupFile)
        assertTrue(names.isNotEmpty())
        assertEquals(names.distinct(), names)
        names.forEach { name -> assertTrue(name, PREFIXES.any { name.startsWith(it) }) }
    }

    @Test
    fun noSetupNameIsDefinedInAnotherStringFile() {
        val setupNames = namesIn(setupFile).toSet()
        val others = valuesDir.listFiles { file -> file.extension == "xml" && file.name != setupFile.name }.orEmpty()
        others.forEach { file ->
            val clash = namesIn(file).filter { it in setupNames }
            assertTrue("${file.name} also defines $clash", clash.isEmpty())
        }
    }

    @Test
    fun everyStringTheSetupScreensUseExistsAndEverySetupStringIsUsed() {
        val defined = valuesDir.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { namesIn(it) }.toSet()
        val used = setupSources.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file -> R_STRING.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
        assertTrue(used.isNotEmpty())
        assertEquals(emptySet<String>(), used - defined)

        val unused = namesIn(setupFile).toSet() - used
        assertEquals(emptySet<String>(), unused)
    }

    @Test
    fun placeholdersAreNumberedAndPercentSignsEscaped() {
        stringsIn(setupFile).forEach { (name, text) ->
            val withoutPlaceholders = text.replace(PLACEHOLDER, "").replace("%%", "")
            assertFalse("$name has a bare %", withoutPlaceholders.contains('%'))
            assertFalse("$name has an ASCII apostrophe, which aapt rejects unescaped", text.contains('\''))
        }
    }

    @Test
    fun aboutSaysPlainlyThatThereIsNoAccountYetAndOneIsComing() {
        val strings = stringsIn(setupFile).toMap()
        val account = strings.getValue("about_no_account")
        assertTrue(account, account.startsWith("No account yet."))
        assertTrue(account, account.contains("An account is coming"))
        assertEquals("5gto6g", strings["about_publisher"])
        assertEquals("Free", strings["about_price"])
    }

    @Test
    fun theLimitsStatementIsNotCopiedIntoTheSetupStrings() {
        stringsIn(setupFile).forEach { (name, text) ->
            assertFalse(name, text.contains("layer-3 signalling"))
        }
    }

    private fun namesIn(file: File): List<String> = stringsIn(file).map { it.first }

    private fun stringsIn(file: File): List<Pair<String, String>> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        val PREFIXES = listOf("setup_", "disclosure_", "permissions_", "readiness_", "soak_", "probe_", "settings_", "about_")
        val R_STRING = Regex("""R\.string\.([A-Za-z0-9_]+)""")
        val PLACEHOLDER = Regex("""%[1-9]\$[sd]""")
    }
}
