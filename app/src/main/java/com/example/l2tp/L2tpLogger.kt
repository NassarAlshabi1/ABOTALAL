package com.example.l2tp

import android.util.Log
import com.arcansecurity.vpn.l2tpipsec.core.util.LogLevel
import com.arcansecurity.vpn.l2tpipsec.core.util.VpnLogger
import com.example.BuildConfig

/**
 * Forwards the protocol stack's diagnostics to logcat. Debug traces are only compiled into debug
 * builds, so a release APK does not log packet-level detail from a live VPN.
 */
object L2tpLogger : VpnLogger {

    private const val TAG_PREFIX = "L2TP."
    private const val MAX_TAG = 23

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val logTag = (TAG_PREFIX + tag).take(MAX_TAG)
        when (level) {
            LogLevel.DEBUG -> Log.d(logTag, message, error)
            LogLevel.INFO -> Log.i(logTag, message, error)
            LogLevel.WARN -> Log.w(logTag, message, error)
            LogLevel.ERROR -> Log.e(logTag, message, error)
        }
    }

    override fun isEnabled(level: LogLevel): Boolean = BuildConfig.DEBUG || level >= LogLevel.INFO
}
