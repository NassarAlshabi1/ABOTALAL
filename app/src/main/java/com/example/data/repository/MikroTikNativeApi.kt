package com.example.data.repository

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Rich result of one RouterOS API sentence execution so callers can tell an
 * empty-but-successful reply apart from a failure:
 *  - [ApiResult.Success] — router replied !done (data may legitimately be empty)
 *  - [ApiResult.Trap]    — router replied !trap (command refused / bad input)
 *  - [ApiResult.Error]   — transport-level failure (timeout, socket error, not connected)
 */
sealed class ApiResult {
    /** Rows returned by the router; empty for failures and for successful prints with no rows. */
    abstract val data: List<Map<String, String>>

    data class Success(override val data: List<Map<String, String>>) : ApiResult()
    data class Trap(val message: String) : ApiResult() {
        override val data: List<Map<String, String>> get() = emptyList()
    }
    data class Error(val message: String) : ApiResult() {
        override val data: List<Map<String, String>> get() = emptyList()
    }

    val isSuccess: Boolean get() = this is Success
    /** Error text when this is [Trap] or [Error]; null on success. */
    val errorMessage: String?
        get() = when (this) {
            is Trap -> message
            is Error -> message
            else -> null
        }

    /** Rows or throw — for mutations that must not silently pass on failure. */
    fun getOrThrow(): List<Map<String, String>> = when (this) {
        is Success -> data
        is Trap -> throw IOException(message)
        is Error -> throw IOException(message)
    }
}

/**
 * Native MikroTik RouterOS Binary API Client (Port 8728 / 8729)
 * Supports ROS v6 and v7 authentication and sentence command execution.
 *
 * Certificate policy: MikroTik routers ship self-signed certificates by default.
 * Selecting the SSL transport ([useSsl] / port 8729) is the explicit, per-connection
 * opt-in under which the router's own certificate is accepted. Without that opt-in
 * no SSL connection is made at all (plain 8728), and no other traffic of this
 * client relaxes certificate verification.
 */
