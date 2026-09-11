package com.fieldtap.format

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTextTest {
    private val bs = "\\"

    @Test
    fun rendersWhatPythonJsonDumpsWrites() {
        // Python: json.dumps({"s": ..., "e": {}, "a": [], "n": None, "f": 88.3, "z": 0.0}, indent=2, ensure_ascii=False)
        val value = "a\"b" + bs + "c/d" + ch(0xE9) + ch(0x2028) + ch(0x7F) + ch(0x01) + ch(0x1F) + ch(0x08) + ch(0x0C) + "\n\r\t"
        val tree = JsonObj(
            listOf(
                "s" to JsonStr(value),
                "e" to JsonObj(emptyList()),
                "a" to JsonArr(emptyList()),
                "n" to JsonNul,
                "f" to JsonDec(88.3, 1),
                "z" to JsonDec(0.0, 1),
            ),
        )
        val escaped = "a" + bs + "\"b" + bs + bs + "c/d" + ch(0xE9) + ch(0x2028) + ch(0x7F) +
            bs + "u0001" + bs + "u001f" + bs + "b" + bs + "f" + bs + "n" + bs + "r" + bs + "t"
        val expected = listOf(
            "{",
            "  \"s\": \"$escaped\",",
            "  \"e\": {},",
            "  \"a\": [],",
            "  \"n\": null,",
            "  \"f\": 88.3,",
            "  \"z\": 0.0",
            "}",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, JsonText.render(tree))
    }

    @Test
    fun nestsWithTwoSpacesAndNoTrailingCommas() {
        val tree = JsonObj(
            listOf(
                "o" to JsonObj(
                    listOf(
                        "a" to JsonInt(1),
                        "b" to JsonArr(listOf(JsonBool(true), JsonObj(listOf("c" to JsonStr("d"))), JsonArr(emptyList()))),
                    ),
                ),
                "last" to JsonBool(false),
            ),
        )
        val expected = listOf(
            "{",
            "  \"o\": {",
            "    \"a\": 1,",
            "    \"b\": [",
            "      true,",
            "      {",
            "        \"c\": \"d\"",
            "      },",
            "      []",
            "    ]",
            "  },",
            "  \"last\": false",
            "}",
        ).joinToString("\n", postfix = "\n")
        assertEquals(expected, JsonText.render(tree))
    }

    @Test
    fun rendersTopLevelValuesAndEmptyContainers() {
        assertEquals("{}\n", JsonText.render(JsonObj(emptyList())))
        assertEquals("[]\n", JsonText.render(JsonArr(emptyList())))
        assertEquals("null\n", JsonText.render(JsonNul))
        assertEquals("\"x\"\n", JsonText.render(JsonStr("x")))
        assertEquals("true\n", JsonText.render(JsonBool(true)))
        assertEquals("-9223372036854775808\n", JsonText.render(JsonInt(Long.MIN_VALUE)))
    }

    @Test
    fun decimalsRoundHalfEvenAndAreNeverNegativeZero() {
        assertEquals("88.3\n", JsonText.render(JsonDec(88.3, 1)))
        assertEquals("88.2\n", JsonText.render(JsonDec(88.25, 1)))
        assertEquals("14.0\n", JsonText.render(JsonDec(14.0, 1)))
        assertEquals("4.1\n", JsonText.render(JsonDec(4.135, 1)))
        assertEquals("0.0\n", JsonText.render(JsonDec(-0.04, 1)))
        assertEquals("0.0\n", JsonText.render(JsonDec(-0.0, 1)))
        assertEquals("0.062\n", JsonText.render(JsonDec(0.0625, 3)))
    }

    @Test
    fun nonFiniteDecimalsRenderNull() {
        assertEquals("null\n", JsonText.render(JsonDec(Double.NaN, 1)))
        assertEquals("null\n", JsonText.render(JsonDec(Double.NEGATIVE_INFINITY, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aDecimalWithoutDecimalsIsAProgrammingError() {
        JsonText.render(JsonDec(1.0, 0))
    }

    @Test
    fun quoteEscapesOnlyWhatPythonEscapes() {
        assertEquals("\"a/b\"", JsonText.quote("a/b"))
        assertEquals("\"\"", JsonText.quote(""))
        assertEquals("\"" + bs + "u0000" + bs + "u000b" + bs + "u001f\"", JsonText.quote(ch(0) + ch(0x0B) + ch(0x1F)))
        val raw = ch(0x20) + ch(0x7F) + ch(0x2028) + ch(0xE9) + ch(0x1F6B6)
        assertEquals("\"" + raw + "\"", JsonText.quote(raw))
    }

    @Test
    fun quoteReplacesLoneSurrogates() {
        assertEquals("\"" + ch(0xFFFD) + "x" + ch(0xFFFD) + "\"", JsonText.quote(ch(0xDC00) + "x" + ch(0xD800)))
    }

    @Test
    fun noDefaultLocaleChangesTheOutput() {
        val tree = JsonObj(listOf("pct" to JsonDec(44.7, 1), "n" to JsonInt(1_234_567)))
        val expected = "{\n  \"pct\": 44.7,\n  \"n\": 1234567\n}\n"
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertEquals(tag, expected, JsonText.render(tree))
            }
        }
    }
}
