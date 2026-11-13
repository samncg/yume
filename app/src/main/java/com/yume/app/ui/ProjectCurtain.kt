package com.yume.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yume.app.data.ProjectMeta
import com.yume.app.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The screen between the gallery and a canvas: the project's own thumbnail and
 * name over the app background, with a thin moving progress line. It covers
 * the editor while the document loads and while it saves on the way out (the
 * canvas surface cannot fade, so this curtain fades instead).
 */
/** What the curtain shows for a project, loaded once when it opens. */
class CurtainContent {
    var meta by mutableStateOf<ProjectMeta?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun rememberCurtainContent(store: ProjectStore, projectId: String): CurtainContent {
    val content = remember(projectId) { CurtainContent() }
    LaunchedEffect(projectId) {
        withContext(Dispatchers.IO) {
            val m = store.listProjects().firstOrNull { it.id == projectId }
            content.meta = m
            content.thumb = m?.let { store.loadThumb(it, 512)?.asImageBitmap() }
        }
    }
    return content
}

@Composable
fun ProjectCurtain(
    content: CurtainContent,
    label: String,
    alpha: Float
) {
    val meta = content.meta
    val thumb = content.thumb
    // The card settles in as the curtain appears.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, AppleSpring.sheet()) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(YumeColors.bg)
            .claimCanvasTouches(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                val r = rise.value
                this.alpha = r.coerceIn(0f, 1f)
                scaleX = 0.94f + 0.06f * r
                scaleY = 0.94f + 0.06f * r
                translationY = (1f - r) * 24.dp.toPx()
            }
        ) {
            val m = meta
            val ratio = if (m != null) (m.width.toFloat() / m.height.coerceAtLeast(1)).coerceIn(0.45f, 2.4f) else 1f
            Box(
                Modifier
                    .widthIn(max = 360.dp)
                    .heightIn(max = 360.dp)
                    .aspectRatio(ratio)
                    .graphicsLayer {
                        shadowElevation = 24.dp.toPx()
                        shape = RoundedCornerShape(14.dp)
                        clip = true
                    }
                    .background(Color(0xFF1C1C22))
            ) {
                thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = m?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = YumeColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
            Spacer(Modifier.height(16.dp))
            ProgressLine()
        }
    }
}

/** A short highlight sliding along a hairline: busy, without a spinner. */
@Composable
private fun ProgressLine() {
    val t by rememberInfiniteTransition(label = "progress").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "progressT"
    )
    Box(
        Modifier
            .width(120.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x1FFFFFFF))
            .drawWithContent {
                val seg = size.width * 0.35f
                val x = -seg + (size.width + seg) * t
                drawRoundRect(
                    color = YumeColors.accent,
                    topLeft = Offset(x, 0f),
                    size = Size(seg, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                )
            }
    )
}
