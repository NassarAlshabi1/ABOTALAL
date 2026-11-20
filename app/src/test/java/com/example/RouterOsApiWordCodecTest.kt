package com.example

import com.example.data.repository.RouterOsApiWordCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterOsApiWordCodecTest {
    @Test
    fun `round trips a word using the four byte length form`() {
        val word = "x".repeat(0x200000)
        val encoded = ByteArrayOutputStream().also {
            RouterOsApiWordCodec.writeWord(it, word)
        }.toByteArray()

        assertEquals(0xE0, encoded[0].toInt() and 0xFF)
        assertEquals(word, RouterOsApiWordCodec.readWord(ByteArrayInputStream(encoded)))
    }

    @Test
    fun `decodes the five byte length form used by very large words`() {
        val encoded = byteArrayOf(
            0xF0.toByte(), 0x00, 0x00, 0x00, 0x03,
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()
        )

        assertEquals("abc", RouterOsApiWordCodec.readWord(ByteArrayInputStream(encoded)))
    }

    @Test
    fun `uses RouterOS length prefixes at encoding boundaries`() {
        val cases = listOf(
            127 to byteArrayOf(0x7F),
            128 to byteArrayOf(0x80.toByte(), 0x80.toByte()),
            0x3FFF to byteArrayOf(0xBF.toByte(), 0xFF.toByte()),
            0x4000 to byteArrayOf(0xC0.toByte(), 0x40, 0x00),
            0x1FFFFF to byteArrayOf(0xDF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            0x200000 to byteArrayOf(0xE0.toByte(), 0x20, 0x00, 0x00)
        )

        cases.forEach { (length, expectedPrefix) ->
            val encoded = ByteArrayOutputStream().also {
                RouterOsApiWordCodec.writeWord(it, "x".repeat(length))
            }.toByteArray()
            assertTrue("Wrong prefix for length $length", encoded.copyOf(expectedPrefix.size).contentEquals(expectedPrefix))
        }
    }

    @Test
    fun `rejects truncated five byte length prefix`() {
        val error = runCatching {
            RouterOsApiWordCodec.readWord(ByteArrayInputStream(byteArrayOf(0xF0.toByte(), 0, 0)))
        }.exceptionOrNull()

        assertTrue(error is java.io.EOFException)
    }

    @Test
    fun `rejects oversized word before allocating its body`() {
        val oversizedLengthPrefix = byteArrayOf(
            0xF0.toByte(), 0x01, 0x00, 0x00, 0x01
        )
        val error = runCatching {
            RouterOsApiWordCodec.readWord(ByteArrayInputStream(oversizedLengthPrefix))
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }

    @Test
    fun `rejects reserved five byte length prefixes`() {
        val error = runCatching {
            RouterOsApiWordCodec.readWord(ByteArrayInputStream(byteArrayOf(0xF1.toByte())))
        }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }
}
