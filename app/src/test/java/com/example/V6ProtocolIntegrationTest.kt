package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
import com.example.data.model.HotspotUser
import com.example.data.model.UserManagerUser
import com.example.data.model.PppoeUser
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
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addUserManagerUser(
                UserManagerUser(username = "new_card", password = "9999", profile = "p1"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil { success || error != null }
            assertTrue("addUserManagerUser must succeed via v6 path: $error", success)
            assertTrue(server.commandsMatching("/tool/user-manager/user/add").isNotEmpty())
        } finally { server.shutdown() }
    }

    @Test
    fun `addHotspotUser writes over native api on custom port`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addHotspotUser(
                HotspotUser(name = "hs_card", password = "5555", profile = "gold"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil { success || error != null }
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
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.removeActiveHotspotUser("*5", "victim",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
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
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addHotspotUser(
                HotspotUser(name = "dup", password = "1", profile = "default"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil { success || error != null }
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
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.deleteUserManagerUser("card_x",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("delete must succeed: $error", success)
            val removes = server.commandsMatching("/tool/user-manager/user/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue("must send resolved .id in =numbers=", removes.first().any { it == "=numbers=*77" })
        } finally { server.shutdown() }
    }}
