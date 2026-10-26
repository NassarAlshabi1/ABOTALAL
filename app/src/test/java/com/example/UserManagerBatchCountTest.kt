package com.example

import com.example.data.model.UserManagerUser
import com.example.ui.screens.GeneratedBatchRecord
import com.example.ui.screens.resolveCards
import com.example.util.CardPrintAndExportHelper
import com.example.util.CardTemplateConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The User Manager batch generator must create exactly the number of cards the
 * user typed (1 or 1000), and print/PDF must contain exactly those cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserManagerBatchCountTest {

    private fun cards(n: Int, profile: String = "p1") =
        (1..n).map { UserManagerUser(username = "c$it", password = "pw$it", profile = profile) }

    @Test
    fun `v6 batch of 1000 adds exactly 1000 users via tool path`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                val cmd = words.firstOrNull() ?: ""
                when {
                    cmd.startsWith("/user-manager/") -> listOf(listOf("!trap", "=message=no such command"))
                    else -> listOf(listOf("!done"))
                }
            }
            val repo = connectRepoToMockRouter(server)
            var created: List<UserManagerUser>? = null
            var error: String? = null
            repo.batchAddUserManagerUsers(cards(1000), "B1000",
                onSuccess = { created = it },
                onError = { e, _ -> error = e })
            pumpMainUntil(60_000) { created != null || error != null }
            assertEquals("error: $error", 1000, created?.size)
            assertEquals(1000, server.commandsMatching("/tool/user-manager/user/add").size)
            // The v6 version gate skips the v7 menu entirely (no wasted probe).
            assertEquals(0, server.commandsMatching("/user-manager/user/add").size)
            assertEquals(1000, server.commandsMatching("/tool/user-manager/user/create-and-activate-profile").size)
        } finally { server.shutdown() }
    }

    @Test
    fun `partial failure still returns the cards the router accepted`() = runBlocking {
        val server = ScriptableMockServer()
        try {
            server.handler = { words ->
                if (words.firstOrNull() == "/tool/user-manager/user/add" && words.any { it == "=username=c2" })
                    listOf(listOf("!trap", "=message=already have user with this name"))
                else listOf(listOf("!done"))
            }
            val repo = connectRepoToMockRouter(server)
            var partial: List<UserManagerUser>? = null
            var error: String? = null
            repo.batchAddUserManagerUsers(cards(3), "B3",
                onSuccess = { error = "unexpected success" },
                onError = { e, c -> error = e; partial = c })
            pumpMainUntil { error != null }
            assertEquals(listOf("c1", "c3"), partial?.map { it.username })
            assertTrue(error!!.contains("already have user"))
        } finally { server.shutdown() }
    }

    @Test
    fun `batch prints only its own cards not every card of the profile`() {
        val routerUsers = cards(50) // 50 older cards on the same profile
        val batch = GeneratedBatchRecord("B", "", "p1", 1, 0, "", cards = listOf(UserManagerUser(username = "new", profile = "p1")))
        assertEquals(listOf("new"), batch.resolveCards(routerUsers).map { it.username })

        val legacy = GeneratedBatchRecord("L", "", "p1", 2, 0, "c")
        assertEquals(2, legacy.resolveCards(routerUsers).size)
        val noPrefix = GeneratedBatchRecord("N", "", "p1", 2, 0, "")
        assertTrue(noPrefix.resolveCards(routerUsers).isEmpty())
    }

    @Test
    fun `page count matches renderer grid`() {
        val cfg = CardTemplateConfig(columns = 3, rows = 17) // 51 per sheet
        assertEquals(1, CardPrintAndExportHelper.pageCountFor(1, cfg))
        assertEquals(1, CardPrintAndExportHelper.pageCountFor(51, cfg))
        assertEquals(2, CardPrintAndExportHelper.pageCountFor(52, cfg))
        assertEquals(20, CardPrintAndExportHelper.pageCountFor(1000, cfg))
        // rows above the renderer's limit are clamped the same way the renderer clamps them
        val tall = CardTemplateConfig(columns = 2, rows = 40)
        assertEquals(60, CardPrintAndExportHelper.cardsPerPage(tall))
        assertEquals(2, CardPrintAndExportHelper.pageCountFor(100, tall))
    }
}
