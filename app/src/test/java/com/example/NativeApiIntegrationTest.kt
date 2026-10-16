package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
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
import kotlin.concurrent.thread

/**
 * End-to-end check of the native MikroTik API socket path on a *custom* port
 * (regression test for the fix that routes any non-web port to the native API,
 * e.g. an `api` service remapped to port 1300).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeApiIntegrationTest {

    private class MockMikroTikServer {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private var running = true
        private val worker = thread(isDaemon = true) { serve() }

        private fun writeWord(out: OutputStream, word: String) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            when {
                len < 0x80 -> out.write(len)
                len < 0x4000 -> {
                    out.write((len shr 8) or 0x80); out.write(len and 0xFF)
                }
                len < 0x200000 -> {
                    out.write((len shr 16) or 0xC0); out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
                }
                else -> {
                    out.write((len shr 24) or 0xE0); out.write((len shr 16) and 0xFF)
                    out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
                }
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
                (first and 0xE0) == 0xC0 ->
                    ((first and 0x1F) shl 16) or (input.read() shl 8) or input.read()
                (first and 0xF0) == 0xE0 ->
                    ((first and 0x0F) shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
                else -> return ""
            }
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = input.read(buf, off, len - off)
                if (r == -1) break
                off += r
            }
            return String(buf, 0, off, Charsets.UTF_8)
        }

        private fun readSentence(input: InputStream): List<String>? {
            val words = mutableListOf<String>()
            while (true) {
                val w = readWord(input) ?: return null
                if (w.isEmpty()) return words
                words.add(w)
            }
        }

        private fun answer(out: OutputStream, command: String) {
            when {
                command.startsWith("/login") -> writeSentence(out, listOf("!done"))
                command.startsWith("/system/resource/print") -> writeSentence(
                    out,
                    listOf(
                        "!done",
                        "=version=6.49.19 (stable)",
                        "=board-name=hEX",
                        "=uptime=1d00:00:00",
                        "=cpu-load=7",
                        "=total-memory=268435456",
                        "=free-memory=134217728"
                    )
                )
                command.startsWith("/ip/hotspot/user/print") -> {
                    writeSentence(out, listOf("!re", "=.id=*1", "=name=test_card1", "=password=1234", "=profile=default", "=bytes-out=100", "=bytes-in=200"))
                    writeSentence(out, listOf("!done"))
                }
                command.startsWith("/ip/hotspot/user/profile/print") -> {
                    writeSentence(out, listOf("!re", "=.id=*1", "=name=default", "=rate-limit=1M/1M"))
                    writeSentence(out, listOf("!done"))
                }
                command.startsWith("/user-manager/user/print") -> {
                    writeSentence(out, listOf("!re", "=.id=*1", "=name=card_ahmed", "=password=1111", "=profile=1h_100MB", "=uptime-used=15m", "=disabled=false"))
                    writeSentence(out, listOf("!re", "=.id=*2", "=name=card_khalid", "=password=2222", "=profile=1h_100MB", "=uptime-used=30m", "=disabled=false"))
                    writeSentence(out, listOf("!done"))
                }
                command.startsWith("/system/identity/print") ->
                    writeSentence(out, listOf("!done", "=name=mock-router"))
                else -> writeSentence(out, listOf("!done"))
            }
        }

        private fun serve() {
            try {
                while (running) {
                    val client: Socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread(isDaemon = true) {
                        try {
                            val input = client.getInputStream()
                            val out = client.getOutputStream()
                            while (true) {
                                val words = readSentence(input) ?: break
                                val command = words.firstOrNull() ?: continue
                                answer(out, command)
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

        fun shutdown() {
            running = false
            runCatching { server.close() }
        }
    }

    @Test
    fun `native api login and execute work on a custom port`() {
        val server = MockMikroTikServer()
        try {
            val api = MikroTikNativeApi(
                host = "127.0.0.1",
                port = server.port,
                connectTimeoutMs = 5000,
                commandTimeoutMs = 5000
            )
            val (ok, msg) = api.login("admin", "1234")
            assertTrue("login should succeed: $msg", ok)

            val users = api.execute("/ip/hotspot/user/print")
            assertEquals(1, users.size)
            assertEquals("test_card1", users[0]["name"])

            assertTrue(api.ping())
            api.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `connectToRouter uses native api on a custom port and loads data`() = runBlocking {
        val server = MockMikroTikServer()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())

            var success = false
            var error: String? = null
            repo.connectToRouter(
                RouterConnection(
                    name = "test",
                    host = "127.0.0.1",
                    port = server.port,
                    username = "admin",
                    password = "1234"
                ),
                onSuccess = { success = true },
                onError = { error = it }
            )

            val deadline = System.currentTimeMillis() + 15_000
            while (!success && error == null && System.currentTimeMillis() < deadline) {
                // onSuccess/onError are delivered on Dispatchers.Main — pump the looper
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(100)
            }
            assertTrue("connectToRouter should succeed on custom port ${server.port}: $error", success)

            // Post-login: data fetching should have populated the flows used by the dashboard
            val dataDeadline = System.currentTimeMillis() + 15_000
            while (repo.hotspotUsers.value.isEmpty() && System.currentTimeMillis() < dataDeadline) {
                kotlinx.coroutines.delay(100)
            }
            assertTrue(repo.isConnected.value)
            assertEquals("test_card1", repo.hotspotUsers.value.firstOrNull()?.name)
            assertEquals("default", repo.hotspotProfiles.value.firstOrNull()?.name)
        } finally {
            server.shutdown()
        }
    }

    private fun connectRepoToMock(server: MockMikroTikServer): MikroTikRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
        var success = false
        var error: String? = null
        repo.connectToRouter(
            RouterConnection(name = "test", host = "127.0.0.1", port = server.port, username = "admin", password = "1234"),
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

    private fun awaitUmUsers(repo: MikroTikRepository) {
        val deadline = System.currentTimeMillis() + 15_000
        while (repo.userManagerUsers.value.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("user-manager users should load from router", repo.userManagerUsers.value.isNotEmpty())
    }

    @Test
    fun `card renewal updates profile and resets uptime`() = runBlocking {
        val server = MockMikroTikServer()
        try {
            val repo = connectRepoToMock(server)
            awaitUmUsers(repo)

            val target = repo.userManagerUsers.value.first()
            var successCalled = false
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            repo.renewUserManagerUser(
                username = target.username,
                newProfile = "3000RY",
                resetUptime = true,
                onSuccess = { successCalled = true },
                onError = {}
            )
            var wait = System.currentTimeMillis() + 10_000
            while (!successCalled && System.currentTimeMillis() < wait) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(100)
            }

            assertTrue(successCalled)
            val updated = repo.userManagerUsers.value.find { it.username == target.username }
            assertEquals("3000RY", updated?.profile)
            assertTrue(updated?.active == true)
            assertEquals("0s", updated?.uptimeUsed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `bulk toggle deactivates selected user-manager cards`() = runBlocking {
        val server = MockMikroTikServer()
        try {
            val repo = connectRepoToMock(server)
            awaitUmUsers(repo)

            val usernames = repo.userManagerUsers.value.take(2).map { it.username }
            var toggled = -1
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            repo.bulkToggleUserManagerUsers(usernames, active = false) { count -> toggled = count }
            var wait = System.currentTimeMillis() + 10_000
            while (toggled < 0 && System.currentTimeMillis() < wait) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(100)
            }

            assertEquals(2, toggled)
            repo.userManagerUsers.value.filter { it.username in usernames }.forEach {
                assertFalse(it.active)
            }
        } finally {
            server.shutdown()
        }
    }
}
