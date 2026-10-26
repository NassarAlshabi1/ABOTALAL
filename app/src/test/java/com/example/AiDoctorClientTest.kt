package com.example

import com.example.data.repository.AiDoctorClient
import com.example.data.repository.RouterDiagnosticRedactor
import com.example.data.repository.RouterOsV6AdviceGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AiDoctorClientTest {
    @Test
    fun `parses multiple custom HTTP headers`() {
        val parsed = AiDoctorClient.parseHeaders("X-Client: netcraft\nX-Trace: 42")
        assertEquals("netcraft", parsed["X-Client"])
        assertEquals("42", parsed["X-Trace"])
    }

    @Test
    fun `rejects malformed additional header line`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiDoctorClient.parseHeaders("X-Client has no colon")
        }
    }

    @Test
    fun `rejects transport controlled headers`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiDoctorClient.parseHeaders("Host: attacker.example")
        }
    }

    @Test
    fun `allows unauthenticated local HTTP endpoint but rejects credentials over HTTP`() {
        assertTrue(AiDoctorClient.validateApiEndpoint("http://192.168.1.10:8080/v1/chat/completions", "", "").startsWith("http://"))
        assertThrows(IllegalArgumentException::class.java) {
            AiDoctorClient.validateApiEndpoint("http://192.168.1.10:8080/v1/chat/completions", "secret", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiDoctorClient.validateApiEndpoint("http://api.example.com/v1/chat/completions", "", "")
        }
        assertTrue(AiDoctorClient.validateApiEndpoint("https://api.example.com/v1/chat/completions", "secret", "").startsWith("https://"))
    }

    @Test
    fun `rejects API secrets embedded in endpoint query`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiDoctorClient.validateUrl("https://api.example.com/v1/chat/completions?api_key=secret")
        }
    }

    @Test
    fun `redacts common credential formats from router log text`() {
        val input = """password=hidden token: abc123 "api-key": "xyz" Authorization: Bearer eyJhbGci.abc"""
        val output = RouterDiagnosticRedactor.redact(input)
        assertFalse(output.contains("hidden"))
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("xyz"))
        assertFalse(output.contains("eyJhbGci"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun `blocks known RouterOS v7 command paths for a v6 router`() {
        assertTrue(RouterOsV6AdviceGuard.findKnownV7Paths("6.49.17", "Use /interface/wifi/print").isNotEmpty())
        assertTrue(RouterOsV6AdviceGuard.findKnownV7Paths("7.14.3", "Use /interface/wifi/print").isEmpty())
        assertTrue(RouterOsV6AdviceGuard.findKnownV7Paths("6.49.17", "Use /tool/user-manager/user/print").isEmpty())
    }

    @Test
    fun `blocks v6-only and v7-only User Manager paths in both directions`() {
        assertTrue(
            RouterOsV6AdviceGuard.findIncompatiblePaths("6.49.17", "Use /user-manager/user/print").isNotEmpty()
        )
        assertTrue(
            RouterOsV6AdviceGuard.findIncompatiblePaths("7.14.3", "Use /tool/user-manager/user/print").isNotEmpty()
        )
        assertTrue(
            RouterOsV6AdviceGuard.findIncompatiblePaths("7.14.3", "Use /user-manager/user/print").isEmpty()
        )
        assertTrue(
            RouterOsV6AdviceGuard.findIncompatiblePaths("6.49.17", "Use /tool/user-manager/user/print").isEmpty()
        )
        assertTrue(
            RouterOsV6AdviceGuard.findIncompatiblePaths("unknown", "Use /tool/user-manager/user/print").isEmpty()
        )
    }

    @Test
    fun `connector reachability probe sends no provider credentials`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String> {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                        if (line.isEmpty()) break
                    }
                }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                request
            }
        }
        try {
            val result = AiDoctorClient.testConnector("http://127.0.0.1:${server.localPort}/health")
            val request = future.get(5, TimeUnit.SECONDS)
            assertTrue(result.contains("HTTP 200"))
            assertTrue(request.startsWith("GET /health"))
            assertFalse(request.contains("Authorization", ignoreCase = true))
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
