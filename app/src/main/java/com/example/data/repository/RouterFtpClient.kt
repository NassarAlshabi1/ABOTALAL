package com.example.data.repository

import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.CoroutineContext

/** Minimal passive-mode FTP client for downloading RouterOS files. FTP is unencrypted. */
internal class RouterFtpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 30_000
) {
    fun download(
        fileName: String,
        output: OutputStream,
        expectedSize: Long = 0L,
        coroutineContext: CoroutineContext,
        onProgress: (Long) -> Unit = {}
    ): Long {
        require(fileName.endsWith(".backup", ignoreCase = true) &&
            fileName.matches(Regex("[A-Za-z0-9._ -]+"))) { "اسم ملف النسخة غير صالح" }

        val control = Socket()
        var data: Socket? = null
        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null
        try {
            control.connect(InetSocketAddress(host, port), connectTimeoutMs)
            control.soTimeout = readTimeoutMs
            reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.US_ASCII))
            writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.US_ASCII))
            expect(readReply(reader), setOf(220), "اتصال FTP")

            command(writer, reader, "USER $username", setOf(230, 331))
                .also { reply ->
                    if (reply.code == 331) command(writer, reader, "PASS $password", setOf(230))
                }
            command(writer, reader, "TYPE I", setOf(200))
            val passiveReply = command(writer, reader, "PASV", setOf(227))
            val dataPort = parsePassivePort(passiveReply.message)

            // Use the already-authenticated control host, not the address advertised by
            // PASV, which may be a private/NAT address unrelated to the active VPN route.
            data = Socket()
            data.connect(InetSocketAddress(host, dataPort), connectTimeoutMs)
            data.soTimeout = readTimeoutMs

            command(writer, reader, "RETR $fileName", setOf(125, 150))
            val count = data.getInputStream().use { input ->
                copyWithProgress(input, output, expectedSize, coroutineContext, onProgress)
            }
            data.close()
            data = null
            expect(readReply(reader), setOf(226, 250), "اكتمال تنزيل FTP")
            output.flush()
            if (expectedSize > 0 && count != expectedSize) {
                throw IOException("حجم الملف المنزّل غير مكتمل: المتوقع $expectedSize بايت، والمستلم $count بايت")
            }
            command(writer, reader, "QUIT", setOf(221))
            return count
        } finally {
            try { data?.close() } catch (_: Exception) { }
            try { reader?.close() } catch (_: Exception) { }
            try { writer?.close() } catch (_: Exception) { }
            try { control.close() } catch (_: Exception) { }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        coroutineContext: CoroutineContext,
        onProgress: (Long) -> Unit
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var lastReported = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            if (total - lastReported >= PROGRESS_INTERVAL_BYTES || (expectedSize > 0 && total >= expectedSize)) {
                onProgress(total)
                lastReported = total
            }
        }
        if (total != lastReported) onProgress(total)
        return total
    }

    private fun command(
        writer: BufferedWriter,
        reader: BufferedReader,
        value: String,
        acceptedCodes: Set<Int>
    ): Reply {
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) throw IOException("أمر FTP غير صالح")
        writer.write(value)
        writer.write("\r\n")
        writer.flush()
        return expect(readReply(reader), acceptedCodes, value.substringBefore(' '))
    }

    private fun expect(reply: Reply, acceptedCodes: Set<Int>, operation: String): Reply {
        if (reply.code !in acceptedCodes) {
            throw IOException("فشل $operation عبر FTP (${reply.code}): ${reply.message}")
        }
        return reply
    }

    private fun readReply(reader: BufferedReader): Reply {
        val first = reader.readLine() ?: throw IOException("انقطع اتصال FTP قبل استلام الرد")
        if (first.length < 3) throw IOException("رد FTP غير صالح: $first")
        val code = first.substring(0, 3).toIntOrNull() ?: throw IOException("رد FTP غير صالح: $first")
        val lines = mutableListOf(first)
        if (first.length > 3 && first[3] == '-') {
            val terminator = "$code "
            while (true) {
                val line = reader.readLine() ?: throw IOException("انقطع رد FTP متعدد الأسطر")
                lines += line
                if (line.startsWith(terminator)) break
            }
        }
        return Reply(code, lines.joinToString("\n"))
    }

    private fun parsePassivePort(message: String): Int {
        val tuple = Regex("\\((\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3})\\)")
            .find(message)?.groupValues?.get(1)
            ?: throw IOException("لم يقدّم خادم FTP منفذ PASV صالحًا")
        val values = tuple.split(',').map { it.toIntOrNull() ?: throw IOException("منفذ PASV غير صالح") }
        if (values.any { it !in 0..255 }) throw IOException("رد PASV يحتوي قيمًا خارج النطاق")
        val port = values[4] * 256 + values[5]
        if (port !in 1..65535) throw IOException("منفذ PASV خارج النطاق")
        return port
    }

    private data class Reply(val code: Int, val message: String)

    private companion object {
        const val PROGRESS_INTERVAL_BYTES = 512L * 1024L
    }
}
