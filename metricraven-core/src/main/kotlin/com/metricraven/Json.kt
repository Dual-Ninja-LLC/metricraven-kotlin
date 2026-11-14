package com.metricraven

/**
 * The little JSON the SDK needs (collect bodies, the persisted queue, the server's reply), so the
 * library has no dependencies. Objects decode to maps, arrays to lists, integers to Long, other
 * numbers to Double. Objects encode with sorted keys and null values left out.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun decode(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing characters at ${parser.pos}")
        return value
    }

    /** Whole numbers go out as integers (5, not 5.0), like the Swift SDK. */
    fun number(value: Double): String =
        if (value == Math.rint(value) && Math.abs(value) < 9_007_199_254_740_992.0) value.toLong().toString()
        else value.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Number -> out.append(value.toLong())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for (key in value.keys.map { it.toString() }.sorted()) {
                    val v = value[key] ?: continue
                    if (!first) out.append(',')
                    first = false
                    string(out, key)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at $pos")

        fun value(): Any? {
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[pos] != '"') fail("expected a key")
                val key = string()
                skipWhitespace()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                skipWhitespace()
                out[key] = value()
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWhitespace()
                out.add(value())
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            val out = StringBuilder()
            pos++
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                out.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                pos += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var integer = true
            if (pos < s.length && s[pos] == '.') { integer = false; pos++; while (pos < s.length && s[pos] in '0'..'9') pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integer = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (integer) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("bad number")
        }
    }
}
