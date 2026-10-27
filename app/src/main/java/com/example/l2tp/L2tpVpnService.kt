package com.example.l2tp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.L2tpIpsecTunnel
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelErrorKind
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelInfo
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelListener
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelState
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelStats
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.VpnConfig
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground [VpnService] that owns one in-app L2TP/IPsec tunnel.
 *
 * It is deliberately small: the protocol stack lives in :l2tp-core, this class only supplies the
 * Android UDP socket factory, the TUN interface, the notification, and lifecycle. Once the tunnel
 * is up, the rest of the app can reach the router through it; only the stack's own UDP sockets are
 * protected from the VPN.
 */
class L2tpVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>(null)
    private val tunnel = AtomicReference<L2tpIpsecTunnel?>(null)
    private val socketFactory = AtomicReference<AndroidUdpSocketFactory?>(null)
    private val tunDescriptor = AtomicReference<ParcelFileDescriptor?>(null)

    @Volatile
    private var serverHost: String = ""

    @Volatile
    private var lastStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> startTunnel(intent)
            ACTION_DISCONNECT -> {
                if (running.get()) stopTunnel() else {
                    L2tpStatusRepository.onDisconnected()
                    stopSelf()
                }
            }
            else -> if (!running.get()) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startTunnel(intent: Intent) {
        if (!running.compareAndSet(false, true)) return
        stopRequested.set(false)
        // start() uses startForegroundService(), so Android expects a foreground notification
        // within a few seconds even when the intent turns out to be invalid.
        startForegroundCompat("جارٍ تجهيز نفق L2TP...")

        val config = L2tpClientConfig.fromIntent(intent)
        if (config == null || config.host.isBlank()) {
            abortStartup("بيانات خادم L2TP غير مكتملة.")
            return
        }
        if (config.presharedKey.isBlank()) {
            abortStartup("مفتاح IPsec PSK مطلوب لاتصال L2TP/IPsec.")
            return
        }
        if (config.username.isBlank()) {
            abortStartup("اسم مستخدم VPN مطلوب.")
            return
        }

        serverHost = config.host
        L2tpStatusRepository.onConnecting(config.serverHost)
        updateNotification("جارٍ الاتصال بـ ${config.host}...")

        worker.set(
            Thread({ runTunnel(config) }, "l2tp-vpn-worker").apply {
                isDaemon = true
                start()
            },
        )
    }

    private fun runTunnel(config: L2tpClientConfig) {
        var failure: String? = null
        try {
            // Prefer IPv4: the stack and most RouterOS L2TP servers are IPv4-only.
            val server = InetAddress.getAllByName(config.host)
                .firstOrNull { it is java.net.Inet4Address }
                ?: InetAddress.getByName(config.host)
            if (stopRequested.get()) return
            val factory = AndroidUdpSocketFactory(this, server, L2tpLogger)
            socketFactory.set(factory)
            val tunProvider = AndroidTunProvider(
                service = this,
                onEstablished = { descriptor -> tunDescriptor.set(descriptor) },
                sessionName = "ABOTALAL L2TP",
                configureIntent = null,
                logger = L2tpLogger,
            )
            val vpnConfig = VpnConfig(
                serverHost = config.host,
                presharedKey = config.presharedKey,
                username = config.username,
                password = config.password,
                l2tpPort = config.port,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                debugLogging = BuildConfig.DEBUG,
            )
            val activeTunnel = L2tpIpsecTunnel(
                config = vpnConfig,
                socketFactory = factory,
                tunProvider = tunProvider,
                listener = tunnelListener,
                logger = L2tpLogger,
            )
            tunnel.set(activeTunnel)
            if (stopRequested.get()) {
                activeTunnel.stop()
            }
            activeTunnel.run()
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
            L2tpStatusRepository.onFailed(failure)
            updateNotification(failure)
        } finally {
            tunnel.set(null)
            runCatching { socketFactory.getAndSet(null)?.closeAll() }
            runCatching { tunDescriptor.getAndSet(null)?.close() }
            running.set(false)
            if (failure == null && L2tpStatusRepository.state.value.phase != L2tpPhase.FAILED) {
                L2tpStatusRepository.onDisconnected()
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    private fun abortStartup(message: String) {
        L2tpStatusRepository.onFailed(message)
        running.set(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun stopTunnel() {
        if (!running.get() && tunnel.get() == null) {
            // onDestroy() calls this after a failed attempt. Do not let teardown erase the
            // failure the user still needs to read.
            if (L2tpStatusRepository.state.value.phase != L2tpPhase.FAILED) {
                L2tpStatusRepository.onDisconnected()
            }
            return
        }
        stopRequested.set(true)
        L2tpStatusRepository.onDisconnecting()
        updateNotification("جارٍ قطع الاتصال...")
        runCatching { tunnel.get()?.stop() }
        // Closing the TUN is what unblocks the uplink read. The protocol stack keeps its UDP
        // socket alive briefly so PPP/L2TP/IPsec teardown messages can still leave the device.
        runCatching { tunDescriptor.getAndSet(null)?.close() }
        worker.get()?.interrupt()
    }

    // ------------------------------------------------------------------ listener

    private val tunnelListener = object : TunnelListener {
        override fun onStateChanged(state: TunnelState, detail: String?) {
            val text = detail ?: stateLabel(state)
            if (state != TunnelState.CONNECTED) {
                L2tpStatusRepository.onProgress(text)
                updateNotification(text)
            }
        }

        override fun onConnected(info: TunnelInfo) {
            L2tpStatusRepository.onConnected(
                server = info.serverAddress,
                assigned = info.assignedAddress,
                peer = info.peerAddress,
            )
            updateNotification(
                "متصل — ${info.assignedAddress} عبر ${info.serverAddress}",
            )
        }

        override fun onFailed(kind: TunnelErrorKind, message: String, cause: Throwable?) {
            L2tpStatusRepository.onFailed(userMessage(kind, message))
            updateNotification(userMessage(kind, message))
        }

        override fun onDisconnected() {
            if (L2tpStatusRepository.state.value.phase != L2tpPhase.FAILED) {
                L2tpStatusRepository.onDisconnected()
            }
        }

        override fun onStats(stats: TunnelStats) = Unit
    }

    // ------------------------------------------------------------------ notifications

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "نفق L2TP",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "حالة اتصال L2TP داخل التطبيق"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(text ?: "نفق L2TP"))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            REQUEST_DISCONNECT,
            Intent(this, L2tpVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_l2tp_vpn)
            .setContentTitle("ABOTALAL — L2TP")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_l2tp_vpn, "قطع الاتصال", disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setLocalOnly(true)
            .build()
    }

    private fun stateLabel(state: TunnelState): String = when (state) {
        TunnelState.RESOLVING -> "جارٍ تحليل عنوان الخادم..."
        TunnelState.IKE_PHASE1 -> "مصادقة IPsec (المرحلة 1)..."
        TunnelState.IKE_PHASE2 -> "تكوين نفق IPsec (المرحلة 2)..."
        TunnelState.L2TP_TUNNEL -> "إنشاء نفق L2TP..."
        TunnelState.L2TP_SESSION -> "فتح جلسة L2TP..."
        TunnelState.PPP_NEGOTIATION -> "مصادقة PPP..."
        TunnelState.CONNECTED -> "متصل"
        TunnelState.DISCONNECTING -> "جارٍ قطع الاتصال..."
        TunnelState.RECONNECTING -> "إعادة الاتصال..."
        TunnelState.FAILED -> "فشل الاتصال"
        TunnelState.IDLE -> "غير متصل"
    }

    private fun userMessage(kind: TunnelErrorKind, message: String): String {
        val hint = when (kind) {
            TunnelErrorKind.DNS_FAILURE ->
                "تعذر تحليل اسم خادم L2TP. تحقق من الاسم أو استخدم عنوان IP."
            TunnelErrorKind.IKE_NO_RESPONSE ->
                "الخادم لم يرد على IPsec. تحقق من أن الخادم يستخدم L2TP/IPsec PSK وأن UDP 500/4500 غير محجوب."
            TunnelErrorKind.IKE_AUTH_FAILED ->
                "فشل التحقق من مفتاح IPsec PSK. تحقق من المفتاح المشترك."
            TunnelErrorKind.PPP_AUTH_FAILED ->
                "رفض الخادم اسم مستخدم أو كلمة مرور VPN."
            TunnelErrorKind.TUN_UNAVAILABLE ->
                "لم يمنح أندرويد إذن إنشاء نفق VPN. أعد المحاولة ووافق على طلب VPN."
            TunnelErrorKind.NETWORK_UNREACHABLE ->
                "تعذر الوصول إلى خادم L2TP. تحقق من الشبكة والمنفذ."
            else -> null
        }
        val detail = message.trim()
        return buildString {
            append(detail.ifBlank { "تعذر إنشاء نفق L2TP." })
            if (hint != null && !detail.contains(hint)) {
                append("\n\n")
                append(hint)
            }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.example.l2tp.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.l2tp.action.DISCONNECT"

        private const val CHANNEL_ID = "l2tp_vpn"
        private const val NOTIFICATION_ID = 0x4C32
        private const val REQUEST_OPEN = 11
        private const val REQUEST_DISCONNECT = 12
        private const val CONNECT_TIMEOUT_MS = 60_000

        fun start(context: Context, config: L2tpClientConfig) {
            val intent = config.writeTo(
                Intent(context, L2tpVpnService::class.java).setAction(ACTION_CONNECT),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, L2tpVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
        }
    }
}
