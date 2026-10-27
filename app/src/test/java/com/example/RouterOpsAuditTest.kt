package com.example

import com.example.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full-project diagnosis sweep: exercises repository operations that had no
 * wire-level coverage — hotspot deletes/profiles, UM profile CRUD, UM backup,
 * cloud backup removal — against the scriptable mock router.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouterOpsAuditTest {

    @Test
    fun `deleteHotspotUser sends numbers remove on native api`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.deleteHotspotUser("*3", "card3",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("delete must succeed: $error", success)
            val removes = server.commandsMatching("/ip/hotspot/user/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue(removes.first().any { it == "=numbers=*3" })
        } finally { server.shutdown() }
    }

    @Test
    fun `deleteHotspotUser surfaces router trap`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                if (cmd.startsWith("/ip/hotspot/user/remove"))
                    listOf(listOf("!trap", "=message=no such item"))
                else listOf(listOf("!done"))
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.deleteHotspotUser("*3", "card3",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("delete must fail honestly", !success && error != null)
            assertTrue(error!!.contains("no such item"))
        } finally { server.shutdown() }
    }

    @Test
    fun `addHotspotProfile writes profile on native api`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.addHotspotProfile(
                HotspotProfile(name = "gold", rateLimit = "2M/2M", sharedUsers = "2"),
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("profile add must succeed: $error", success)
            val adds = server.commandsMatching("/ip/hotspot/user/profile/add")
            assertTrue(adds.isNotEmpty())
            assertTrue(adds.first().any { it == "=name=gold" })
            assertTrue(adds.first().any { it == "=rate-limit=2M/2M" })
        } finally { server.shutdown() }
    }

    @Test
    fun `updateUserManagerProfile resolves id then sets`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/profile/print") ->
                        listOf(listOf("!re", "=.id=*55", "=name=old500"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.updateUserManagerProfile("old500",
                UserManagerProfile(name = "new500", validity = "4w", price = 500),
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("update must succeed: $error", success)
            val sets = server.commandsMatching("/tool/user-manager/profile/set")
            assertTrue(sets.isNotEmpty())
            assertTrue(sets.first().any { it == "=numbers=*55" })
        } finally { server.shutdown() }
    }

    @Test
    fun `deleteUserManagerProfile removes resolved id`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/profile/print") ->
                        listOf(listOf("!re", "=.id=*66", "=name=p500"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.deleteUserManagerProfile("p500",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("delete profile must succeed: $error", success)
            val removes = server.commandsMatching("/tool/user-manager/profile/remove")
            assertTrue(removes.isNotEmpty())
            assertTrue(removes.first().any { it == "=numbers=*66" })
        } finally { server.shutdown() }
    }

    @Test
    fun `backupUserManagerDatabase reports router failure honestly`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                if (cmd.contains("database/save"))
                    listOf(listOf("!trap", "=message=not enough permissions"))
                else listOf(listOf("!done"))
            }
            val repo = connectRepoToMockRouter(server)
            var file: String? = null
            repo.backupUserManagerDatabase(
                onSuccess = { file = it },
                onError = { file = "ERR:$it" })
            pumpMainUntil { file != null }
            // Backup must NOT report a usable filename when the router refused.
            assertTrue("backup must not fake success when router rejects save", file!!.startsWith("ERR:"))
        } finally { server.shutdown() }
    }

    @Test
    fun `removeCloudBackup uses v6 remove-file slot number`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.removeCloudBackup("0",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("cloud remove must succeed: $error", success)
            val removes = server.commandsMatching("/system/backup/cloud/remove-file")
            assertTrue(removes.isNotEmpty())
            assertTrue(removes.first().any { it == "=number=0" })
        } finally { server.shutdown() }
    }

    @Test
    fun `uploadCloudBackup reuses the single v6 cloud slot`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                when (words.firstOrNull()) {
                    "/system/backup/cloud/print" ->
                        listOf(listOf("!re", "=name=cloud-old", "=size=1KiB", "=status=ok"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.uploadCloudBackup("new-backup", "secret",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("cloud upload must succeed: $error", success)
            val uploads = server.commandsMatching("/system/backup/cloud/upload-file")
            assertTrue(uploads.isNotEmpty())
            assertTrue(uploads.first().any { it == "=action=create-and-upload" })
            assertTrue(uploads.first().any { it == "=replace=cloud-old" })
        } finally { server.shutdown() }
    }

    @Test
    fun `commands after a trap stay in sync`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/") ->
                        listOf(listOf("!trap", "=message=no such command"))
                    cmd.startsWith("/tool/user-manager/user/print") ->
                        listOf(listOf("!re", "=.id=*9", "=name=card1"), listOf("!done"))
                    cmd.startsWith("/system/identity/print") ->
                        listOf(listOf("!re", "=name=router-lab"), listOf("!done"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var success = false; var error: String? = null
            repo.deleteUserManagerUser("card1",
                onSuccess = { success = true },
                onError = { error = it })
            pumpMainUntil { success || error != null }
            assertTrue("delete must succeed after v7 trap: $error", success)
            // Delete succeeded only because the post-trap user/print reply was
            // read correctly — protocol stayed in sync.
        } finally { server.shutdown() }
    }
}