class MikroTikNativeApi(
    val host: String,
    val port: Int = 8728,
    private val connectTimeoutMs: Int = 12000,
    private val commandTimeoutMs: Int = 25000,
    private val useSsl: Boolean = false
) {
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private val socketLock = Any()
    private var savedUsername: String = ""
    private var savedPassword: String = ""

    companion object {
        private const val TAG = "MikroTikNativeApi"

        fun testSocket(host: String, port: Int, timeoutMs: Int = 5000): Pair<Boolean, String> {
            return try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.close()
                Pair(true, "✅ الاتصال ناجح ومكتمل!\n\nتم الوصول إلى الراوتر ($host) على المنفذ ($port) بنجاح، ومنفذ الخدمة جاهز لاستقبال الأوامر.")
            } catch (e: Exception) {
                Log.w(TAG, "Test socket failed to $host:$port", e)
                val rawMsg = e.localizedMessage ?: ""

                // Diagnose alternative common ports on same host to help the user
                var openAltPort: Int? = null
                val altPorts = listOf(8728, 80, 8291, 8729).filter { it != port }
                for (alt in altPorts) {
                    try {
                        val testSock = Socket()
                        testSock.connect(InetSocketAddress(host, alt), 600)
                        testSock.close()
                        openAltPort = alt
                        break
                    } catch (_: Exception) {}
                }

                val altHint = when (openAltPort) {
                    80 -> "\n\n💡 ملاحظة تشخيصية:\nعنوان الآي بي ($host) متصل وجهاز الراوتر يعمل، والمنفذ 80 (الويب) مفتوح! ولكن منفذ الـ API ($port) مغلق.\nلتفعيله: افتح WinBox -> ثم IP -> Services -> واضغط على (api) واختر Enable (✔️)."
                    8291 -> "\n\n💡 ملاحظة تشخيصية:\nالراوتر متصل ومنفذ WinBox (8291) مفتوح! لكن منفذ الـ API ($port) لم يتم تفعيله في قائمة IP -> Services."
                    8729 -> "\n\n💡 ملاحظة تشخيصية:\nالراوتر يعمل ومنفذ API المشفر (8729) مفتوح! يرجى تجربة المنفذ 8729 مع تفعيل خيار SSL."
                    8728 -> "\n\n💡 ملاحظة تشخيصية:\nمنفذ الـ API الافتراضي (8728) مفتوح على الراوتر! يرجى استخدام المنفذ 8728 بدلاً من $port."
                    else -> ""
                }

                val friendlyMsg = when {
                    rawMsg.contains("ECONNREFUSED", ignoreCase = true) || rawMsg.contains("refused", ignoreCase = true) -> {
                        "⚠️ تم رفض الاتصال من الراوتر على المنفذ ($port).\n\n" +
                        "💡 السبب والحل:\n" +
                        "• الآي بي ($host) صحيح، لكن خدمة API معطلة في نظام الراوتر أو تعمل على منفذ مختلف.\n\n" +
                        "خطوات التفعيل عبر WinBox:\n" +
                        "1. افتح قائمة IP ثم Services\n" +
                        "2. اضغط على خدمة (api) واضغط زر تفعيل (Enable ✔️)\n" +
                        "3. تأكد أن المنفذ المحدد هناك هو $port." +
                        altHint
                    }
                    rawMsg.contains("ETIMEDOUT", ignoreCase = true) || rawMsg.contains("timed out", ignoreCase = true) -> {
                        "⚠️ انتهت مهلة الاتصال بالراوتر ($host:$port).\n\n" +
                        "💡 الأسباب المحتملة والحل:\n" +
                        "• تأكد أن الهاتف متصل بنفس شبكة الواي فاي للراوتر.\n" +
                        "• تأكد من صحة الآي بي ($host) وأنه ضمن نفس مجال شبكتك."
                    }
                    rawMsg.contains("EHOSTUNREACH", ignoreCase = true) || rawMsg.contains("No route to host", ignoreCase = true) -> {
                        "⚠️ تعذر الوصول إلى عنوان الراوتر ($host).\n\n" +
                        "💡 السبب:\n" +
                        "الهاتف غير متصل بشبكة الراوتر، أو تم إدخال آي بي خاطئ. يرجى الاتصال بشبكة الواي فاي للراوتر والمحاولة مجدداً."
                    }
                    else -> {
                        "⚠️ تعذر الاتصال بـ $host:$port\n\nالسبب: ${e.localizedMessage ?: "المنفذ مغلق أو الشبكة غير متاحة"}\n\n💡 تأكد من تشغيل خدمة api في قائمة IP -> Services في المايكروتيك." + altHint
                    }
                }
                Pair(false, friendlyMsg)
            }
        }
    }

    fun isConnected(): Boolean {
        synchronized(socketLock) {
            val s = socket
            return s != null && s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
        }
    }

    fun connect(): Boolean {
        synchronized(socketLock) {
            return try {
                // NOTE: keep the plain-socket path in statement form (no `apply {}`):
                // the lambda form breaks under Robolectric's bytecode instrumentation.
                val s: Socket = if (useSsl) {
                    // SSL transport (explicit opt-in): plain socket first so the connect
                    // timeout applies, then TLS layered on top + explicit handshake.
                    createSslSocket()
                } else {
                    val plain = Socket()
                    plain.tcpNoDelay = true
                    plain.keepAlive = true
                    plain.soTimeout = commandTimeoutMs
                    plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    plain
                }
                socket = s
                inStream = s.getInputStream()
                outStream = s.getOutputStream()
                Log.d(TAG, "Socket connected to $host:$port (ssl=$useSsl)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect socket to $host:$port", e)
                closeInternal()
                false
            }
        }
    }

    /**
     * Builds the SSL connection with an explicit handshake:
     * plain socket first (so the connect timeout actually applies), then TLS layered
     * on top via [SSLSocketFactory.createSocket] and [SSLSocket.startHandshake].
     */
    private fun createSslSocket(): SSLSocket {
        // Explicit per-connection opt-in: the caller selected the API-SSL transport,
        // accepting the router's own (typically self-signed) certificate.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())

        val plain = Socket()
        plain.tcpNoDelay = true
        plain.keepAlive = true
        plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        plain.soTimeout = commandTimeoutMs

        val ssl = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.startHandshake() // fail fast on TLS problems instead of first read/write
        ssl.tcpNoDelay = true
        ssl.keepAlive = true
        return ssl
    }

    fun ensureConnected(): Boolean {
        synchronized(socketLock) {
            if (isConnected()) return true
            if (savedUsername.isBlank()) return false
            Log.w(TAG, "Socket disconnected, attempting auto-reconnect to $host:$port...")
            closeInternal()
            if (connect()) {
                val (ok, msg) = performLogin(savedUsername, savedPassword)
                if (ok) {
                    Log.i(TAG, "Auto-reconnect & re-login succeeded!")
                    return true
                } else {
                    Log.e(TAG, "Auto-reconnect login failed: $msg")
                }
            }
            return false
        }
    }

    fun login(user: String, pass: String): Pair<Boolean, String> {
        synchronized(socketLock) {
            savedUsername = user
            savedPassword = pass
            if (!isConnected()) {
                if (!connect()) {
                    return Pair(false, "تعذر إنشاء اتصال Socket بالراوتر على المنفذ $port. تأكد من فتح خدمة api في الراوتر والاتصال بنفس شبكة الراوتر.")
                }
            }
            return performLogin(user, pass)
        }
    }

    private fun performLogin(user: String, pass: String): Pair<Boolean, String> {
        try {
            // First attempt: Modern ROS v6.43+ and v7 direct login
            writeSentence(listOf("/login", "=name=$user", "=password=$pass"))
            val response = readSentence()

            val status = response.firstOrNull() ?: ""
            if (status == "!done") {
                // Check if challenge response was requested (Older ROS v6)
                val ret = response.firstOrNull { it.startsWith("=ret=") }
                if (ret != null) {
                    val challengeHex = ret.removePrefix("=ret=")
                    val challengeHash = md5Response(challengeHex, pass)
                    writeSentence(listOf("/login", "=name=$user", "=response=00$challengeHash"))
                    val resp2 = readSentence()
                    if (resp2.firstOrNull() == "!done") {
                        return Pair(true, "تم تسجيل الدخول بنجاح")
                    } else {
                        val trap = resp2.firstOrNull { it.startsWith("=message=") }?.removePrefix("=message=")
                        return Pair(false, trap ?: "اسم المستخدم أو كلمة المرور غير صحيحة")
                    }
                }
                return Pair(true, "تم تسجيل الدخول بنجاح")
            } else if (status.startsWith("!trap")) {
                val trap = response.firstOrNull { it.startsWith("=message=") }?.removePrefix("=message=")
                return Pair(false, trap ?: "اسم المستخدم أو كلمة المرور غير صحيحة")
            } else if (status == "!fatal") {
                val msg = response.firstOrNull { it.startsWith("=message=") }?.removePrefix("=message=")
                return Pair(false, msg ?: "انقطع الاتصال بالراوتر أثناء تسجيل الدخول")
            }

            // Anything else (!re, empty, garbage) is NOT a successful login.
            return Pair(false, "رد غير متوقع من الراوتر أثناء تسجيل الدخول: ${response.firstOrNull() ?: "لا يوجد رد"}")
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            closeInternal()
            return Pair(false, "خطأ أثناء محاولة تسجيل الدخول: ${e.localizedMessage ?: "انتهت المهلة"}")
        }
    }

    @Volatile
    private var lastCommandError: String? = null

    /** Last !trap/!fatal/socket message recorded by [execute]; null when the last
     *  command succeeded or never ran. */
    val lastError: String?
        get() = synchronized(socketLock) { lastCommandError }

    fun execute(command: String, params: Map<String, String>): ApiResult {
        val words = params.map { (k, v) -> formatAttribute(k, v) }
        return execute(command, *words.toTypedArray())
    }

    private fun formatAttribute(key: String, value: String): String = "=$key=$value"

    /** Like [execute] but throws on !trap/!fatal/socket failures instead of silently
     *  returning partial results — use it for mutations where the caller must know
     *  whether the router actually applied the command. */
    fun executeStrict(command: String, vararg params: String): List<Map<String, String>> {
        synchronized(socketLock) {
            when (val res = executeWithTimeoutRetry(command, *params)) {
                is ApiResult.Success -> return res.data
                else -> throw IOException(res.errorMessage ?: "فشل غير معروف للأمر $command")
            }
        }
    }

    fun executeStrict(command: String, params: Map<String, String>): List<Map<String, String>> {
        val words = params.map { (k, v) -> formatAttribute(k, v) }
        return executeStrict(command, *words.toTypedArray())
    }

    fun execute(command: String, vararg params: String): ApiResult {
        synchronized(socketLock) { return executeWithTimeoutRetry(command, *params) }
    }

    /**
     * Runs a command; on a read timeout the session may simply have stalled (router
     * busy on a large print), so the socket is rebuilt, the login replayed and the
     * command retried exactly once before the failure is reported. Holding
     * [socketLock] keeps the retry atomic with respect to other commands.
     */
    private fun executeWithTimeoutRetry(command: String, vararg params: String): ApiResult {
        val first = executeOnce(command, *params)
        if (first !is ApiResult.Error) return first
        val retriable = first.message.contains("انتهت المهلة") || first.message.contains("timeout", true)
        if (!retriable) return first

        Log.w(TAG, "Timeout on '$command' — reconnecting and retrying once")
        closeInternal()
        val reconnectOk = savedUsername.isBlank() || ensureConnected()
        if (!reconnectOk) {
            return ApiResult.Error("تعذر إعادة الاتصال بالراوتر بعد انتهاء المهلة")
        }
        return executeOnce(command, *params)
    }

    /** Single attempt. MUST be called while holding [socketLock]. */
    private fun executeOnce(command: String, vararg params: String): ApiResult {
        lastCommandError = null
        if (!isConnected()) {
            if (!ensureConnected()) {
                lastCommandError = "تعذر الاتصال بالراوتر لتنفيذ: $command"
                return ApiResult.Error(lastCommandError!!)
            }
        }

        try {
            val words = mutableListOf(command)
            for (p in params) {
                // Pass RouterOS word forms through untouched: "=key=value" attributes,
                // "?key=value" query words, "<key" / ">key" command modifiers. Anything
                // else is a bare attribute value ("=value" form) for convenience.
                words.add(
                    when {
                        p.startsWith("=") -> p
                        p.startsWith("?") -> p
                        p.startsWith("<") -> p
                        p.startsWith(">") -> p
                        p == ".tag" -> p
                        else -> "=$p"
                    }
                )
            }
            writeSentence(words)

            val results = mutableListOf<Map<String, String>>()
            while (true) {
                val reply = readSentence()
                if (reply.isEmpty()) break
                val replyType = reply.firstOrNull() ?: ""

                if (replyType == "!re") {
                    var currentRecord = mutableMapOf<String, String>()
                    for (word in reply.drop(1)) {
                        if (word.startsWith("=")) {
                            val parts = word.substring(1).split("=", limit = 2)
                            if (parts.size == 2) {
                                currentRecord[parts[0]] = parts[1]
                            }
                        }
                    }
                    results.add(currentRecord)
                } else if (replyType == "!done") {
                    val doneMap = mutableMapOf<String, String>()
                    for (word in reply.drop(1)) {
                        if (word.startsWith("=")) {
                            val parts = word.substring(1).split("=", limit = 2)
                            if (parts.size == 2) {
                                doneMap[parts[0]] = parts[1]
                            }
                        }
                    }
                    if (doneMap.isNotEmpty() && results.isEmpty()) {
                        results.add(doneMap)
                    }
                    return ApiResult.Success(results)
                } else if (replyType == "!trap") {
                    val msg = reply.firstOrNull { it.startsWith("=message=") }
                        ?.removePrefix("=message=") ?: "رفض الراوتر الأمر"
                    Log.w(TAG, "Command trap response for $command: $reply")
                    lastCommandError = "فشل $command: $msg"
                    // Drain the !done sentence that always follows !trap — otherwise the
                    // next command reads it as its own reply and wrongly returns empty.
                    while (true) {
                        val drain = readSentence()
                        if (drain.isEmpty() || drain.firstOrNull() == "!done") break
                    }
                    return ApiResult.Trap(lastCommandError!!)
                } else if (replyType == "!fatal") {
                    Log.e(TAG, "Command fatal response for $command: $reply")
                    lastCommandError = "انقطع اتصال الراوتر أثناء: $command"
                    closeInternal()
                    return ApiResult.Error(lastCommandError!!)
                }
            }
            // Stream ended without !done — treat as transport failure, not success.
            lastCommandError = "انقطع الاتصال أثناء تنفيذ $command قبل اكتمال الرد"
            closeInternal()
            return ApiResult.Error(lastCommandError!!)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Execute timeout for $command", e)
            lastCommandError = "انتهت المهلة (25 ثانية) أثناء تنفيذ $command: ${e.message}"
            return ApiResult.Error(lastCommandError!!)
        } catch (e: Exception) {
            Log.e(TAG, "Execute command error for $command", e)
            lastCommandError = "خطأ في تنفيذ $command: ${e.message}"
            // Socket error: close internal socket to trigger reconnect next time
            closeInternal()
            return ApiResult.Error(lastCommandError!!)
        }
    }

    /** Session liveness check — a successful empty reply still proves we are connected. */
    fun ping(): Boolean {
        return try {
            execute("/system/identity/print").isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /** Reboot the router; true only when the router acknowledged the command (!done). */
    fun reboot(): Boolean {
        return try {
            execute("/system/reboot").isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "reboot failed", e)
            false
        }
    }

    /** Shutdown the router; true only when the router acknowledged the command (!done). */
    fun shutdown(): Boolean {
        return try {
            execute("/system/shutdown").isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "shutdown failed", e)
            false
        }
    }

    fun close() {
        synchronized(socketLock) {
            savedUsername = ""
            savedPassword = ""
            closeInternal()
        }
    }

    private fun closeInternal() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inStream = null
        outStream = null
    }

    private fun writeSentence(words: List<String>) {
        val out = outStream ?: return
        for (w in words) {
            writeWord(out, w)
        }
        out.write(0) // end of sentence
        out.flush()
    }

    private fun writeWord(out: OutputStream, word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        when {
            len < 0x80 -> {
                out.write(len)
            }
            len < 0x4000 -> {
                out.write((len shr 8) or 0x80)
                out.write(len and 0xFF)
            }
            len < 0x200000 -> {
                out.write((len shr 16) or 0xC0)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                // 5-byte form: 0xF0 marker followed by the full 32-bit length.
                out.write(0xF0)
                out.write((len shr 24) and 0xFF)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(bytes)
    }

    private fun readSentence(): List<String> {
        val list = mutableListOf<String>()
        val input = inStream ?: return list
        while (true) {
            val word = readWord(input) ?: break
            if (word.isEmpty()) {
                break
            }
            list.add(word)
        }
        return list
    }

    private fun readWord(input: InputStream): String? {
        val firstByte = input.read()
        if (firstByte == -1) return null
        if (firstByte == 0) return ""

        val len = when {
            (firstByte and 0x80) == 0 -> firstByte
            (firstByte and 0xC0) == 0x80 -> {
                val b2 = input.read()
                ((firstByte and 0x3F) shl 8) or b2
            }
            (firstByte and 0xE0) == 0xC0 -> {
                val b2 = input.read()
                val b3 = input.read()
                ((firstByte and 0x1F) shl 16) or (b2 shl 8) or b3
            }
            firstByte == 0xF0 -> {
                // 5-byte form: 0xF0 followed by four length bytes.
                val b2 = input.read()
                val b3 = input.read()
                val b4 = input.read()
                val b5 = input.read()
                ((b2 and 0xFF) shl 24) or ((b3 and 0xFF) shl 16) or ((b4 and 0xFF) shl 8) or (b5 and 0xFF)
            }
            else -> return ""
        }

        if (len < 0) return ""
        val buffer = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val r = input.read(buffer, offset, len - offset)
            if (r == -1) break
            offset += r
        }
        return String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun md5Response(challengeHex: String, pass: String): String {
        return try {
            val challengeBytes = hexStringToByteArray(challengeHex)
            val md = MessageDigest.getInstance("MD5")
            md.update(0.toByte())
            md.update(pass.toByteArray(Charsets.UTF_8))
            md.update(challengeBytes)
            val digest = md.digest()
            bytesToHex(digest)
        } catch (e: Exception) {
            ""
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789abcdef".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
