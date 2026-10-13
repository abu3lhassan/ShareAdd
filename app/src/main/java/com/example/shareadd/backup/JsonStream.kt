package com.example.shareadd.backup

import java.io.Reader
import java.io.Writer

/** Raised for any malformed, truncated or unexpected JSON. */
class JsonFormatException(message: String) : Exception(message)

/**
 * Minimal streaming JSON writer (pure Kotlin, no Android classes) so large backups are written
 * piece by piece instead of being built in memory.
 */
class JsonOut(private val out: Writer) {
    private val stack = ArrayList<Int>()

    fun beginObject(): JsonOut { beforeValue(); out.write("{"); stack.add(OBJ_EMPTY); return this }
    fun endObject(): JsonOut { check(stack.lastOrNull() == OBJ_EMPTY || stack.lastOrNull() == OBJ_NONEMPTY); stack.removeAt(stack.size - 1); out.write("}"); return this }
    fun beginArray(): JsonOut { beforeValue(); out.write("["); stack.add(ARR_EMPTY); return this }
    fun endArray(): JsonOut { check(stack.lastOrNull() == ARR_EMPTY || stack.lastOrNull() == ARR_NONEMPTY); stack.removeAt(stack.size - 1); out.write("]"); return this }

    fun name(name: String): JsonOut {
        val top = stack.lastOrNull()
        check(top == OBJ_EMPTY || top == OBJ_NONEMPTY) { "name outside object" }
        if (top == OBJ_NONEMPTY) out.write(",")
        writeString(name)
        out.write(":")
        stack[stack.size - 1] = OBJ_NAME
        return this
    }

    fun value(text: String?): JsonOut { beforeValue(); if (text == null) out.write("null") else writeString(text); return this }
    fun value(number: Long): JsonOut { beforeValue(); out.write(number.toString()); return this }
    fun value(number: Int): JsonOut = value(number.toLong())
    fun value(flag: Boolean): JsonOut { beforeValue(); out.write(if (flag) "true" else "false"); return this }

    fun field(name: String, text: String?) = name(name).value(text)
    fun field(name: String, number: Long) = name(name).value(number)
    fun field(name: String, number: Int) = name(name).value(number)
    fun field(name: String, flag: Boolean) = name(name).value(flag)

    fun flush() = out.flush()

    private fun beforeValue() {
        if (stack.isEmpty()) return
        when (stack.last()) {
            ARR_EMPTY -> stack[stack.size - 1] = ARR_NONEMPTY
            ARR_NONEMPTY -> out.write(",")
            OBJ_NAME -> stack[stack.size - 1] = OBJ_NONEMPTY
            else -> throw IllegalStateException("value without a name")
        }
    }

    private fun writeString(text: String) {
        out.write("\"")
        for (c in text) {
            when {
                c == '"' -> out.write("\\\"")
                c == '\\' -> out.write("\\\\")
                c == '\n' -> out.write("\\n")
                c == '\r' -> out.write("\\r")
                c == '\t' -> out.write("\\t")
                c == '\b' -> out.write("\\b")
                c == '\u000C' -> out.write("\\f")
                c < ' ' || c == ' ' || c == ' ' -> out.write("\\u%04x".format(c.code))
                else -> out.write(c.code)
            }
        }
        out.write("\"")
    }

    private companion object {
        const val ARR_EMPTY = 1
        const val ARR_NONEMPTY = 2
        const val OBJ_EMPTY = 3
        const val OBJ_NAME = 4
        const val OBJ_NONEMPTY = 5
    }
}

enum class JsonToken { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/**
 * Minimal streaming JSON pull reader (pure Kotlin). Any syntax error or early end of input throws
 * [JsonFormatException], so a truncated backup is always detected.
 */
class JsonIn(reader: Reader, private val maxStringLength: Int = 128 * 1024 * 1024) {
    private val input = reader
    private val buffer = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0
    private val stack = ArrayList<Int>()
    private var topLevelDone = false
    private var peeked: JsonToken? = null
    private var peekedText: String = ""

    fun peek(): JsonToken {
        peeked?.let { return it }
        val token = doPeek()
        peeked = token
        return token
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != JsonToken.END_DOCUMENT
    }

    fun beginObject() { expect(JsonToken.BEGIN_OBJECT); stack.add(OBJ_EMPTY) }
    fun endObject() { expect(JsonToken.END_OBJECT); stack.removeAt(stack.size - 1); afterValue() }
    fun beginArray() { expect(JsonToken.BEGIN_ARRAY); stack.add(ARR_EMPTY) }
    fun endArray() { expect(JsonToken.END_ARRAY); stack.removeAt(stack.size - 1); afterValue() }
    fun nextName(): String { expect(JsonToken.NAME); return peekedText }
    fun nextString(): String { expect(JsonToken.STRING); afterValue(); return peekedText }
    fun nextBoolean(): Boolean { expect(JsonToken.BOOLEAN); afterValue(); return peekedText == "true" }
    fun nextNull() { expect(JsonToken.NULL); afterValue() }

    fun nextLong(): Long {
        expect(JsonToken.NUMBER)
        afterValue()
        return peekedText.toLongOrNull()
            ?: peekedText.toDoubleOrNull()?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
            ?: throw JsonFormatException("not an integer: $peekedText")
    }

    /** Reads any scalar as text (lenient, like org.json opt*). Objects/arrays are an error. */
    fun nextScalarAsText(): String = when (peek()) {
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> { expect(JsonToken.NUMBER); afterValue(); peekedText }
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> { nextNull(); "" }
        else -> throw JsonFormatException("expected a value, found ${peek()}")
    }

