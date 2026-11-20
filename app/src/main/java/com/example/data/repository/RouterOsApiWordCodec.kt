package com.example.data.repository

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Encodes and decodes a single length-prefixed RouterOS Binary API word. */
internal object RouterOsApiWordCodec {
    private const val MAX_WORD_LENGTH = 16 * 1024 * 1024

    fun writeWord(out: OutputStream, word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        val length = bytes.size
        require(length <= MAX_WORD_LENGTH) { "RouterOS API word exceeds supported length" }
        when {
            length < 0x80 -> out.write(length)
            length < 0x4000 -> {
                out.write((length shr 8) or 0x80)
                out.write(length and 0xFF)
            }
            length < 0x200000 -> {
                out.write((length shr 16) or 0xC0)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            length < 0x10000000 -> {
                out.write((length shr 24) or 0xE0)
                out.write((length shr 16) and 0xFF)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(0xF0)
                out.write((length shr 24) and 0xFF)
                out.write((length shr 16) and 0xFF)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
        out.write(bytes)
    }

    fun readWord(input: InputStream): String? {
        val first = input.read()
        if (first == -1) return null
        if (first == 0) return ""

        val length = when {
            (first and 0x80) == 0 -> first
            (first and 0xC0) == 0x80 -> ((first and 0x3F) shl 8) or readLengthByte(input)
            (first and 0xE0) == 0xC0 ->
                ((first and 0x1F) shl 16) or (readLengthByte(input) shl 8) or readLengthByte(input)
            (first and 0xF0) == 0xE0 ->
                ((first and 0x0F) shl 24) or (readLengthByte(input) shl 16) or
                    (readLengthByte(input) shl 8) or readLengthByte(input)
            first == 0xF0 ->
                (readLengthByte(input) shl 24) or (readLengthByte(input) shl 16) or
                    (readLengthByte(input) shl 8) or readLengthByte(input)
            else -> throw IOException("Invalid RouterOS API length prefix: $first")
        }
        if (length < 0 || length > MAX_WORD_LENGTH) {
            throw IOException("RouterOS API word length is outside the supported range")
        }

        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count == -1) throw EOFException("Truncated RouterOS API word")
            if (count == 0) {
                val next = input.read()
                if (next == -1) throw EOFException("Truncated RouterOS API word")
                bytes[offset++] = next.toByte()
            } else {
                offset += count
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLengthByte(input: InputStream): Int = input.read().also {
        if (it == -1) throw EOFException("Truncated RouterOS API length prefix")
    }
}
