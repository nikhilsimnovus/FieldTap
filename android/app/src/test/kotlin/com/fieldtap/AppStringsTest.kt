package com.fieldtap

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class AppStringsTest {

    @Test
    fun appNameAndLimitsStatementAreVerbatim() {
        val strings = readStrings(File("src/main/res/values/strings.xml"))

        assertEquals("5gto6G FieldTap", strings["app_name"])
        assertEquals(LIMITS_STATEMENT, strings["limits_statement"])
    }

    private fun readStrings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val LIMITS_STATEMENT =
            "Reads what Android exposes: cell identity, RSRP/RSRQ/SINR, band, ARFCN, service state, " +
                "plus ping and download tests. It does not decode RRC, NAS, SIB or any layer-3 " +
                "signalling, cannot lock bands or cells, cannot scan operators, and needs no root."
    }
}
