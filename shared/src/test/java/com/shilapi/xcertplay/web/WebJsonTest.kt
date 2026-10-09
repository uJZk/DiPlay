package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebJsonTest {
    @Test fun parsesTheControlBody() {
        val body = WebJson.parse(
            """ {"c":"123456","s":"abcdefghijklmnop","q":12,"e":[{"k":"t","p":[[0,0.25,1,1],[1,0.5,0.75,0]]},""" +
                """{"k":"vp","w":1182,"h":920,"cw":773,"ch":601,"dpr":1.53},{"k":"kf"}]} """,
        ) as Map<*, *>
        assertEquals("123456", body["c"])
        assertEquals(12L, body["q"])
        val events = body["e"] as List<*>
        assertEquals(listOf(listOf(0L, 0.25, 1L, 1L), listOf(1L, 0.5, 0.75, 0L)), (events[0] as Map<*, *>)["p"])
        assertEquals(1.53, (events[1] as Map<*, *>)["dpr"])
        assertEquals(mapOf("k" to "kf"), events[2])
    }

    @Test fun parsesScalarsAndEscapes() {
        assertEquals(listOf(true, false, null, -0L, -1.5e3, 9_007_199_254_740_993L), WebJson.parse("[true,false,null,-0,-1.5e3,9007199254740993]"))
        assertEquals("a\"b\\c/d\b\u000c\n\r\t\u00e9\ud83d\ude00", WebJson.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\""))
        assertEquals(1.0e30, WebJson.parse("1000000000000000000000000000000"))
        assertEquals(emptyMap<String, Any?>(), WebJson.parse("{ }"))
        assertEquals(emptyList<Any?>(), WebJson.parse("[ ]"))
    }

    @Test fun rejectsMalformedInput() {
        for (text in listOf(
            "", "{", "[1,]", "{\"a\":}", "{\"a\" 1}", "{a:1}", "01", "1.", "1e", "-", "+1", ".5", "tru", "nul",
            "\"open", "\"bad \\x escape\"", "\"\\u12g4\"", "\"\\u+123\"", "\"tab\there\"", "{} {}", "[1] x",
            "[".repeat(40) + "]".repeat(40),
        )) {
            assertThrows(text, WebJson.SyntaxException::class.java) { WebJson.parse(text) }
        }
    }

    @Test fun writesCompactJsonWithEscapes() {
        assertEquals(
            """{"s":"q\"\\\n\u0001é","n":[1,2.5,null,null],"b":true,"z":null,"m":{"x":-3}}""",
            WebJson.write(
                linkedMapOf(
                    "s" to "q\"\\\n\u0001é",
                    "n" to listOf(1, 2.5, Double.NaN, Double.POSITIVE_INFINITY),
                    "b" to true,
                    "z" to null,
                    "m" to mapOf("x" to -3L),
                ),
            ),
        )
        assertEquals("[\"x\",1.0E10]", WebJson.write(listOf("x", 1.0e10)))
        assertEquals(mapOf("k" to listOf(1L, "two")), WebJson.parse(WebJson.write(mapOf("k" to listOf(1, "two")))))
    }
}
