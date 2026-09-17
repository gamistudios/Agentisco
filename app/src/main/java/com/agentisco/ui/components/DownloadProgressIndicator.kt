package com.agentisco.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.agentisco.ui.theme.DarkSurfaceHighlight
import com.agentisco.ui.theme.ElectricBlue

/**
 * Slim 2dp download progress bar pinned to the very top of the header.
 * It is intentionally minimal so long-running APK downloads don't distract
 * from whatever the user is doing.
 */
@Composable
fun DownloadProgressIndicator(
    progress: Float,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "updateDownloadProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(DarkSurfaceHighlight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(2.dp)
                .background(ElectricBlue)
        )
    }
}
