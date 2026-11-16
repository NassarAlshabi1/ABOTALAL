package com.example.util

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for RouterOS API-SSL (port 8729).
 *
 * RouterOS generates a self-signed certificate for `api-ssl`, so the platform trust store rejects
 * it. The app must not ship a trust-all manager, so this uses trust-on-first-use (TOFU) pinning:
 *
 *  * certificates that chain to a system CA are accepted normally;
 *  * a self-signed certificate is accepted once, its SHA-256 fingerprint is stored per
 *    `host:port`, and every later handshake must present the same certificate.
 *
 * If the router certificate is regenerated, the user must clear the pin from the advanced
 * options before reconnecting.
 */
object RouterOsTls {

    private const val PREFS = "router_tls_pins"

    fun socketFactory(context: Context, host: String, port: Int): SSLSocketFactory {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = PinningTrustManager(pinKey(host, port), prefs)
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), null)
        }.socketFactory
    }

    fun storedPin(context: Context, host: String, port: Int): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(pinKey(host, port), null)

    fun clearPin(context: Context, host: String, port: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(pinKey(host, port))
            .apply()
    }

    private fun pinKey(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    private fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private class PinningTrustManager(
        private val key: String,
        private val prefs: android.content.SharedPreferences,
    ) : X509TrustManager {

        private val systemTrust: X509TrustManager? = runCatching {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificates = chain ?: throw CertificateException("الخادم لم يرسل شهادة SSL.")
            if (certificates.isEmpty()) throw CertificateException("الخادم لم يرسل شهادة SSL.")
            val system = systemTrust
            if (system != null && runCatching { system.checkServerTrusted(certificates, authType) }.isSuccess) {
                return
            }

            val actual = fingerprint(certificates[0])
            val pinned = prefs.getString(key, null)
            if (pinned == null) {
                // First contact with this router: remember the certificate and require it later.
                prefs.edit().putString(key, actual).apply()
                return
            }
            if (!pinned.equals(actual, ignoreCase = true)) {
                throw CertificateException(
                    "شهادة SSL الخاصة بالراوتر لا تطابق الشهادة المحفوظة. " +
                        "إن كنت أنت من أعاد ضبط الراوتر أو جدّد شهادته، فامسح الشهادة المحفوظة " +
                        "من خيارات SSL المتقدمة ثم أعد المحاولة."
                )
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("لا يستخدم هذا التطبيق شهادات عميل.")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
