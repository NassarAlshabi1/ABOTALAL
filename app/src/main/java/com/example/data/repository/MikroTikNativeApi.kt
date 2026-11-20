package com.example.data.repository

import com.example.util.OperationTelemetry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Native MikroTik RouterOS Binary API Client (Port 8728 / 8729)
 * Implements the RouterOS Binary API used by this app's supported v6 routers.
 * The login handshake also supports legacy v6 MD5 challenge authentication.
 */
class MikroTikNativeApi(
    val host: String,
    val port: Int = 8728,
    private val connectTimeoutMs: Int = 12000,
    private val commandTimeoutMs: Int = 25000,
    private val useSsl: Boolean = false,
    private val sslSocketFactory: SSLSocketFactory? = null,
) {
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private val socketLock = Any()
    private var savedUsername: String = ""
    private var savedPassword: String = ""

    companion object {
        fun testSocket(host: String, port: Int, timeoutMs: Int = 5000): Pair<Boolean, String> {
            val operationId = OperationTelemetry.newOperationId()
            val startedAt = OperationTelemetry.elapsedRealtime()
            return try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.close()
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                Pair(true, "✅ تم الوصول إلى ($host) على المنفذ ($port) عبر TCP.\n\nهذا اختبار وصول للمنفذ فقط، وليس اختبار تسجيل دخول.")
            } catch (e: Exception) {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = if (OperationTelemetry.classify(e) == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.classify(e),
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
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
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        synchronized(socketLock) {
            lastConnectError = null
            return try {
                val s = if (useSsl) {
                    // Explicit SSL: connect the unconnected socket with our own timeout,
                    // then force the handshake immediately so certificate problems surface
                    // here (with a clear log) instead of corrupting the first command read.
                    val ssl = (sslSocketFactory ?: SSLSocketFactory.getDefault()).createSocket() as SSLSocket
                    ssl.tcpNoDelay = true
                    ssl.keepAlive = true
                    ssl.soTimeout = commandTimeoutMs
                    ssl.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    try {
                        ssl.startHandshake()
                    } catch (h: SSLHandshakeException) {
                        throw h
                    }
                    ssl
                } else {
                    Socket()
                }
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = commandTimeoutMs
                if (!useSsl) s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket = s
                inStream = s.getInputStream()
                outStream = s.getOutputStream()
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    transport = if (useSsl) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                true
            } catch (e: Exception) {
                lastConnectError = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                val errorClass = OperationTelemetry.classify(e)
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                    errorClass = errorClass,
                    transport = if (useSsl) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                closeInternal()
                false
            }
        }
    }

    fun ensureConnected(): Boolean {
        synchronized(socketLock) {
            if (isConnected()) return true
            if (savedUsername.isBlank()) return false
            closeInternal()
            if (connect()) {
                val (ok, _) = performLogin(savedUsername, savedPassword)
                if (ok) {
                    return true
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
                    val reason = lastConnectError?.takeIf { it.isNotBlank() }
                    return Pair(
                        false,
                        "تعذر إنشاء اتصال Socket بالراوتر على المنفذ $port" +
                            (if (reason != null) " (${reason})" else "") +
                            ". تأكد من فتح خدمة api في الراوتر والوصول إلى المنفذ الصحيح."
                    )
                }
            }
            return performLogin(user, pass)
        }
    }

    private fun performLogin(user: String, pass: String): Pair<Boolean, String> {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        fun finish(result: Pair<Boolean, String>, errorClass: OperationTelemetry.ErrorClass? = null): Pair<Boolean, String> {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.AUTH_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_AUTH,
                operationId = operationId,
                outcome = if (result.first) OperationTelemetry.Outcome.SUCCESS else OperationTelemetry.Outcome.FAILURE,
                errorClass = errorClass,
                transport = if (useSsl) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            return result
        }
        try {
            // First attempt: Modern ROS v6.43+ and v7 direct login
            writeSentence(listOf("/login", "=name=$user", "=password=$pass"))
            val response = readSentence()

            val status = response.firstOrNull() ?: ""
            if (status == "!done") {
                // Check if challenge response was requested (Older ROS v6 pre-6.43)
                val ret = response.firstOrNull { it.startsWith("=ret=") }
                if (ret != null) {
                    val challengeHex = ret.removePrefix("=ret=")
                    val challengeHash = md5Response(challengeHex, pass)
                    writeSentence(listOf("/login", "=name=$user", "=response=00$challengeHash"))
                    val resp2 = readSentence()
                    if (resp2.firstOrNull() == "!done") {
                        return finish(Pair(true, "تم تسجيل الدخول بنجاح"))
                    }
                    val trap2 = resp2.firstOrNull { it.startsWith("=message=") }?.removePrefix("=message=")
                    return finish(Pair(false, trap2 ?: "اسم المستخدم أو كلمة المرور غير صحيحة"), OperationTelemetry.ErrorClass.AUTHENTICATION)
                }
                return finish(Pair(true, "تم تسجيل الدخول بنجاح"))
            }
            if (status.startsWith("!trap") || status.startsWith("!fatal")) {
                val trap = response.firstOrNull { it.startsWith("=message=") }?.removePrefix("=message=")
                    ?: "اسم المستخدم أو كلمة المرور غير صحيحة"
                if (status.startsWith("!fatal")) closeInternal()
                return finish(Pair(false, trap), OperationTelemetry.ErrorClass.AUTHENTICATION)
            }

            // Anything else (empty reply, EOF, unknown sentence) is a FAILED login —
            // never report success without the router's explicit !done.
            closeInternal()
            return finish(Pair(false, "استجابة غير متوقعة من الراوتر أثناء تسجيل الدخول — تأكد أن المنفذ $port مخصص لخدمة api وليس لخدمة أخرى"), OperationTelemetry.ErrorClass.PARSE)
        } catch (e: Exception) {
            closeInternal()
            return finish(Pair(false, "خطأ أثناء محاولة تسجيل الدخول: ${e.localizedMessage ?: "انتهت المهلة"}"), OperationTelemetry.classify(e))
        }
    }

    @Volatile
    private var lastConnectError: String? = null

    @Volatile
    private var lastCommandError: String? = null
    @Volatile
    private var lastErrorIsTrap = false
    @Volatile
    private var lastCommandErrorClass: OperationTelemetry.ErrorClass? = null

    /** Last !trap/!fatal/socket message recorded by [execute]; null when the last
     *  command succeeded or never ran. */
    val lastError: String?
        get() = synchronized(socketLock) { lastCommandError }

    fun execute(command: String, params: Map<String, String>): List<Map<String, String>> {
        val arr = params.map { "${it.key}=${it.value}" }.toTypedArray()
        return execute(command, *arr)
    }

    /** Like [execute] but reports !trap/!fatal/socket failures instead of silently
     *  returning partial results — use it for mutations where the caller must know
     *  whether the router actually applied the command. */
    fun executeStrict(command: String, vararg params: String): List<Map<String, String>> {
        synchronized(socketLock) {
            val res = executeInternal(command, *params)
            val err = lastCommandError
            if (err != null) throw java.io.IOException(err)
            return res
        }
    }

    fun executeStrict(command: String, params: Map<String, String>): List<Map<String, String>> {
        val arr = params.map { "${it.key}=${it.value}" }.toTypedArray()
        return executeStrict(command, *arr)
    }

    fun execute(command: String, vararg params: String): List<Map<String, String>> {
        synchronized(socketLock) { return executeInternal(command, *params) }
    }

    /** Body of [execute]; MUST be called while holding socketLock so that
     *  [lastCommandError] is observed atomically with the command's own reply. */
    private fun executeInternal(command: String, vararg params: String): List<Map<String, String>> {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        lastCommandError = null
        lastErrorIsTrap = false
        lastCommandErrorClass = null
        if (!isConnected()) {
            if (!ensureConnected()) {
                lastCommandError = "تعذر الاتصال بالراوتر لتنفيذ: $command"
                lastCommandErrorClass = OperationTelemetry.ErrorClass.NETWORK
                recordCommandResult(command, operationId, startedAt)
                return mutableListOf()
            }
        }

        val words = mutableListOf(command)
        for (p in params) {
            words.add(when {
                // Pre-formed attribute (=key=value) and query (?key=value) words pass through
                p.startsWith("=") || p.startsWith("?") -> p
                else -> "=$p"
            })
        }

        try {
            val result = writeAndCollect(command, words)
            recordCommandResult(command, operationId, startedAt)
            return result
        } catch (e: SocketTimeoutException) {
            // A timed-out mutation may already have been applied by RouterOS even
            // though its reply was lost. Never replay it: surface the ambiguous
            // result so callers can verify router state before trying again.
            if (OperationTelemetry.isMutationCommand(command)) {
                closeInternal()
                lastCommandError = "انتهت المهلة أثناء تنفيذ $command؛ قد يكون الراوتر نفّذ الأمر. تحقّق من الحالة قبل إعادة المحاولة."
                lastCommandErrorClass = OperationTelemetry.ErrorClass.TIMEOUT
                recordCommandResult(command, operationId, startedAt)
                return mutableListOf()
            }

            // A one-shot retry is safe for reads: reconnect + re-login, resend once.
            closeInternal()
            lastCommandError = null
            lastCommandErrorClass = null
            if (savedUsername.isNotBlank() && connect() &&
                performLogin(savedUsername, savedPassword).first
            ) {
                try {
                    val result = writeAndCollect(command, words)
                    recordCommandResult(command, operationId, startedAt, retryCount = 1)
                    return result
                } catch (e2: Exception) {
                    lastCommandError = "خطأ في تنفيذ $command: ${e2.message}"
                    lastCommandErrorClass = OperationTelemetry.classify(e2)
                    closeInternal()
                    recordCommandResult(command, operationId, startedAt, retryCount = 1)
                    return mutableListOf()
                }
            }
            lastCommandError = "انتهت مهلة الاتصال أثناء تنفيذ: $command (تعذرت إعادة المحاولة)"
            lastCommandErrorClass = OperationTelemetry.ErrorClass.TIMEOUT
            recordCommandResult(command, operationId, startedAt, retryCount = 1)
            return mutableListOf()
        } catch (e: Exception) {
            lastCommandError = "خطأ في تنفيذ $command: ${e.message}"
            lastCommandErrorClass = OperationTelemetry.classify(e)
            // Socket error: close internal socket to trigger reconnect next time
            closeInternal()
            recordCommandResult(command, operationId, startedAt)
            return mutableListOf()
        }
    }

    private fun recordCommandResult(command: String, operationId: String, startedAt: Long, retryCount: Int = 0) {
        val errorClass = lastCommandErrorClass
        if (errorClass == null && !OperationTelemetry.isMutationCommand(command)) return
        val outcome = when (errorClass) {
            OperationTelemetry.ErrorClass.TIMEOUT -> OperationTelemetry.Outcome.TIMEOUT
            null -> OperationTelemetry.Outcome.SUCCESS
            else -> OperationTelemetry.Outcome.FAILURE
        }
        OperationTelemetry.record(
            event = OperationTelemetry.Event.COMMAND_RESULT,
            operation = OperationTelemetry.Operation.ROUTER_COMMAND,
            operationId = operationId,
            outcome = outcome,
            errorClass = errorClass,
            transport = if (useSsl) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
            commandFamily = OperationTelemetry.commandFamily(command),
            durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
            retryCount = retryCount
        )
    }

    /** Sends [words] and collects !re records until !done. Throws socket/I-O errors;
     *  records !trap/!fatal into [lastCommandError] and returns what arrived so far. */
    private fun writeAndCollect(command: String, words: List<String>): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        writeSentence(words)

        var currentRecord = mutableMapOf<String, String>()
        while (true) {
            val reply = readSentence()
            if (reply.isEmpty()) break
            val replyType = reply.firstOrNull() ?: ""

            if (replyType == "!re") {
                currentRecord = mutableMapOf()
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
                break
            } else if (replyType == "!trap") {
                val msg = reply.firstOrNull { it.startsWith("=message=") }
                    ?.removePrefix("=message=") ?: "رفض الراوتر الأمر"
                lastCommandError = "فشل $command: $msg"
                lastErrorIsTrap = true
                lastCommandErrorClass = OperationTelemetry.ErrorClass.ROUTER_REJECTED
                // Drain the !done sentence that always follows !trap — otherwise the
                // next command reads it as its own reply and wrongly returns empty.
                while (true) {
                    val drain = readSentence()
                    if (drain.isEmpty() || drain.firstOrNull() == "!done") break
                }
                break
            } else if (replyType == "!fatal") {
                lastCommandError = "انقطع اتصال الراوتر أثناء: $command"
                lastCommandErrorClass = OperationTelemetry.ErrorClass.NETWORK
                closeInternal()
                break
            }
        }
        return results
    }

    /** True when the last command failed with !trap (router refused) rather than a
     *  transport problem. Useful for callers deciding whether to fall back. */
    fun lastErrorWasTrap(): Boolean {
        return synchronized(socketLock) { lastErrorIsTrap }
    }

    fun ping(): Boolean {
        return try {
            val res = execute("/system/identity/print")
            // Success requires BOTH a real reply and no trap/fatal recorded for it.
            val ok = res.isNotEmpty() && synchronized(socketLock) { lastCommandError } == null
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun reboot(): Boolean = sendSystemCommand("/system/reboot")

    fun shutdown(): Boolean = sendSystemCommand("/system/shutdown")

    /** Sends a system-halting command (/system/reboot, /system/shutdown) and reports
     *  honestly: a !trap (e.g. missing permission) is a failure; a !done, an EOF or a
     *  connection reset right after sending means the router accepted the command and
     *  is going down. */
    private fun sendSystemCommand(command: String): Boolean {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        fun finish(accepted: Boolean): Boolean {
            val errorClass = lastCommandErrorClass
            OperationTelemetry.record(
                event = OperationTelemetry.Event.COMMAND_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_COMMAND,
                operationId = operationId,
                outcome = if (accepted) OperationTelemetry.Outcome.SUCCESS else if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                errorClass = if (accepted) null else errorClass,
                transport = if (useSsl) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                commandFamily = OperationTelemetry.commandFamily(command),
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            return accepted
        }
        synchronized(socketLock) {
            lastCommandError = null
            lastCommandErrorClass = null
            if (!isConnected()) {
                if (!ensureConnected()) {
                    lastCommandError = "تعذر الاتصال بالراوتر لإرسال: $command"
                    lastCommandErrorClass = OperationTelemetry.ErrorClass.NETWORK
                    return finish(false)
                }
            }
            return try {
                writeSentence(listOf(command))
                val reply = readSentence()
                val first = reply.firstOrNull() ?: ""
                when {
                    first.startsWith("!trap") -> {
                        val msg = reply.firstOrNull { it.startsWith("=message=") }
                            ?.removePrefix("=message=") ?: "رفض الراوتر الأمر"
                        lastCommandError = "فشل $command: $msg"
                        lastCommandErrorClass = OperationTelemetry.ErrorClass.ROUTER_REJECTED
                        // drain trailing !done so the socket stays usable if we survived
                        try {
                            while (true) {
                                val drain = readSentence()
                                if (drain.isEmpty() || drain.firstOrNull() == "!done") break
                            }
                        } catch (_: Exception) {}
                        finish(false)
                    }
                    first == "!done" -> finish(true)
                    // Empty reply / EOF after send: the router tore the connection down
                    // while executing — treat as accepted, it is going down.
                    else -> {
                        closeInternal()
                        finish(true)
                    }
                }
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                if (e is EOFException || msg.contains("reset") || msg.contains("econnreset") ||
                    msg.contains("end of stream") || msg.contains("broken pipe")
                ) {
                    // Router dropped the socket while going down — command was accepted.
                    closeInternal()
                    finish(true)
                } else if (e is SocketTimeoutException) {
                    // No reply and no close within the timeout — the command was sent and
                    // the router often reboots without answering. Report accepted.
                    lastCommandErrorClass = OperationTelemetry.ErrorClass.TIMEOUT
                    finish(true)
                } else {
                    lastCommandError = "خطأ في إرسال $command: ${e.message}"
                    lastCommandErrorClass = OperationTelemetry.classify(e)
                    closeInternal()
                    finish(false)
                }
            }
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
        RouterOsApiWordCodec.writeWord(out, word)
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
        return RouterOsApiWordCodec.readWord(input)
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
