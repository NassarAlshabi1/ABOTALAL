package com.example.data.repository

import com.example.util.OperationTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Client for user-configured OpenAI-compatible chat-completions endpoints. */
internal object AiDoctorClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Never forward custom credential headers to a redirect target.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun complete(
        endpointUrl: String,
        apiKey: String,
        modelName: String,
        additionalHeaders: String,
        systemPrompt: String,
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        var transport = OperationTelemetry.Transport.HTTPS
        try {
        val model = modelName.trim()
        require(model.isNotBlank()) { "أدخل اسم النموذج الذي توفره خدمتك؛ لا يفرض التطبيق اسمًا محددًا." }
        val endpoint = validateApiEndpoint(endpointUrl, apiKey, additionalHeaders)
        if (URI(endpoint).scheme == "http") transport = OperationTelemetry.Transport.LOCAL
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder().url(endpoint).post(body)
        addConfiguredHeaders(requestBuilder, additionalHeaders, apiKey)
        val content = http.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("استجاب endpoint برمز HTTP ${response.code}؛ تحقق من الرابط ومفتاح API والنموذج والترويسات.")
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("استجابة API ليست JSON متوافقًا؛ لم تُعرض بيانات حساسة من الاستجابة.")
            }
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
                ?.let(::contentAsText)
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("استجابة الخدمة لا تحتوي choices[0].message.content كما يتوقع معيار OpenAI-compatible.")
        }
        OperationTelemetry.record(
            event = OperationTelemetry.Event.AI_REQUEST_RESULT,
            operation = OperationTelemetry.Operation.AI_COMPLETION,
            operationId = operationId,
            outcome = OperationTelemetry.Outcome.SUCCESS,
            transport = transport,
            durationMs = OperationTelemetry.elapsedRealtime() - startedAt
        )
        content
        } catch (e: Exception) {
            val errorClass = OperationTelemetry.classify(e)
            OperationTelemetry.record(
                event = OperationTelemetry.Event.AI_REQUEST_RESULT,
                operation = OperationTelemetry.Operation.AI_COMPLETION,
                operationId = operationId,
                outcome = if (e is kotlinx.coroutines.CancellationException) OperationTelemetry.Outcome.CANCELLED else if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                errorClass = errorClass,
                transport = transport,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            throw e
        }
    }

    /** If a model is configured, exercise the API with a tiny completion. Otherwise
     *  issue GET only as a reachability/auth probe and state its limited meaning. */
    suspend fun testApi(
        endpointUrl: String,
        apiKey: String,
        modelName: String,
        additionalHeaders: String
    ): String {
        if (modelName.isNotBlank()) {
            complete(
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                modelName = modelName,
                additionalHeaders = additionalHeaders,
                systemPrompt = "Reply with the single word OK.",
                userPrompt = "Connection test. Reply with OK."
            )
            return "نجح اختبار API بإكمال طلب للنموذج المحدد."
        }
        return withContext(Dispatchers.IO) {
            val operationId = OperationTelemetry.newOperationId()
            val startedAt = OperationTelemetry.elapsedRealtime()
            var transport = OperationTelemetry.Transport.HTTPS
            try {
            val endpoint = validateApiEndpoint(endpointUrl, apiKey, additionalHeaders)
            if (URI(endpoint).scheme == "http") transport = OperationTelemetry.Transport.LOCAL
            val requestBuilder = Request.Builder().url(endpoint).get()
            addConfiguredHeaders(requestBuilder, additionalHeaders, apiKey)
            val result = http.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code in 200..299 -> "الخادم استجاب HTTP ${response.code}. هذا يثبت الوصول فقط؛ لم يُختبر نموذج أو إكمال طلب لأن Model Name فارغ."
                    response.code == 405 -> "الخادم استجاب HTTP 405. يبدو أن العنوان قابل للوصول لكنه يتطلب POST؛ أدخل اسم النموذج لاختبار إكمال API فعليًا."
                    else -> throw IllegalStateException("فشل اختبار الوصول إلى API: HTTP ${response.code}. تحقق من الرابط والترويسات ومفتاح الخدمة.")
                }
            }
            OperationTelemetry.record(
                event = OperationTelemetry.Event.AI_REQUEST_RESULT,
                operation = OperationTelemetry.Operation.AI_API_PROBE,
                operationId = operationId,
                outcome = OperationTelemetry.Outcome.SUCCESS,
                transport = transport,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            result
            } catch (e: Exception) {
                val errorClass = OperationTelemetry.classify(e)
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.AI_REQUEST_RESULT,
                    operation = OperationTelemetry.Operation.AI_API_PROBE,
                    operationId = operationId,
                    outcome = if (e is kotlinx.coroutines.CancellationException) OperationTelemetry.Outcome.CANCELLED else if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                    errorClass = errorClass,
                    transport = transport,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                throw e
            }
        }
    }

    /** Reachability test only: no universal connector protocol is assumed. */
    suspend fun testConnector(connectorUrl: String): String = withContext(Dispatchers.IO) {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        try {
        val endpoint = validateUrl(connectorUrl)
        // Do not send the AI provider key or custom headers to an unrelated
        // connector URL. A generic connector has no universal auth contract.
        val requestBuilder = Request.Builder().url(endpoint).get()
        val result = http.newCall(requestBuilder.build()).execute().use { response ->
            when {
                response.code in 200..299 -> "استجاب رابط الموصل HTTP ${response.code}. هذا اختبار وصول فقط ولا يثبت دعم بروتوكول MikroTik أو أوامره؛ يلزم توثيق خدمة الموصل."
                response.code in 300..399 -> "رابط الموصل قابل للوصول وأعاد HTTP ${response.code} بعد التحويل، لكنه لم يُتحقق من بروتوكوله."
                else -> throw IllegalStateException("استجاب رابط الموصل HTTP ${response.code}. تحقق من الرابط والترويسات؛ لم يُرسل أي أمر إلى الراوتر.")
            }
        }
        OperationTelemetry.record(
            event = OperationTelemetry.Event.AI_REQUEST_RESULT,
            operation = OperationTelemetry.Operation.AI_API_PROBE,
            operationId = operationId,
            outcome = OperationTelemetry.Outcome.SUCCESS,
            transport = if (URI(endpoint).scheme == "https") OperationTelemetry.Transport.HTTPS else OperationTelemetry.Transport.LOCAL,
            durationMs = OperationTelemetry.elapsedRealtime() - startedAt
        )
        result
        } catch (e: Exception) {
            val errorClass = OperationTelemetry.classify(e)
            OperationTelemetry.record(
                event = OperationTelemetry.Event.AI_REQUEST_RESULT,
                operation = OperationTelemetry.Operation.AI_API_PROBE,
                operationId = operationId,
                outcome = if (e is kotlinx.coroutines.CancellationException) OperationTelemetry.Outcome.CANCELLED else if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                errorClass = errorClass,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            throw e
        }
    }

    internal fun validateUrl(value: String): String {
        val text = value.trim()
        require(text.isNotEmpty()) { "أدخل رابط API أو الموصل أولاً." }
        val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException("الرابط غير صالح.") }
        require((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "استخدم رابط HTTP أو HTTPS صالحًا من دون بيانات دخول مضمّنة في الرابط."
        }
        require(!Regex("(?i)(^|&)(api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|authorization|password|passwd|key|signature)=").containsMatchIn(uri.rawQuery.orEmpty())) {
            "لا تضع API Key أو token أو كلمة مرور في query URL؛ خزّن الاعتماد في الحقل المخصص أو Additional Headers."
        }
        return text
    }

    internal fun validateApiEndpoint(endpointUrl: String, apiKey: String, additionalHeaders: String): String {
        val endpoint = validateUrl(endpointUrl)
        val uri = URI(endpoint)
        if (uri.scheme == "http") {
            val host = uri.host.orEmpty().lowercase().removePrefix("[").removeSuffix("]")
            require(isLoopbackOrPrivateHost(host)) {
                "HTTP مسموح فقط لخدمة محلية/خاصة؛ استخدم HTTPS لواجهة عامة."
            }
            require(apiKey.isBlank() && additionalHeaders.isBlank()) {
                "لا يمكن إرسال API Key أو Additional Headers عبر HTTP غير مشفّر؛ استخدم HTTPS."
            }
        }
        return endpoint
    }

    private fun isLoopbackOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254) ||
            octets.all { it == 0 }
    }

    private fun addConfiguredHeaders(builder: Request.Builder, headerText: String, apiKey: String) {
        val headers = parseHeaders(headerText)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (apiKey.isNotBlank() && headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            builder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
    }

    internal fun parseHeaders(headerText: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (line in headerText.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
            val separator = line.indexOf(':')
            require(separator > 0) { "صيغة Additional Headers يجب أن تكون سطرًا لكل ترويسة: Name: Value" }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' }) {
                "اسم أو قيمة إحدى الترويسات غير صالحة."
            }
            require(name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")) {
                "لا يمكن تجاوز ترويسة النقل المحمية: $name"
            }
            result[name] = value
        }
        return result
    }

    private fun contentAsText(content: Any): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) appendLine(text)
            }
        }
        else -> content.toString()
    }
}
