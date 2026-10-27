package com.example.l2tp

import android.content.Intent

/**
 * Everything the in-app L2TP/IPsec client needs for one connection.
 *
 * The RouterOS API credentials are deliberately not part of this object: the tunnel is
 * established with the VPN account, then the existing login screen authenticates against
 * RouterOS separately.
 */
data class L2tpClientConfig(
    val host: String,
    val port: Int = DEFAULT_L2TP_PORT,
    val presharedKey: String,
    val username: String,
    val password: String,
    val displayName: String = "",
) {
    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_HOST, host)
        putExtra(EXTRA_PORT, port)
        putExtra(EXTRA_PSK, presharedKey)
        putExtra(EXTRA_USERNAME, username)
        putExtra(EXTRA_PASSWORD, password)
        putExtra(EXTRA_DISPLAY_NAME, displayName)
    }

    companion object {
        const val DEFAULT_L2TP_PORT = 1701

        private const val EXTRA_HOST = "com.example.l2tp.extra.HOST"
        private const val EXTRA_PORT = "com.example.l2tp.extra.PORT"
        private const val EXTRA_PSK = "com.example.l2tp.extra.PSK"
        private const val EXTRA_USERNAME = "com.example.l2tp.extra.USERNAME"
        private const val EXTRA_PASSWORD = "com.example.l2tp.extra.PASSWORD"
        private const val EXTRA_DISPLAY_NAME = "com.example.l2tp.extra.DISPLAY_NAME"

        fun fromIntent(intent: Intent): L2tpClientConfig? {
            val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
            if (host.isBlank()) return null
            return L2tpClientConfig(
                host = host,
                port = intent.getIntExtra(EXTRA_PORT, DEFAULT_L2TP_PORT),
                presharedKey = intent.getStringExtra(EXTRA_PSK).orEmpty(),
                username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty(),
            )
        }
    }
}

data class L2tpEndpoint(val host: String, val port: Int)

/**
 * Accepts the shapes users paste from a VPN provider: `host`, `host:1701`,
 * `l2tp://host:1701`, `[2001:db8::1]:1701`, or a URL with a trailing path.
 */
fun parseL2tpEndpoint(raw: String, fallbackPort: Int = L2tpClientConfig.DEFAULT_L2TP_PORT): L2tpEndpoint? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    value = value.substringAfter("://", value).trim()
    value = value.substringBefore('/').substringBefore('?').substringBefore('#').trim()
    if (value.isEmpty()) return null

    var host: String
    var port = fallbackPort
    if (value.startsWith("[")) {
        val close = value.indexOf(']')
        if (close <= 1) return null
        host = value.substring(1, close)
        val rest = value.substring(close + 1)
        if (rest.startsWith(":")) {
            port = rest.substring(1).toIntOrNull() ?: return null
        } else if (rest.isNotEmpty()) {
            return null
        }
    } else {
        val firstColon = value.indexOf(':')
        val lastColon = value.lastIndexOf(':')
        if (firstColon > 0 && firstColon == lastColon) {
            host = value.substring(0, firstColon)
            port = value.substring(firstColon + 1).toIntOrNull() ?: return null
        } else {
            // Bare IPv6 address without a port.
            host = value
        }
    }

    host = host.trim()
    if (host.isBlank() || port !in 1..65535) return null
    return L2tpEndpoint(host, port)
}
