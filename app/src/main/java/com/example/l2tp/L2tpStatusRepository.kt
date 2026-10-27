package com.example.l2tp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class L2tpPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED,
}

data class L2tpStatus(
    val phase: L2tpPhase = L2tpPhase.IDLE,
    val detail: String? = null,
    val serverAddress: String? = null,
    val assignedAddress: String? = null,
    val peerAddress: String? = null,
) {
    val isConnected: Boolean get() = phase == L2tpPhase.CONNECTED
    val isBusy: Boolean get() = phase == L2tpPhase.CONNECTING || phase == L2tpPhase.DISCONNECTING
}

/**
 * Process-wide mirror of the tunnel state. The service writes, the login screen reads; there is
 * no service binding, so rotating or recreating the screen never disturbs the tunnel.
 */
object L2tpStatusRepository {

    private val _state = MutableStateFlow(L2tpStatus())
    val state: StateFlow<L2tpStatus> = _state.asStateFlow()

    fun onConnecting(server: String?) {
        _state.value = L2tpStatus(phase = L2tpPhase.CONNECTING, serverAddress = server)
    }

    fun onProgress(detail: String?) {
        val current = _state.value
        if (current.phase == L2tpPhase.CONNECTING) {
            _state.value = current.copy(detail = detail)
        }
    }

    fun onConnected(
        server: String?,
        assigned: String?,
        peer: String?,
    ) {
        _state.value = L2tpStatus(
            phase = L2tpPhase.CONNECTED,
            serverAddress = server,
            assignedAddress = assigned,
            peerAddress = peer,
        )
    }

    fun onDisconnecting() {
        _state.value = _state.value.copy(phase = L2tpPhase.DISCONNECTING, detail = null)
    }

    fun onDisconnected() {
        _state.value = L2tpStatus()
    }

    fun onFailed(message: String?) {
        _state.value = L2tpStatus(
            phase = L2tpPhase.FAILED,
            detail = message?.takeIf { it.isNotBlank() },
            serverAddress = _state.value.serverAddress,
        )
    }
}
