package com.example.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.netCraftColors
import kotlinx.coroutines.channels.Channel

/**
 * Unified in-app feedback API.
 *
 * Replaces the historical mix of `Toast.makeText` (112 call sites) and ad-hoc
 * per-screen snackbars with ONE entry point that works from anywhere —
 * composables, ViewModels, coroutines and plain helper classes:
 *
 * ```
 * AppFeedback.success("تم الحفظ")
 * AppFeedback.error("تعذر الاتصال")
 * ```
 *
 * Messages are always themed snackbars rendered by [AppFeedbackHost] (mounted
 * once at the app root in MainActivity): they survive configuration changes,
 * are accessible, respect the app theme, and never leak system-wide toasts.
 */
enum class FeedbackType { SUCCESS, ERROR, WARNING, INFO }

internal class FeedbackEvent(
    val message: String,
    val type: FeedbackType,
    val duration: SnackbarDuration,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

/** App-singleton feedback bus. Safe to call from any thread. */
object AppFeedback {
    internal val events = Channel<FeedbackEvent>(Channel.UNLIMITED)

    fun show(
        message: String,
        type: FeedbackType = FeedbackType.INFO,
        long: Boolean = false,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        if (message.isBlank()) return
        events.trySend(
            FeedbackEvent(
                message = message,
                type = type,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
                actionLabel = actionLabel,
                onAction = onAction,
            )
        )
    }

    fun info(message: String, long: Boolean = false) = show(message, FeedbackType.INFO, long)

    fun success(message: String, long: Boolean = false) = show(message, FeedbackType.SUCCESS, long)

    /** Errors stay on screen longer so the user can actually read them. */
    fun error(message: String) = show(message, FeedbackType.ERROR, long = true)

    fun warning(message: String, long: Boolean = false) = show(message, FeedbackType.WARNING, long)
}

/**
 * Root-level host that renders every [AppFeedback] message as a themed
 * snackbar. Mounted once in MainActivity on top of all screens; no screen
 * needs its own SnackbarHost anymore.
 */
@Composable
fun AppFeedbackHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        for (event in AppFeedback.events) {
            // Encode the type as a prefix tag so the renderer can pick the
            // right icon without changing the SnackbarHostState API.
            val tagged = when (event.type) {
                FeedbackType.SUCCESS -> "✓ ${event.message}"
                FeedbackType.ERROR -> "✕ ${event.message}"
                FeedbackType.WARNING -> "! ${event.message}"
                FeedbackType.INFO -> event.message
            }
            val result = hostState.showSnackbar(
                message = tagged,
                actionLabel = event.actionLabel,
                duration = event.duration,
            )
            if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
        }
    }

    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        FeedbackSnackbar(data)
    }
}

private fun FeedbackType.icon(): ImageVector = when (this) {
    FeedbackType.SUCCESS -> Icons.Filled.CheckCircle
    FeedbackType.ERROR -> Icons.Filled.ErrorOutline
    FeedbackType.WARNING -> Icons.Filled.WarningAmber
    FeedbackType.INFO -> Icons.Filled.Info
}

private fun FeedbackType.color(): Color = when (this) {
    FeedbackType.SUCCESS -> Color(0xFF10B981)
    FeedbackType.ERROR -> Color(0xFFEF4444)
    FeedbackType.WARNING -> Color(0xFFF59E0B)
    FeedbackType.INFO -> Color(0xFF0284C7)
}

@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val colors = netCraftColors()
    // Convention: messages carry their type as a prefix tag so the host can
    // render an icon without changing every call site at once.
    val (type, text) = parseType(data.visuals.message)
    Snackbar(
        containerColor = colors.cardBg,
        contentColor = colors.textPrimary,
        actionContentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(12.dp),
    ) {
        Icon(
            imageVector = type.icon(),
            contentDescription = null,
            tint = type.color(),
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(text = text, color = colors.textPrimary)
    }
}

private fun parseType(message: String): Pair<FeedbackType, String> = when {
    message.startsWith("✓ ") -> FeedbackType.SUCCESS to message.removePrefix("✓ ")
    message.startsWith("✕ ") -> FeedbackType.ERROR to message.removePrefix("✕ ")
    message.startsWith("! ") -> FeedbackType.WARNING to message.removePrefix("! ")
    else -> FeedbackType.INFO to message
}
