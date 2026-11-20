package com.example

import com.example.data.repository.RouterOsApiWordCodec
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

    /** Version reported when a test handler does not answer /system/resource/print
     *  with a version field. Keeps the v6-only support gate satisfied. */
    var routerVersion = "6.49.21"

    private val worker = thread(isDaemon = true) { serve() }

    private fun writeWord(out: OutputStream, word: String) {
        RouterOsApiWordCodec.writeWord(out, word)
    }

    private fun writeSentence(out: OutputStream, words: List<String>) {
        for (w in words) writeWord(out, w)
        out.write(0)
        out.flush()
    }

    private fun readWord(input: InputStream): String? {
        return RouterOsApiWordCodec.readWord(input)
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
                        client.tcpNoDelay = true
                        val input = java.io.BufferedInputStream(client.getInputStream())
                        // Buffered so each sentence leaves as one packet (flushed in writeSentence);
                        // unbuffered word-by-word writes hit Nagle + delayed-ACK (~40ms per reply).
                        val out = java.io.BufferedOutputStream(client.getOutputStream())
                        while (true) {
                            val words = readSentence(input) ?: break
                            received.add(words)
                            val command = words.firstOrNull() ?: continue
                            if (command.startsWith("/login")) {
                                if (rejectLogin) {
                                    writeSentence(out, listOf("!trap", "=message=invalid user name or password"))
                                    writeSentence(out, listOf("!done"))
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
                                    writeSentence(out, listOf("!done"))
                                    continue
                                }
                                writeSentence(out, listOf("!done"))
                                continue
                            }
                            handler(words).let { replies ->
                                replies.forEach { writeSentence(out, it) }
                                if (replies.none { it.firstOrNull() == "!done" }) {
                                    writeSentence(out, listOf("!done"))
                                }
                            }
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
    val originalHandler = server.handler
    server.handler = { words ->
        if (words.firstOrNull() == "/system/resource/print") {
            val replies = originalHandler(words)
            val hasVersion = replies.any { sentence ->
                sentence.any { it.startsWith("=version=") }
            }
            if (hasVersion) replies
            else listOf(
                listOf("!re", "=version=${server.routerVersion}", "=board-name=TestBoard"),
                listOf("!done")
            )
        } else {
            originalHandler(words)
        }
    }
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