    fun skipValue() {
        when (peek()) {
            JsonToken.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            JsonToken.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> nextScalarAsText()
            else -> throw JsonFormatException("cannot skip ${peek()}")
        }
    }

    /** Requires that nothing but whitespace follows the top-level value. */
    fun endDocument() {
        if (peek() != JsonToken.END_DOCUMENT) throw JsonFormatException("unexpected data after the end")
    }

    private fun expect(token: JsonToken) {
        val t = peek()
        if (t != token) throw JsonFormatException("expected $token but found $t")
        peeked = null
    }

    private fun afterValue() {
        if (stack.isEmpty()) topLevelDone = true
    }

    private fun doPeek(): JsonToken {
        if (stack.isEmpty()) {
            if (topLevelDone) {
                val c = nextNonWhitespaceOrEof()
                if (c == -1) return JsonToken.END_DOCUMENT
                throw JsonFormatException("unexpected data after the end")
            }
            return readValueToken()
        }
        when (stack.last()) {
            ARR_EMPTY -> {
                val c = nextNonWhitespace()
                if (c == ']'.code) return JsonToken.END_ARRAY
                pos--
                stack[stack.size - 1] = ARR_NONEMPTY
                return readValueToken()
            }
            ARR_NONEMPTY -> {
                return when (nextNonWhitespace()) {
                    ']'.code -> JsonToken.END_ARRAY
                    ','.code -> readValueToken()
                    else -> throw JsonFormatException("expected , or ]")
                }
            }
            OBJ_EMPTY, OBJ_NONEMPTY -> {
                var c = nextNonWhitespace()
                if (c == '}'.code) return JsonToken.END_OBJECT
                if (stack.last() == OBJ_NONEMPTY) {
                    if (c != ','.code) throw JsonFormatException("expected , or }")
                    c = nextNonWhitespace()
                }
                if (c != '"'.code) throw JsonFormatException("expected a name")
                peekedText = readStringBody()
                stack[stack.size - 1] = OBJ_NAME
                return JsonToken.NAME
            }
            OBJ_NAME -> {
                if (nextNonWhitespace() != ':'.code) throw JsonFormatException("expected :")
                stack[stack.size - 1] = OBJ_NONEMPTY
                return readValueToken()
            }
        }
        throw JsonFormatException("invalid state")
    }

    private fun readValueToken(): JsonToken {
        val c = nextNonWhitespace()
        return when {
            c == '{'.code -> JsonToken.BEGIN_OBJECT
            c == '['.code -> JsonToken.BEGIN_ARRAY
            c == '"'.code -> { peekedText = readStringBody(); JsonToken.STRING }
            c == 't'.code -> { readLiteral("rue"); peekedText = "true"; JsonToken.BOOLEAN }
            c == 'f'.code -> { readLiteral("alse"); peekedText = "false"; JsonToken.BOOLEAN }
            c == 'n'.code -> { readLiteral("ull"); JsonToken.NULL }
            c == '-'.code || c in '0'.code..'9'.code -> { peekedText = readNumber(c.toChar()); JsonToken.NUMBER }
            else -> throw JsonFormatException("unexpected character")
        }
    }

    private fun readLiteral(rest: String) {
        for (ch in rest) if (nextChar() != ch.code) throw JsonFormatException("invalid literal")
    }

    private fun readNumber(first: Char): String {
        val sb = StringBuilder().append(first)
        while (true) {
            if (!fill()) break
            val ch = buffer[pos]
            if (ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') { sb.append(ch); pos++ } else break
        }
        if (sb.toString().toDoubleOrNull() == null) throw JsonFormatException("invalid number")
        return sb.toString()
    }

    private fun readStringBody(): String {
        val sb = StringBuilder()
        while (true) {
            val c = nextChar()
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    when (val e = nextChar()) {
                        '"'.code -> sb.append('"')
                        '\\'.code -> sb.append('\\')
                        '/'.code -> sb.append('/')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = Character.digit(nextChar(), 16)
                                if (h < 0) throw JsonFormatException("invalid escape")
                                code = code * 16 + h
                            }
                            sb.append(code.toChar())
                        }
                        else -> throw JsonFormatException("invalid escape ${e.toChar()}")
                    }
                }
                else -> {
                    if (c < 0x20) throw JsonFormatException("control character in string")
                    sb.append(c.toChar())
                }
            }
            if (sb.length > maxStringLength) throw JsonFormatException("string too long")
        }
    }

    private fun fill(): Boolean {
        if (pos < limit) return true
        limit = input.read(buffer, 0, buffer.size)
        pos = 0
        if (limit <= 0) { limit = 0; return false }
        return true
    }

    private fun nextChar(): Int {
        if (!fill()) throw JsonFormatException("unexpected end of input")
        return buffer[pos++].code
    }

    private fun nextNonWhitespace(): Int {
        val c = nextNonWhitespaceOrEof()
        if (c == -1) throw JsonFormatException("unexpected end of input")
        return c
    }

    private fun nextNonWhitespaceOrEof(): Int {
        while (true) {
            if (!fill()) return -1
            val c = buffer[pos++]
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t' && c != '﻿') return c.code
        }
    }

    private companion object {
        const val ARR_EMPTY = 1
        const val ARR_NONEMPTY = 2
        const val OBJ_EMPTY = 3
        const val OBJ_NAME = 4
        const val OBJ_NONEMPTY = 5
    }
}
