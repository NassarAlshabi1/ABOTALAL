package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
import com.example.data.model.HotspotUser
import com.example.data.model.UserManagerUser
import com.example.data.repository.MikroTikNativeApi
import com.example.data.repository.MikroTikRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * End-to-end RouterOS v6 coverage: legacy MD5-challenge login, user-manager
 * /tool/user-manager fallback, hotspot CRUD over the native socket, honest
 * !trap error surfacing, and =numbers= write parameters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V6ProtocolIntegrationTest {

    private class ScriptableMockServer {
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

    private fun connectRepo(server: ScriptableMockServer): MikroTikRepository {
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
        val deadline = System.currentTimeMillis() + 15_000
        while ((!success && error == null) && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        assertTrue("connectToRouter failed: $error", success)
        return repo
    }

    private fun pumpUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
    }

    @Test
    fun `login succeeds on legacy v6 MD5 challenge`() {
        val server = ScriptableMockServer()
        try {
            server.v6Challenge = true
            val api = MikroTikNativeApi("127.0.0.1", server.port, 5000, 5000)
            val (ok, msg) = api.login("admin", "1234")
            assertTrue("v6 challenge login should succeed: $msg", ok)
            assertTrue("client must answer the challenge", server.challengeSeen)
            api.close()
        } finally { server.shutdown() }
    }

    @Test
    fun `executeStrict throws the router trap message`() {
        val server = ScriptableMockServer()
        try {
            server.handler = {
                if (it.firstOrNull()?.contains("remove") == true)
                    listOf(listOf("!trap", "=message=no such item"))
                else listOf(listOf("!done"))
            }
            val api = MikroTikNativeApi("127.0.0.1", server.port, 5000, 5000)
            assertTrue(api.login("admin", "1234").first)
            var thrown: String? = null
            try {
                api.executeStrict("/ip/hotspot/user/remove", "=numbers=*99")
            } catch (e: Exception) { thrown = e.message }
            assertTrue("executeStrict must surface the trap, got: $thrown", thrown?.contains("no such item") == true)
            api.close()
        } finally { server.shutdown() }
    }

    @Test
    fun `addUserManagerUser falls back to v6 tool path`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    // v7 path does not exist on v6
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/user/add") -> {
                        assertTrue(words.any { it == "=customer=admin" })
                        assertTrue(words.any { it.startsWith("=username=") })
                        listOf(listOf("!done"))
                    }
                    cmd.startsWith("/tool/user-manager/user/print") ->
                        listOf(listOf("!re", "=.id=*9", "=name=new_card", "=password=9999"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepo(server)
            var success = false; var error: String? = null
            repo.addUserManagerUser(
                UserManagerUser(username = "new_card", password = "9999", profile = "p1"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpUntil { success || error != null }
            assertTrue("addUserManagerUser must succeed via v6 path: $error", success)
            assertTrue(server.commandsMatching("/tool/user-manager/user/add").isNotEmpty())
        } finally { server.shutdown() }
    }

    @Test
    fun `addHotspotUser writes over native api on custom port`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepo(server)
            var success = false; var error: String? = null
            repo.addHotspotUser(
                HotspotUser(name = "hs_card", password = "5555", profile = "gold"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpUntil { success || error != null }
            assertTrue("addHotspotUser must succeed: $error", success)
            val adds = server.commandsMatching("/ip/hotspot/user/add")
            assertTrue("router must receive /ip/hotspot/user/add", adds.isNotEmpty())
            assertTrue(adds.first().any { it == "=name=hs_card" })
            assertTrue(adds.first().any { it == "=profile=gold" })
            assertTrue(repo.hotspotUsers.value.any { it.name == "hs_card" })
        } finally { server.shutdown() }
    }

    @Test
    fun `removeActiveHotspotUser uses numbers parameter`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepo(server)
            var success = false; var error: String? = null
            repo.removeActiveHotspotUser("*5", "victim",
                onSuccess = { success = true },
                onError = { error = it })
            pumpUntil { success || error != null }
            assertTrue("removeActive must succeed: $error", success)
            val removes = server.commandsMatching("/ip/hotspot/active/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue("must use =numbers= not =.id=", removes.first().any { it == "=numbers=*5" })
        } finally { server.shutdown() }
    }

    @Test
    fun `router trap on hotspot add reports honest error`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                if (words.firstOrNull()?.startsWith("/ip/hotspot/user/add") == true)
                    listOf(listOf("!trap", "=message=already have user with this name"))
                else listOf(listOf("!done"))
            }
            val repo = connectRepo(server)
            var success = false; var error: String? = null
            repo.addHotspotUser(
                HotspotUser(name = "dup", password = "1", profile = "default"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpUntil { success || error != null }
            assertFalse("must not report success", success)
            assertTrue("error must carry the router message, got: $error",
                error?.contains("already have user") == true)
            // And the local list must NOT show a phantom user
            assertFalse(repo.hotspotUsers.value.any { it.name == "dup" })
        } finally { server.shutdown() }
    }

    @Test
    fun `um user removal resolves dot id then removes by numbers`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/tool/user-manager/user/print") || cmd.startsWith("/user-manager/user/print") ->
                        listOf(listOf("!re", "=.id=*77", "=name=card_x"), listOf("!done"))
                    cmd.startsWith("/user-manager/user/remove") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepo(server)
            var success = false; var error: String? = null
            repo.deleteUserManagerUser("card_x",
                onSuccess = { success = true },
                onError = { error = it })
            pumpUntil { success || error != null }
            assertTrue("delete must succeed: $error", success)
            val removes = server.commandsMatching("/tool/user-manager/user/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue("must send resolved .id in =numbers=", removes.first().any { it == "=numbers=*77" })
        } finally { server.shutdown() }
    }
}
