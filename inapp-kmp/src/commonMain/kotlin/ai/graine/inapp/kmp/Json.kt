package ai.graine.inapp.kmp

/**
 * JSON, as the bridge to a platform SDK carries it — free-form values (traits,
 * variables, a screen's data, an action's arguments and result) cross as JSON
 * text, so both platforms hand the shared code exactly the same shapes:
 * objects are `Map<String, Any?>` in key order, arrays `List<Any?>`, whole
 * numbers `Long`, other numbers `Double`, and `true`, `false`, `null`, strings.
 *
 * Small on purpose: the SDK depends on nothing but coroutines and datetime.
 */
internal object Json {

    // ── writing ───────────────────────────────────────────────────────────

    /**
     * Any value as JSON. Maps (keys as text), lists, arrays, numbers, booleans,
     * strings and null as themselves; a number that is not finite is null (JSON
     * has none); anything else is its text, as the native SDKs write it.
     */
    fun write(value: Any?): String = buildString { put(this, value) }

    private fun put(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> number(out, value)
            is Float -> number(out, value.toDouble())
            is Number -> out.append(value.toLong().toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    put(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> list(out, value.iterator())
            is Array<*> -> list(out, value.iterator())
            is Enum<*> -> quote(out, value.name.lowercase())
            else -> quote(out, value.toString())
        }
    }

    private fun list(out: StringBuilder, items: Iterator<*>) {
        out.append('[')
        var first = true
        for (v in items) {
            if (!first) out.append(',')
            first = false
            put(out, v)
        }
        out.append(']')
    }

    private fun number(out: StringBuilder, d: Double) {
        when {
            !d.isFinite() -> out.append("null")
            // A whole number is written whole: 3, not 3.0 (and read back as a Long).
            d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.007199254740992E15 -> out.append(d.toLong().toString())
            else -> out.append(d.toString())
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c.code < 0x20 || c == ' ' || c == ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    // ── reading ───────────────────────────────────────────────────────────

    /** The value in [text]. Throws IllegalArgumentException on anything that is not JSON. */
    fun read(text: String): Any? {
        val p = Parser(text)
        p.space()
        val v = p.value()
        p.space()
        if (!p.done) p.fail("unexpected text after the value")
        return v
    }

    /** An object from [text]; null for blank text, anything that is not an object, or text that is not JSON. */
    fun obj(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { read(text) }.getOrNull() as? Map<String, Any?>
    }

    /** An array from [text]; null when it is not one. */
    fun array(text: String?): List<Any?>? {
        if (text.isNullOrBlank()) return null
        return runCatching { read(text) }.getOrNull() as? List<Any?>
    }

    private class Parser(private val s: String) {
        private var i = 0
        val done: Boolean get() = i >= s.length

        fun fail(why: String): Nothing = throw IllegalArgumentException("Not JSON at $i: $why")

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (done) fail("the text ended")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("expected $w")
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            space()
            if (!done && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (done || s[i] != '"') fail("expected a key")
                val k = str()
                space()
                if (done || s[i] != ':') fail("expected ':'")
                i++
                space()
                out[k] = value()
                space()
                if (done) fail("the object never closed")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            space()
            if (!done && s[i] == ']') { i++; return out }
            while (true) {
                space()
                out.add(value())
                space()
                if (done) fail("the array never closed")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            val out = StringBuilder()
            i++
            while (true) {
                if (done) fail("the string never closed")
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (done) fail("the string never closed")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("a short \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("a bad \\u escape")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> fail("a bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var whole = true
            if (i < s.length && s[i] == '.') {
                whole = false
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                whole = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (text == "-" || text.isEmpty()) fail("a bad number")
            if (whole) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("a bad number '$text'")
        }
    }
}
