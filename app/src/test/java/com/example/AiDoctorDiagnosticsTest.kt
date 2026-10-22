package com.example

import com.example.data.repository.AiDoctorDiagnostics
import com.example.data.repository.RouterDiagnosticRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDoctorDiagnosticsTest {
    @Test
    fun `detects major RouterOS version without guessing unknown values`() {
        assertEquals(6, AiDoctorDiagnostics.majorVersion("6.49.20 (stable)"))
        assertEquals(7, AiDoctorDiagnostics.majorVersion("7.13.5"))
        assertEquals(null, AiDoctorDiagnostics.majorVersion("unknown"))
        assertEquals(null, AiDoctorDiagnostics.majorVersion(""))
    }

    @Test
    fun `uses legacy wireless menus on v6 and wifi menus on v7`() {
        val ros6 = AiDoctorDiagnostics.additionalReadSpecs("6.49.20", setOf("wireless"))
        val ros7Old = AiDoctorDiagnostics.additionalReadSpecs("7.12.1", setOf("wireless"))
        val ros7 = AiDoctorDiagnostics.additionalReadSpecs("7.13.5", setOf("wireless"))
        val unknown = AiDoctorDiagnostics.additionalReadSpecs("unknown", setOf("wireless"))

        assertTrue(ros6.any { it.path == "/interface/wireless/registration-table/print" })
        assertTrue(ros6.any { it.path == "/interface/wireless/security-profiles/print" })
        assertFalse(ros6.any { it.path.startsWith("/interface/wifi/") })
        assertTrue(ros7Old.any { it.path == "/interface/wifiwave2/registration-table/print" })
        assertTrue(ros7Old.any { it.path == "/interface/wireless/registration-table/print" })
        assertTrue(ros7Old.any { it.path == "/interface/wifiwave2/security/print" })
        assertTrue(ros7.any { it.path == "/interface/wifi/registration-table/print" })
        assertTrue(ros7.any { it.path == "/interface/wifi/security/print" })
        assertTrue(ros7.any { it.path == "/interface/wireless/registration-table/print" })
        assertFalse(ros7.any { it.path.startsWith("/interface/wifiwave2/") })
        assertTrue(unknown.isEmpty())
        assertFalse(ros7.flatMap { it.properties.split(',') }.any { it.contains("passphrase", ignoreCase = true) })
    }

    @Test
    fun `requests only read-only common access and routing data`() {
        val specs = AiDoctorDiagnostics.additionalReadSpecs("7.17.0", setOf("security", "routing", "ipv6"))
        assertTrue(specs.any { it.path == "/ip/service/print" })
        assertTrue(specs.any { it.path == "/snmp/community/print" })
        assertTrue(specs.any { it.path == "/ip/route/print" })
        assertTrue(specs.any { it.path == "/ipv6/route/print" })
        assertTrue(specs.any { it.path == "/ipv6/firewall/filter/print" })
        assertTrue(specs.all { it.path.endsWith("/print") })
        assertFalse(specs.first { it.key == "snmp_access" }.properties.contains("name"))
    }

    @Test
    fun `creates conservative security findings without disclosing SNMP or wifi secrets`() {
        val findings = AiDoctorDiagnostics.securityFindings(
            mapOf(
                "management_services" to listOf(
                    mapOf("name" to "api", "disabled" to "false", "address" to "", "port" to "8728"),
                    mapOf("name" to "ftp", "disabled" to "yes", "address" to "")
                ),
                "snmp_access" to listOf(mapOf("addresses" to "0.0.0.0/0", "disabled" to "no")),
                "dns" to listOf(mapOf("allow-remote-requests" to "yes")),
                "filter" to listOf(
                    mapOf("chain" to "input", "action" to "accept", "disabled" to "no"),
                    mapOf("chain" to "forward", "action" to "drop", "disabled" to "no")
                ),
                "wireless_security_profiles" to listOf(mapOf("name" to "private-profile", "mode" to "none"))
            )
        )
        assertTrue(findings.any { it.category == "management-service" && it.severity == "medium" })
        assertTrue(findings.any { it.category == "snmp" })
        assertTrue(findings.any { it.category == "dns" })
        assertTrue(findings.any { it.category == "firewall" })
        assertTrue(findings.any { it.category == "wireless" && it.severity == "high" })
        val safeText = findings.joinToString { "${it.title} ${it.evidence} ${it.recommendation}" }
        assertFalse(safeText.contains("private-profile"))
        assertFalse(safeText.contains("0.0.0.0/0"))
    }

    @Test
    fun `filters disabled firewall rules and avoids claiming a verified exposure`() {
        val findings = AiDoctorDiagnostics.securityFindings(
            mapOf(
                "filter" to listOf(
                    mapOf("chain" to "input", "action" to "accept", "disabled" to "yes"),
                    mapOf("chain" to "input", "action" to "drop", "disabled" to "no")
                ),
                "management_services" to listOf(
                    mapOf("name" to "telnet", "disabled" to "no", "address" to "192.168.1.0/24")
                )
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not flag an SNMP community restricted by the official addresses field`() {
        val findings = AiDoctorDiagnostics.securityFindings(
            mapOf("snmp_access" to listOf(mapOf("addresses" to "192.168.1.0/24", "disabled" to "no")))
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `does not infer final firewall rule from truncated data`() {
        val findings = AiDoctorDiagnostics.securityFindings(
            mapOf("filter" to listOf(mapOf("chain" to "input", "action" to "accept", "disabled" to "no"))),
            truncatedKeys = setOf("filter")
        )
        assertEquals(1, findings.size)
        assertEquals("info", findings.single().severity)
        assertTrue(findings.single().evidence.contains("تجاوز حد القراءة"))
    }

    @Test
    fun `redactor masks passphrases if an unexpected router log includes them`() {
        val output = RouterDiagnosticRedactor.redact("passphrase=\"do not send this\" eap-password: also-secret")
        assertFalse(output.contains("do not send this"))
        assertFalse(output.contains("also-secret"))
    }

    @Test
    fun `redacts complete long secrets before applying the report length limit`() {
        val secret = "Qz9".repeat(250)
        val output = RouterDiagnosticRedactor.redactAndLimit("password=\"$secret\"", 500)
        assertFalse(output.contains("Qz9"))
        assertTrue(output.contains("[REDACTED]"))
    }
}
