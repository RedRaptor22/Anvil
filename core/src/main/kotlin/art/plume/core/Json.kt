package art.plume.core

/**
 * A minimal JSON reader and writer.
 *
 * `core` has no dependencies at all, and that is worth keeping: it is what
 * lets the whole engine compile and test on a plain JVM with nothing
 * installed. Pulling in a JSON library for one file format would trade that
 * away, and the format here is a few thousand numbers in flat arrays — the
 * part of JSON this needs is small.
 *
 * Numbers are all doubles, as they are in JavaScript, so a document written by
 * either build reads the same on the other.
 */
sealed class JsonValue {
    fun asObject(): JsonObject? = this as? JsonObject
    fun asArray(): JsonArray? = this as? JsonArray
    fun asDouble(): Double? = (this as? JsonNumber)?.value
    fun asInt(): Int? = (this as? JsonNumber)?.value?.toInt()
    fun asString(): String? = (this as? JsonString)?.value
    fun asBoolean(): Boolean? = (this as? JsonBool)?.value

    fun write(): String = StringBuilder().also { writeTo(it) }.toString()

    internal abstract fun writeTo(sb: StringBuilder)
}

object JsonNull : JsonValue() {
    override fun writeTo(sb: StringBuilder) { sb.append("null") }
}

class JsonBool(val value: Boolean) : JsonValue() {
    override fun writeTo(sb: StringBuilder) { sb.append(if (value) "true" else "false") }
}

class JsonNumber(val value: Double) : JsonValue() {
    override fun writeTo(sb: StringBuilder) {
        /*
         * NaN and infinity are not JSON. They should never reach here, but one
         * bad number would otherwise write a file neither build can open — so
         * they become 0 rather than corrupting the whole document.
         */
        if (!value.isFinite()) { sb.append('0'); return }
        // integral values write without a trailing .0, as JavaScript does
        if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) {
            sb.append(value.toLong())
        } else {
            sb.append(value.toString())
        }
    }
}

class JsonString(val value: String) : JsonValue() {
    override fun writeTo(sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }
}

class JsonArray(val items: MutableList<JsonValue> = ArrayList()) : JsonValue() {
    val size: Int get() = items.size
    operator fun get(i: Int): JsonValue = items[i]

    fun add(v: JsonValue): JsonArray { items.add(v); return this }
    fun add(v: Double): JsonArray = add(JsonNumber(v))
    fun add(v: String): JsonArray = add(JsonString(v))

    /** The doubles in this array, with nulls read as [orElse]. */
    fun doubles(orElse: Double = 0.0): DoubleArray =
        DoubleArray(items.size) { items[it].asDouble() ?: orElse }

    override fun writeTo(sb: StringBuilder) {
        sb.append('[')
        for (i in items.indices) {
            if (i > 0) sb.append(',')
            items[i].writeTo(sb)
        }
        sb.append(']')
    }

    companion object {
        fun of(values: DoubleArray): JsonArray =
            JsonArray(values.mapTo(ArrayList()) { JsonNumber(it) as JsonValue })

        fun of(values: List<Double>): JsonArray =
            JsonArray(values.mapTo(ArrayList()) { JsonNumber(it) as JsonValue })
    }
}

class JsonObject(val entries: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue() {

    operator fun get(key: String): JsonValue? = entries[key]
    operator fun contains(key: String): Boolean = entries.containsKey(key)

    fun put(key: String, v: JsonValue): JsonObject { entries[key] = v; return this }
    fun put(key: String, v: Double): JsonObject = put(key, JsonNumber(v))
    fun put(key: String, v: Int): JsonObject = put(key, JsonNumber(v.toDouble()))
    fun put(key: String, v: String): JsonObject = put(key, JsonString(v))
    fun put(key: String, v: Boolean): JsonObject = put(key, JsonBool(v))
    fun putNull(key: String): JsonObject = put(key, JsonNull)

    fun obj(key: String): JsonObject? = entries[key]?.asObject()
    fun arr(key: String): JsonArray? = entries[key]?.asArray()
    fun str(key: String): String? = entries[key]?.asString()
    fun num(key: String, orElse: Double): Double = entries[key]?.asDouble() ?: orElse
    fun int(key: String, orElse: Int): Int = entries[key]?.asInt() ?: orElse
    fun bool(key: String, orElse: Boolean): Boolean = entries[key]?.asBoolean() ?: orElse

    override fun writeTo(sb: StringBuilder) {
        sb.append('{')
        var first = true
        for ((k, v) in entries) {
            if (!first) sb.append(',')
            first = false
            JsonString(k).writeTo(sb)
            sb.append(':')
            v.writeTo(sb)
        }
        sb.append('}')
    }
}

/** Thrown when a document cannot be read at all. */
class JsonException(message: String) : Exception(message)

object Json {

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.value()
        p.skipWhitespace()
        if (!p.done) throw JsonException("trailing text at ${p.at}")
        return v
    }

    private class Parser(val s: String) {
        var at = 0
        val done: Boolean get() = at >= s.length

        fun skipWhitespace() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun value(): JsonValue {
            if (done) throw JsonException("unexpected end of document")
            return when (s[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> JsonString(string())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> number()
            }
        }

        fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, at)) throw JsonException("bad literal at $at")
            at += word.length
            return v
        }

        fun obj(): JsonObject {
            expect('{')
            val o = JsonObject()
            skipWhitespace()
            if (peek() == '}') { at++; return o }
            while (true) {
                skipWhitespace()
                val k = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                o.entries[k] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return o }
                    else -> throw JsonException("expected , or } at $at")
                }
            }
        }

        fun array(): JsonArray {
            expect('[')
            val a = JsonArray()
            skipWhitespace()
            if (peek() == ']') { at++; return a }
            while (true) {
                skipWhitespace()
                a.items.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return a }
                    else -> throw JsonException("expected , or ] at $at")
                }
            }
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (done) throw JsonException("unterminated string")
                val c = s[at++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (done) throw JsonException("unterminated escape")
                        when (val e = s[at++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (at + 4 > s.length) throw JsonException("short unicode escape")
                                sb.append(s.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> throw JsonException("bad escape at $at: $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): JsonNumber {
            val start = at
            if (peek() == '-' || peek() == '+') at++
            while (at < s.length && (s[at].isDigit() || s[at] == '.' ||
                    s[at] == 'e' || s[at] == 'E' || s[at] == '-' || s[at] == '+')
            ) at++
            val text = s.substring(start, at)
            return JsonNumber(
                text.toDoubleOrNull() ?: throw JsonException("bad number at $start: $text"),
            )
        }

        fun peek(): Char = if (done) ' ' else s[at]

        fun expect(c: Char) {
            if (peek() != c) throw JsonException("expected $c at $at")
            at++
        }
    }
}
