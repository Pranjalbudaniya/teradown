package com.tera.down.feature.resolver

/**
 * Lightweight, zero-dependency pure Kotlin JSON object wrapper.
 * Decouples JSON parsing from Android framework stubs (org.json.JSONObject) so that
 * unit tests run offline on JVM without "Method not mocked" runtime exceptions.
 */
class SimpleJsonObject(val map: Map<String, Any?>) {

    fun optInt(key: String, default: Int = 0): Int {
        val v = map[key] ?: return default
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    fun optLong(key: String, default: Long = 0L): Long {
        val v = map[key] ?: return default
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    fun optString(key: String, default: String = ""): String {
        val v = map[key] ?: return default
        return v.toString()
    }

    fun optArray(key: String): List<SimpleJsonObject> {
        val list = map[key] as? List<*> ?: return emptyList()
        return list.mapNotNull {
            if (it is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                SimpleJsonObject(it as Map<String, Any?>)
            } else null
        }
    }

    fun has(key: String): Boolean = map.containsKey(key)
}

/**
 * Pure Kotlin recursive descent JSON parser.
 */
object SimpleJsonParser {

    fun parse(raw: String): SimpleJsonObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val parser = Lexer(trimmed)
        val result = parser.parseValue()
        if (result is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return SimpleJsonObject(result as Map<String, Any?>)
        }
        return null
    }

    fun parseAny(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parser = Lexer(trimmed)
        return parser.parseValue()
    }

    private class Lexer(private val src: String) {
        private var idx = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= src.length) return null

            return when (val ch = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            idx++ // consume '{'
            skipWhitespace()

            while (idx < src.length && src[idx] != '}') {
                skipWhitespace()
                if (src[idx] != '"') break
                val key = parseString()
                skipWhitespace()
                if (idx < src.length && src[idx] == ':') {
                    idx++ // consume ':'
                }
                skipWhitespace()
                val value = parseValue()
                map[key] = value

                skipWhitespace()
                if (idx < src.length && src[idx] == ',') {
                    idx++ // consume ','
                }
                skipWhitespace()
            }

            if (idx < src.length && src[idx] == '}') {
                idx++ // consume '}'
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = mutableListOf<Any?>()
            idx++ // consume '['
            skipWhitespace()

            while (idx < src.length && src[idx] != ']') {
                skipWhitespace()
                val value = parseValue()
                list.add(value)

                skipWhitespace()
                if (idx < src.length && src[idx] == ',') {
                    idx++ // consume ','
                }
                skipWhitespace()
            }

            if (idx < src.length && src[idx] == ']') {
                idx++ // consume ']'
            }
            return list
        }

        private fun parseString(): String {
            idx++ // consume opening '"'
            val sb = java.lang.StringBuilder()
            while (idx < src.length) {
                val ch = src[idx++]
                if (ch == '"') {
                    return sb.toString()
                } else if (ch == '\\' && idx < src.length) {
                    val esc = src[idx++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 <= src.length) {
                                val hex = src.substring(idx, idx + 4)
                                idx += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = idx
            if (src[idx] == '-') idx++
            while (idx < src.length && (src[idx] in '0'..'9' || src[idx] == '.' || src[idx] == 'e' || src[idx] == 'E' || src[idx] == '+')) {
                idx++
            }
            val numStr = src.substring(start, idx)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDoubleOrNull() ?: 0.0
            } else {
                numStr.toLongOrNull() ?: 0L
            }
        }

        private fun parseBoolean(): Boolean {
            return if (src.startsWith("true", idx)) {
                idx += 4
                true
            } else if (src.startsWith("false", idx)) {
                idx += 5
                false
            } else {
                idx++
                false
            }
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", idx)) {
                idx += 4
            } else {
                idx++
            }
            return null
        }

        private fun skipWhitespace() {
            while (idx < src.length && (src[idx] == ' ' || src[idx] == '\t' || src[idx] == '\n' || src[idx] == '\r')) {
                idx++
            }
        }
    }
}
