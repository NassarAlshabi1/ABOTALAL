package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
import com.example.data.repository.MikroTikRepository
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Shared scriptable RouterOS API mock: speaks the real wire protocol on a
 * random local port, records every sentence the app sends, and answers via a
 * programmable handler. Supports legacy v6 MD5-challenge login and outright
 * credential rejection.
 */
internal class ScriptableMockServer {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    private var running = true

    /** Every sentence the client sent (each = command words). */
    val received = CopyOnWriteArrayList<List<String>>()

    /** Responder: full sentence words -> sentences to send back. */
    var handler: (List<String>) -> List<List<String>> = { listOf(listOf("!done")) }

    /** v6 legacy mode: first /login returns !done with an =ret= challenge. */
    var v6Challenge = false
    var challengeSeen = false

    /** When true, every /login attempt is rejected with a trap (bad credentials). */
    var rejectLogin = false

    private val worker = thread(isDaemon = true) { serve() }

    private fun writeWord(out: OutputStream, word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x4000 -> { out.write((len shr 8) or 0x80); out.write(len and 0xFF) }
            len < 0x200000 -> { out.write((len shr 16) or 0xC0); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
            else -> { out.write((len shr 24) or 0xE0); out.write((len shr 16) and 0xFF); out.write((len shr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(bytes)
}

    private fun writeSentence(out: OutputStream, words: List<String>) {
        for (w in words) writeWord(out, w)
        out.write(0)
        out.flush()
}

    private fun readWord(input: InputStream): String? {
        val first = input.read()
        if (first == -1) return null
        if (first == 0) return ""
        val len = when {
            (first and 0x80) == 0 -> first
            (first and 0xC0) == 0x80 -> ((first and 0x3F) shl 8) or input.read()
            (first and 0xE0) == 0xC0 -> ((first and 0x1F) shl 16) or (input.read() shl 8) or input.read()
            (first and 0xF0) == 0xE0 -> ((first and 0x0F) shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
            else -> return ""
        }
        val buf = ByteArray(len); var off = 0
        while (off < len) { val r = input.read(buf, off, len - off); if (r == -1) break; off += r }
        return String(buf, 0, off, Charsets.UTF_8)
}

    private fun readSentence(input: InputStream): List<String>? {
        val words = mutableListOf<String>()
        while (true) { val w = readWord(input) ?: return null; if (w.isEmpty()) return words; words.add(w) }
}

    private fun serve() {
        try {
            while (running) {
                val client: Socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val input = client.getInputStream()
                        val out = client.getOutputStream()
                        while (true) {
                            val words = readSentence(input) ?: break
                            received.add(words)
                            val command = words.firstOrNull() ?: continue
                            if (command.startsWith("/login")) {
                                if (rejectLogin) {
                                    writeSentence(out, listOf("!trap", "=message=invalid user name or password"))
                                    continue
                                }
                                if (v6Challenge && words.any { it.startsWith("=response=00") }) {
                                    challengeSeen = true
                                    writeSentence(out, listOf("!done"))
                                    continue
                                }
                                if (v6Challenge && !challengeSeen) {
                                    // Old v6: !done with a challenge the client must MD5-answer
                                    writeSentence(out, listOf("!done", "=ret=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
                                    continue
                                }
                                if (v6Challenge) {
                                    writeSentence(out, listOf("!trap", "=message=invalid user name or password"))
                                    continue
                                }
                                writeSentence(out, listOf("!done"))
                                continue
                            }
                            handler(words).forEach { writeSentence(out, it) }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        } catch (_: Exception) {
        }
}

    fun commandsMatching(prefix: String): List<List<String>> =
        received.filter { it.firstOrNull()?.startsWith(prefix) == true }

    fun shutdown() { running = false; runCatching { server.close() } }
}


internal fun connectRepoToMockRouter(server: ScriptableMockServer): MikroTikRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
    var success = false
    var error: String? = null
    repo.connectToRouter(
        RouterConnection(name = "t", host = "127.0.0.1", port = server.port, username = "admin", password = "1234"),
        onSuccess = { success = true },
        onError = { error = it }
    )
    pumpMainUntil(15_000) { success || error != null }
    org.junit.Assert.assertTrue("connectToRouter failed: $error", success)
    return repo
}

internal fun pumpMainUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond() && System.currentTimeMillis() < deadline) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(100)
    }
}
