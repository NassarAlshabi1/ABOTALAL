package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
import com.example.data.model.HotspotProfile
import com.example.data.model.PppoeUser
import com.example.data.model.RouterGenericItem
import com.example.data.repository.MikroTikRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RouterOS write operations end-to-end: PPPoE secrets, hotspot free mode,
 * mangle/TTL rules, v6 card renewal, bulk deletes, profiles, and tool
 * sections — all verified against a scriptable mock router on the real wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V6WriteOperationsTest {
    @Test
    fun `pppoe user add writes ppp secret on router`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addPppoeUser(
                PppoeUser(name = "ppp1", password = "pw", service = "pppoe", profile = "fast"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil { success || error != null }
            assertTrue("pppoe add must succeed: $error", success)
            val adds = server.commandsMatching("/ppp/secret/add")
            assertTrue(adds.isNotEmpty())
            assertTrue(adds.first().any { it == "=name=ppp1" })
            assertTrue(adds.first().any { it == "=service=pppoe" })
            assertTrue(adds.first().any { it == "=profile=fast" })
        } finally { server.shutdown() }
    }

    @Test
    fun `ttl bypass adds and removes mangle rule on router`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val mangleInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/ip/firewall/mangle/print") -> {
                        if (mangleInstalled.get())
                            listOf(listOf("!re", "=.id=*3", "=comment=ABO_TALAL_ANTI_SHARE"), listOf("!done"))
                        else listOf(listOf("!done"))
                    }
                    cmd.startsWith("/ip/firewall/mangle/add") -> {
                        mangleInstalled.set(true)
                        listOf(listOf("!done"))
                    }
                    cmd.startsWith("/ip/firewall/mangle/remove") -> {
                        mangleInstalled.set(false)
                        listOf(listOf("!done"))
                    }
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.toggleTtlBypass(true, { success = true }, { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("enable must succeed: $error", success)
            val adds = server.commandsMatching("/ip/firewall/mangle/add")
            assertTrue(adds.isNotEmpty())
            assertTrue(adds.first().any { it == "=action=change-ttl" })
            assertTrue(adds.first().any { it == "=comment=ABO_TALAL_ANTI_SHARE" })

            success = false; error = null
            repo.toggleTtlBypass(false, { success = true }, { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("disable must succeed: $error", success)
            val removes = server.commandsMatching("/ip/firewall/mangle/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue(removes.first().any { it == "=numbers=*3" })
        } finally { server.shutdown() }
    }

    @Test
    fun `free hotspot disables every hotspot server`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                if (words.firstOrNull()?.startsWith("/ip/hotspot/print") == true)
                    listOf(
                        listOf("!re", "=.id=*1", "=name=hotspot1"),
                        listOf("!re", "=.id=*2", "=name=hotspot2"),
                        listOf("!done")
                    )
                else listOf(listOf("!done"))
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.toggleFreeHotspot(true, { success = true }, { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("free hotspot must succeed: $error", success)
            val sets = server.commandsMatching("/ip/hotspot/set")
            assertEquals("both servers must be set", 2, sets.size)
            assertTrue(sets.all { s -> s.any { it == "=disabled=yes" } })
            assertTrue(sets.any { s -> s.any { it == "=numbers=*1" } })
            assertTrue(sets.any { s -> s.any { it == "=numbers=*2" } })
        } finally { server.shutdown() }
    }

    @Test
    fun `card renewal uses v6 create-and-activate-profile`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/user/print") ->
                        listOf(listOf("!re", "=.id=*11", "=name=card1"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.renewUserManagerUser("card1", "vip", resetUptime = true,
                onSuccess = { success = true }, onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("renew must succeed: $error", success)
            val acts = server.commandsMatching("/tool/user-manager/user/create-and-activate-profile")
            assertTrue(acts.isNotEmpty())
            assertTrue(acts.first().any { it == "=numbers=*11" })
            assertTrue(acts.first().any { it == "=profile=vip" })
        } finally { server.shutdown() }
    }

    @Test
    fun `bulk delete reports partial failures honestly`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/user/print") || cmd.startsWith("/tool/user-manager/user/print") ->
                        listOf(
                            listOf("!re", "=.id=*1", "=name=u1"),
                            listOf("!re", "=.id=*2", "=name=u2"),
                            listOf("!done")
                        )
                    cmd.contains("user/remove") -> {
                        if (words.any { it == "=numbers=*2" })
                            listOf(listOf("!trap", "=message=no such item"))
                        else listOf(listOf("!done"))
                    }
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var deleted = -1; var error: String? = null
            repo.bulkDeleteUserManagerUsers(listOf("u1", "u2"),
                onSuccess = { deleted = it },
                onError = { error = it })
            pumpMainUntil { deleted >= 0 || error != null }
            assertTrue("partial failure must surface an error, deleted=$deleted", error != null)
            assertTrue("error must report 1 of 2: $error", error?.contains("1") == true)
            // u1 deleted locally, u2 remains
            assertTrue(repo.userManagerUsers.value.none { it.username == "u1" })
            assertTrue(repo.userManagerUsers.value.any { it.username == "u2" })
        } finally { server.shutdown() }
    }

    @Test
    fun `bad credentials surface the router rejection`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.rejectLogin = true
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
            var success = false; var error: String? = null
            repo.connectToRouter(
                RouterConnection(name = "t", host = "127.0.0.1", port = server.port, username = "admin", password = "wrong"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil(15_000) { success || error != null }
            assertFalse("bad credentials must not connect", success)
            assertTrue("must surface router rejection, got: $error", error != null)
            assertFalse(repo.isConnected.value)
        } finally { server.shutdown() }
    }

    @Test
    fun `add hotspot profile writes profile on router`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addHotspotProfile(
                com.example.data.model.HotspotProfile(name = "gold", rateLimit = "2M/2M", sharedUsers = "1"),
                onSuccess = { success = true },
                onError = { error = it }
            )
            pumpMainUntil { success || error != null }
            assertTrue("profile add must succeed: $error", success)
            val adds = server.commandsMatching("/ip/hotspot/user/profile/add")
            assertTrue(adds.isNotEmpty())
            assertTrue(adds.first().any { it == "=name=gold" })
            assertTrue(adds.first().any { it == "=rate-limit=2M/2M" })
        } finally { server.shutdown() }
    }

    @Test
    fun `dhcp leases section returns real router rows`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                if (words.firstOrNull()?.startsWith("/ip/dhcp-server/lease/print") == true)
                    listOf(
                        listOf("!re", "=address=192.168.1.50", "=mac-address=AA:BB:CC:DD:EE:FF", "=host-name=phone1", "=status=bound"),
                        listOf("!done")
                    )
                else listOf(listOf("!done"))
            }
            val repo = connectRepoToMockRouter(server)
            var items: List<com.example.data.model.RouterGenericItem>? = null
            var error: String? = null
            repo.fetchGenericSectionData("dhcp_leases",
                onSuccess = { items = it },
                onError = { error = it })
            pumpMainUntil { items != null || error != null }
            assertTrue("section fetch must succeed: $error", items != null)
            assertEquals(1, items!!.size)
            assertEquals("192.168.1.50", items!!.first().title)
        } finally { server.shutdown() }
    }
}
