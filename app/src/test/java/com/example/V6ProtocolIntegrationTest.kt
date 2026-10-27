package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.RouterConnection
import com.example.data.model.HotspotUser
import com.example.data.model.UserManagerProfile
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
    fun `connection test performs a real v6 login and reports the version`() = runBlocking {
        val server = ScriptableMockServer()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            server.handler = { words ->
                when (words.firstOrNull()) {
                    "/system/resource/print" -> listOf(
                        listOf("!re", "=version=6.49.21 (stable)"),
                        listOf("!done")
                    )
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
            var ok: Boolean? = null
            var msg = ""
            repo.testConnection("127.0.0.1", server.port, "admin", "1234", false) { success, text ->
                ok = success
                msg = text
            }
            pumpMainUntil { ok != null }
            assertTrue("connection test must authenticate, not just probe TCP: $msg", ok == true)
            assertTrue("success message must name the detected version: $msg", msg.contains("6.49.21"))
        } finally {
            db.close()
            server.shutdown()
        }
    }

    @Test
    fun `connection test fails on rejected credentials`() = runBlocking {
        val server = ScriptableMockServer()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            server.rejectLogin = true
            val repo = MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
            var ok: Boolean? = null
            var msg = ""
            repo.testConnection("127.0.0.1", server.port, "admin", "wrong", false) { success, text ->
                ok = success
                msg = text
            }
            pumpMainUntil { ok != null }
            assertFalse("rejected credentials must fail the test: $msg", ok == true)
            assertTrue("failure must be the auth result: $msg", msg.contains("فشل اختبار تسجيل الدخول الفعلي"))
        } finally {
            db.close()
            server.shutdown()
        }
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
    }
    @Test
    fun `user-manager profiles fetched via v6 tool path when v7 traps`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    // v6 router: /user-manager does not exist -> trap
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/profile/profile-limitation/print") ->
                        listOf(listOf("!re", "=name=lim1", "=profile=p500", "=uptime=1d"), listOf("!done"))
                    cmd.startsWith("/tool/user-manager/profile/print") ->
                        listOf(listOf("!re", "=.id=*1", "=name=p500", "=validity=4w2d", "=price=500"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            pumpMainUntil(15000) { repo.userManagerProfiles.value.isNotEmpty() }
            assertTrue(
                "profiles must be fetched from the router via the v6 tool path",
                repo.userManagerProfiles.value.any { it.name == "p500" }
            )
            assertTrue(server.commandsMatching("/tool/user-manager/profile/print").isNotEmpty())
        } finally { server.shutdown() }
    }

    @Test
    fun `v6 profile reads limits from linked limitation row`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd == "/system/resource/print" -> listOf(
                        listOf("!re", "=version=6.49.19", "=board-name=hEX"), listOf("!done")
                    )
                    cmd.startsWith("/user-manager/") -> listOf(listOf("!trap", "=message=no such command"))
                    cmd == "/tool/user-manager/profile/print" -> listOf(
                        listOf("!re", "=.id=*1", "=name=p500", "=validity=4w2d", "=price=500"), listOf("!done")
                    )
                    cmd == "/tool/user-manager/profile/profile-limitation/print" -> listOf(
                        listOf("!re", "=.id=*3", "=profile=p500", "=limitation=p500-lim"), listOf("!done")
                    )
                    cmd == "/tool/user-manager/profile/limitation/print" -> listOf(
                        listOf("!re", "=.id=*2", "=name=p500-lim", "=uptime-limit=1h30m", "=download-limit=1GB"), listOf("!done")
                    )
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            pumpMainUntil(15000) { repo.userManagerProfiles.value.any { it.name == "p500" && it.downloadLimit == "1GB" } }
            val profile = repo.userManagerProfiles.value.first { it.name == "p500" }
            assertEquals("4w2d", profile.validity)
            assertEquals(500, profile.price)
            assertEquals("1h30m", profile.uptimeLimit)
            assertTrue(server.commandsMatching("/tool/user-manager/profile/limitation/print").isNotEmpty())
        } finally { server.shutdown() }
    }

    @Test
    fun `v6 profile create and update use profile validity and separate limits`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd == "/system/resource/print" -> listOf(
                        listOf("!re", "=version=6.49.19", "=board-name=hEX"), listOf("!done")
                    )
                    cmd.startsWith("/user-manager/") -> listOf(listOf("!trap", "=message=no such command"))
                    cmd == "/tool/user-manager/profile/print" -> listOf(
                        listOf("!re", "=.id=*1", "=name=pack", "=validity=4w2d", "=price=500"), listOf("!done")
                    )
                    cmd == "/tool/user-manager/profile/profile-limitation/print" -> listOf(
                        listOf("!re", "=.id=*3", "=profile=p500", "=limitation=p500-lim"),
                        listOf("!re", "=.id=*4", "=profile=pack", "=limitation=pack-lim"),
                        listOf("!done")
                    )
                    cmd == "/tool/user-manager/profile/limitation/print" -> listOf(
                        listOf("!re", "=.id=*2", "=name=pack-lim", "=uptime-limit=1h", "=download-limit=1GB"),
                        listOf("!re", "=.id=*5", "=name=p500-lim", "=uptime-limit=1h30m", "=download-limit=1GB"),
                        listOf("!done")
                    )
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false
            var error: String? = null
            repo.addUserManagerProfile(
                UserManagerProfile("new-pack", validity = "4w2d", price = 500, downloadLimit = "2GB", uptimeLimit = "1h30m"),
                onSuccess = { success = true }, onError = { error = it }
            )
            pumpMainUntil { success || error != null }
            assertTrue("v6 profile creation failed: $error", success)
            val profileAdd = server.commandsMatching("/tool/user-manager/profile/add").last()
            assertTrue(profileAdd.contains("=validity=4w2d"))
            assertTrue(profileAdd.contains("=price=500"))
            val limitationAdd = server.commandsMatching("/tool/user-manager/profile/limitation/add").last()
            assertTrue(limitationAdd.contains("=uptime-limit=1h30m"))
            assertTrue(limitationAdd.contains("=download-limit=2GB"))

            success = false
            error = null
            repo.updateUserManagerProfile(
                "pack",
                UserManagerProfile("renamed-pack", validity = "1w2d", price = 0, downloadLimit = "unlimited", uptimeLimit = "unlimited"),
                onSuccess = { success = true }, onError = { error = it }
            )
            pumpMainUntil { success || error != null }
            assertTrue("v6 profile update failed: $error", success)
            val profileSet = server.commandsMatching("/tool/user-manager/profile/set").last()
            assertTrue(profileSet.contains("=validity=1w2d"))
            assertTrue(profileSet.contains("=price=0"))
            val limitationSet = server.commandsMatching("/tool/user-manager/profile/limitation/set").last()
            assertTrue(limitationSet.contains("=uptime-limit=0s"))
            assertTrue(limitationSet.contains("=download-limit=0B"))
            assertTrue(server.commandsMatching("/tool/user-manager/profile/profile-limitation/set").last().contains("=profile=renamed-pack"))
        } finally { server.shutdown() }
    }

    @Test
    fun `expired card cleanup does not delete manually disabled users`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val users = listOf(
                listOf("!re", "=.id=*manual", "=name=manual_disabled", "=disabled=true", "=profile=p1", "=uptime-used=2h"),
                listOf("!re", "=.id=*expired", "=name=expired_card", "=disabled=false", "=profile=p1", "=uptime-used=2h")
            )
            server.handler = { words ->
                when (words.firstOrNull()) {
                    "/system/resource/print" -> listOf(listOf("!re", "=version=6.49.19"), listOf("!done"))
                    "/tool/user-manager/user/print" -> users + listOf(listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            pumpMainUntil(15_000) { repo.userManagerUsers.value.size == 2 }

            var removed: Int? = null
            var error: String? = null
            repo.cleanExpiredUserManagerUsers(onSuccess = { removed = it }, onError = { error = it })
            pumpMainUntil { removed != null || error != null }

            assertTrue("cleanup failed: $error", removed != null)
            assertEquals(1, removed)
            val deletes = server.commandsMatching("/tool/user-manager/user/remove")
            assertEquals(1, deletes.size)
            assertTrue("only the router-confirmed expired card may be deleted", deletes.single().contains("=numbers=*expired"))
        } finally { server.shutdown() }
    }

    @Test
    fun `v6 maintenance closes and clears sessions and usage logs in the supplied order`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                when (words.firstOrNull()) {
                    "/system/resource/print" -> listOf(listOf("!re", "=version=6.49.19"), listOf("!done"))
                    "/tool/user-manager/session/print" -> listOf(
                        listOf("!re", "=.id=*s1", "=user=alice"),
                        listOf("!re", "=.id=*s2", "=user=ppp-client"),
                        listOf("!done")
                    )
                    "/tool/user-manager/log/print" -> listOf(listOf("!re", "=.id=*l1"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var removed: Int? = null
            var error: String? = null
            repo.cleanStaleUserManagerSessions(onSuccess = { removed = it }, onError = { error = it })
            pumpMainUntil { removed != null || error != null }

            assertTrue("v6 session cleanup failed: $error", removed != null)
            assertEquals(2, removed)
            assertEquals(2, server.commandsMatching("/tool/user-manager/session/close-session").size)
            assertEquals(2, server.commandsMatching("/tool/user-manager/session/remove").size)
            assertEquals(1, server.commandsMatching("/tool/user-manager/log/remove").size)
            assertTrue("all sessions must be closed before the first is removed",
                server.received.indexOfFirst { it.firstOrNull()?.startsWith("/tool/user-manager/session/close-session") == true } <
                    server.received.indexOfFirst { it.firstOrNull()?.startsWith("/tool/user-manager/session/remove") == true })
            assertTrue("logs must be purged only after session cleanup",
                server.received.indexOfFirst { it.firstOrNull()?.startsWith("/tool/user-manager/log/remove") == true } >
                    server.received.indexOfLast { it.firstOrNull()?.startsWith("/tool/user-manager/session/remove") == true })
        } finally { server.shutdown() }
    }

    @Test
    fun `partial hotspot disable rolls back and reports any server left disabled`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val command = words.firstOrNull() ?: ""
                when {
                    command == "/system/resource/print" -> listOf(listOf("!re", "=version=6.49.21"), listOf("!done"))
                    command == "/ip/hotspot/print" -> listOf(
                        listOf("!re", "=.id=*hs1", "=disabled=false"),
                        listOf("!re", "=.id=*hs2", "=disabled=false"),
                        listOf("!done")
                    )
                    command == "/ip/hotspot/set" && words.contains("=numbers=*hs2") && words.contains("=disabled=yes") ->
                        listOf(listOf("!trap", "=message=simulated disable failure"))
                    command == "/ip/hotspot/set" && words.contains("=numbers=*hs1") && words.contains("=disabled=no") ->
                        listOf(listOf("!trap", "=message=simulated rollback failure"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var error: String? = null
            var stillDisabled: List<String>? = null
            repo.disableHotspotForMaintenance(
                onSuccess = { error = "unexpected success" },
                onError = { message, ids -> error = message; stillDisabled = ids }
            )
            pumpMainUntil { error != null }

            assertTrue("failure must report the hotspot server that could not be restored: $error",
                error?.contains("تعذر إعادة تفعيل بعض الخوادم") == true)
            assertEquals(listOf("*hs1"), stillDisabled)
            assertTrue(server.commandsMatching("/ip/hotspot/set").any {
                it.contains("=numbers=*hs1") && it.contains("=disabled=yes")
            })
            assertTrue(server.commandsMatching("/ip/hotspot/set").any {
                it.contains("=numbers=*hs1") && it.contains("=disabled=no")
            })
        } finally { server.shutdown() }
    }

    @Test
    fun `reboot flow re-enables tracked hotspot servers before reboot`() = runBlocking {
        val server = ScriptableMockServer()
        val isDisabled = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            server.handler = { words ->
                val command = words.firstOrNull() ?: ""
                when {
                    command == "/system/resource/print" -> listOf(listOf("!re", "=version=6.49.21"), listOf("!done"))
                    command == "/ip/hotspot/print" -> listOf(
                        listOf("!re", "=.id=*hs1", "=disabled=${isDisabled.get()}"),
                        listOf("!done")
                    )
                    command == "/ip/hotspot/set" && words.contains("=numbers=*hs1") && words.contains("=disabled=no") -> {
                        isDisabled.set(false)
                        listOf(listOf("!done"))
                    }
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success: String? = null
            var error: String? = null
            repo.verifyHotspotAndRebootRouter(
                listOf("*hs1"),
                onSuccess = { success = it },
                onError = { error = it }
            )
            pumpMainUntil(15_000) { success != null || error != null }

            assertTrue("reboot flow failed: $error", success != null)
            val enableIndex = server.received.indexOfFirst {
                it.firstOrNull() == "/ip/hotspot/set" && it.contains("=numbers=*hs1") && it.contains("=disabled=no")
            }
            val rebootIndex = server.received.indexOfFirst { it.firstOrNull() == "/system/reboot" }
            assertTrue("Hotspot must be enabled before reboot", enableIndex >= 0 && rebootIndex > enableIndex)
        } finally { server.shutdown() }
    }

    @Test
    fun `reboot is blocked if hotspot reactivation fails`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val command = words.firstOrNull() ?: ""
                when {
                    command == "/system/resource/print" -> listOf(listOf("!re", "=version=6.49.21"), listOf("!done"))
                    command == "/ip/hotspot/print" -> listOf(listOf("!re", "=.id=*hs1", "=disabled=true"), listOf("!done"))
                    command == "/ip/hotspot/set" -> listOf(listOf("!trap", "=message=simulated enable failure"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var error: String? = null
            repo.verifyHotspotAndRebootRouter(
                listOf("*hs1"),
                onSuccess = { error = "unexpected success" },
                onError = { error = it }
            )
            pumpMainUntil { error != null }

            assertTrue(error?.contains("لن يُعاد تشغيل الراوتر") == true)
            assertTrue("reboot must not be sent after a failed Hotspot restore", server.commandsMatching("/system/reboot").isEmpty())
        } finally { server.shutdown() }
    }
}
