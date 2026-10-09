package com.shilapi.xcertplay.web

/**
 * The JSON the browser link needs: control bodies in, status and stream config out. Objects become
 * [LinkedHashMap]s, arrays [List]s, integers [Long]s and other numbers [Double]s.
 * (`org.json` is only a stub on the JVM, and `:shared` tests run without Robolectric.)
 */
internal object WebJson {
    class SyntaxException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? = Reader(text).run {
        val value = value(0)
        skipWhitespace()
        if (position != text.length) fail("trailing data")
        value
    }

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Number -> value.toDouble().let { if (it.isFinite()) out.append(it) else out.append("null") }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    appendString(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, item -> if (index > 0) out.append(','); append(out, item) }
                out.append(']')
            }
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
                character < ' ' -> out.append("\\u%04x".format(character.code))
                else -> out.append(character)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        var position = 0

        fun fail(reason: String): Nothing = throw SyntaxException("$reason at $position")

        fun skipWhitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            skipWhitespace()
            if (position >= text.length) fail("unexpected end")
            return when (text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++
            skipWhitespace()
            if (peek() == '}') { position++; return result }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a key")
                val key = string()
                skipWhitespace()
                expect(':')
                result[key] = value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("expected , or }")
                }
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            val result = ArrayList<Any?>()
            position++
            skipWhitespace()
            if (peek() == ']') { position++; return result }
            while (true) {
                result += value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                val character = next()
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> when (val escaped = next()) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (position + 4 > text.length) fail("short unicode escape")
                            val hex = text.substring(position, position + 4)
                            if (!hex.all { it.digitToIntOrNull(16) != null }) fail("bad unicode escape")
                            result.append(hex.toInt(16).toChar())
                            position += 4
                        }
                        else -> fail("bad escape")
                    }
                    character < ' ' -> fail("control character in string")
                    else -> result.append(character)
                }
            }
        }

        private fun number(): Any {
            val start = position
            if (peek() == '-') position++
            when {
                peek() == '0' -> position++
                peek() in '1'..'9' -> while (peek() in '0'..'9') position++
                else -> fail("unexpected character")
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                position++
                if (peek() !in '0'..'9') fail("bad fraction")
                while (peek() in '0'..'9') position++
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek() !in '0'..'9') fail("bad exponent")
                while (peek() in '0'..'9') position++
            }
            val digits = text.substring(start, position)
            return (if (integral) digits.toLongOrNull() else null) ?: digits.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) fail("unexpected character")
            position += word.length
            return value
        }

        private fun expect(character: Char) {
            if (next() != character) fail("expected $character")
        }

        private fun peek(): Char = if (position < text.length) text[position] else END

        private fun next(): Char {
            if (position >= text.length) fail("unexpected end")
            return text[position++]
        }
    }

    private const val MAX_DEPTH = 16
    private const val END = '\u0000'
}
