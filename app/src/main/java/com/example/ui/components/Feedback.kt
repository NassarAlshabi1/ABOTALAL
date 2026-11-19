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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.netCraftColors
import kotlinx.coroutines.launch

/**
 * Unified in-app feedback API.
 *
 * Replaces the historical mix of Toast and ad-hoc snackbars (128 call sites)
 * with one entry point:
 *
 * ```
 * val feedback = rememberFeedbackState()
 * Scaffold(snackbarHost = { FeedbackHost(feedback) }) { ... }
 * feedback.success("تم الحفظ")
 * feedback.error("تعذر الاتصال")
 * ```
 *
 * Messages are always snackbars (survive configuration changes, are
 * accessible, and never leak system-wide toasts).
 */
enum class FeedbackType { SUCCESS, ERROR, WARNING, INFO }

class FeedbackState internal constructor(
    internal val hostState: SnackbarHostState,
    internal val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Shows a themed snackbar. Safe to call from any thread. */
    fun show(
        message: String,
        type: FeedbackType = FeedbackType.INFO,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) {
        if (message.isBlank()) return
        // Encode the type as a prefix tag so FeedbackHost can pick the right
        // icon without changing the SnackbarHostState API.
        val tagged = when (type) {
            FeedbackType.SUCCESS -> "✓ $message"
            FeedbackType.ERROR -> "✕ $message"
            FeedbackType.WARNING -> "! $message"
            FeedbackType.INFO -> message
        }
        scope.launch {
            val result = hostState.showSnackbar(
                message = tagged,
                actionLabel = actionLabel,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    fun success(message: String) = show(message, FeedbackType.SUCCESS)
    fun error(message: String) = show(message, FeedbackType.ERROR, duration = SnackbarDuration.Long)
    fun warning(message: String) = show(message, FeedbackType.WARNING)
    fun info(message: String) = show(message, FeedbackType.INFO)
}

/** Creates a [FeedbackState] bound to the current composition scope. */
@Composable
fun rememberFeedbackState(): FeedbackState {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember { FeedbackState(hostState, scope) }
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

/** Renders the snackbars produced by [FeedbackState]. Put it in Scaffold(snackbarHost = ...). */
@Composable
fun FeedbackHost(feedback: FeedbackState) {
    SnackbarHost(hostState = feedback.hostState) { data ->
        FeedbackSnackbar(data)
    }
}

@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val colors = netCraftColors()
    // Convention: messages carry their type as a prefix tag "[نوع]" so the
    // host can render an icon without changing every call site at once.
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
