package com.example.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Unified pull-to-refresh wrapper for every list screen.
 *
 * Wraps the Material 3 [PullToRefreshBox] with the app defaults so adopting
 * swipe-to-refresh on a screen is a three-line change:
 *
 * ```
 * RefreshableListContainer(
 *     isRefreshing = isRefreshing,
 *     onRefresh = { doRefresh() },
 * ) {
 *     LazyColumn { ... }
 * }
 * ```
 *
 * The indicator spins while [isRefreshing] is true; the call site owns the
 * boolean and must flip it back to false when its refresh completes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableListContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { if (enabled) onRefresh() },
        state = state,
        contentAlignment = Alignment.TopStart,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
        modifier = modifier,
    )
}
