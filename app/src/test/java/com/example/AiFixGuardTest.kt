package com.example

import com.example.data.repository.AiFix
import com.example.data.repository.AiFixGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiFixGuardTest {

    private fun response(json: String) = "ملخص التشخيص.\n\n```json\n$json\n```\n"

    @Test
    fun `parses fenced fix plan and normalizes verb separated commands`() {
        val raw = response(
            """{"summary":"s","fixes":[{"title":"أغلق telnet","risk":"low","summary":"تقليل سطح الهجوم","commands":["/ip/service set numbers=telnet disabled=yes"]}]}"""
        )
        val fixes = AiFixGuard.parseFixes(raw)
        assertEquals(1, fixes.size)
        assertEquals("أغلق telnet", fixes[0].title)
        assertEquals("low", fixes[0].risk)
        assertEquals(listOf("/ip/service/set numbers=telnet disabled=yes"), fixes[0].commands)
    }

    @Test
    fun `strips fenced fix block from diagnosis prose`() {
        val raw = response("""{"fixes":[{"title":"x","commands":["/ip/dns/set servers=1.1.1.1"]}]}""")
        assertEquals("ملخص التشخيص.", AiFixGuard.stripFixBlock(raw))
    }

    @Test
    fun `drops fixes whose commands are not allowlisted`() {
        val raw = response(
            """{"fixes":[{"title":"reboot","commands":["/system/reboot"]},{"title":"script","commands":["/system/script/set source=1"]},{"title":"safe","commands":["/ip/dns/set servers=1.1.1.1"]}]}"""
        )
        val fixes = AiFixGuard.parseFixes(raw)
        assertEquals(1, fixes.size)
        assertEquals("safe", fixes[0].title)
    }

    @Test
    fun `drops commands that carry secret bearing parameters`() {
        val fixes = AiFixGuard.parseFixes(
            response("""{"fixes":[{"title":"x","commands":["/ppp/profile/set name=p password=secret"]}]}""")
        )
        assertTrue(fixes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            AiFixGuard.buildSafePlan(listOf(AiFix("x", "high", "", listOf("/ppp/profile/set name=p password=secret"))))
        }
    }

    @Test
    fun `rejects disabling the api service used by the app`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiFixGuard.buildSafePlan(
                listOf(AiFix("x", "high", "", listOf("/ip/service/set numbers=api disabled=yes")))
            )
        }
    }

    @Test
    fun `rejects unrestricted input drop rules`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiFixGuard.buildSafePlan(
                listOf(AiFix("x", "high", "", listOf("/ip/firewall/filter/add chain=input action=drop")))
            )
        }
    }

    @Test
    fun `accepts targeted input drop rules and returns api params`() {
        val plan = AiFixGuard.buildSafePlan(
            listOf(AiFix("x", "medium", "", listOf("/ip/firewall/filter/add chain=input action=drop src-address=203.0.113.5")))
        )
        assertEquals(1, plan.size)
        assertEquals("/ip/firewall/filter/add", plan[0].path)
        assertEquals("drop", plan[0].params["action"])
        assertEquals("203.0.113.5", plan[0].params["src-address"])
    }

    @Test
    fun `rejects shell metacharacters and positional cli arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiFixGuard.buildSafePlan(listOf(AiFix("x", "low", "", listOf("/ip/ssh/set port=2222;reboot"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiFixGuard.buildSafePlan(listOf(AiFix("x", "low", "", listOf("/ip/service set telnet disabled=yes"))))
        }
    }

    @Test
    fun `ignores responses without a fix block`() {
        assertTrue(AiFixGuard.parseFixes("تحليل عادي بلا إصلاحات.").isEmpty())
        assertFalse(AiFixGuard.stripFixBlock("تحليل عادي بلا إصلاحات.").isBlank())
    }

    @Test
    fun `caps the number of fixes`() {
        val items = (1..8).joinToString(",") { """{"title":"f$it","commands":["/ip/dns/set servers=1.1.1.$it"]}""" }
        val fixes = AiFixGuard.parseFixes(response("""{"fixes":[$items]}"""))
        assertEquals(AiFixGuard.MAX_FIXES, fixes.size)
    }
}
