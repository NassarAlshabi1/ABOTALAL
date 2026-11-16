package com.example

import com.example.data.repository.RouterFtpClient
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class RouterFtpClientTest {
    @Test
    fun `downloads binary data through passive FTP and verifies length`() {
        val payload = ByteArray(180_123) { ((it * 37) % 256).toByte() }
        val fixture = startServer(payload)
        try {
            val output = ByteArrayOutputStream()
            val byteCount = client(fixture.control.localPort).download(
                fileName = "router.backup",
                output = output,
                expectedSize = payload.size.toLong(),
                coroutineContext = Dispatchers.IO
            )
            assertEquals(payload.size.toLong(), byteCount)
            assertArrayEquals(payload, output.toByteArray())
            fixture.serverFuture.get(5, TimeUnit.SECONDS)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rejects a transfer whose byte count differs from the router file size`() {
        val payload = ByteArray(4096) { it.toByte() }
        val fixture = startServer(payload)
        try {
            assertThrows(IOException::class.java) {
                client(fixture.control.localPort).download(
                    fileName = "router.backup",
                    output = ByteArrayOutputStream(),
                    expectedSize = payload.size.toLong() + 1,
                    coroutineContext = Dispatchers.IO
                )
            }
            fixture.serverFuture.get(5, TimeUnit.SECONDS)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rejects path injection instead of sending it to FTP server`() {
        assertThrows(IllegalArgumentException::class.java) {
            client(21).download(
                fileName = "../router.backup",
                output = ByteArrayOutputStream(),
                coroutineContext = Dispatchers.IO
            )
        }
    }

    private fun client(port: Int) = RouterFtpClient(
        host = "127.0.0.1",
        port = port,
        username = "test-user",
        password = "test-pass"
    )

    private fun startServer(payload: ByteArray): Fixture {
        val control = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            control.accept().use { socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))
                fun reply(text: String) { output.write(text + "\r\n"); output.flush() }
                fun readCommand(expected: String) {
                    val actual = input.readLine()
                    check(actual == expected) { "Expected '$expected', got '$actual'" }
                }
                reply("220 RouterOS FTP ready")
                readCommand("USER test-user"); reply("331 Password required")
                readCommand("PASS test-pass"); reply("230 Logged in")
                readCommand("TYPE I"); reply("200 Binary mode")
                readCommand("PASV")
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { passive ->
                    val port = passive.localPort
                    reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                    readCommand("RETR router.backup")
                    reply("150 Opening binary data connection")
                    passive.accept().use { data -> data.getOutputStream().use { it.write(payload) } }
                    reply("226 Transfer complete")
                }
                val quit = input.readLine()
                if (quit != null) {
                    check(quit == "QUIT") { "Expected QUIT, got '$quit'" }
                    reply("221 Goodbye")
                }
            }
        }
        return Fixture(control, executor, future)
    }

    private data class Fixture(val control: ServerSocket, val executor: ExecutorService, val serverFuture: Future<*>) {
        fun close() {
            control.close()
            executor.shutdownNow()
        }
    }
}
