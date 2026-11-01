package io.legado.app.ui.widget.components.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 播放器通用的进度条：拖动时放大轨道、松手提交进度。
 */
@Composable
fun PlayerProgressSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    /** 压在深色画面（视频播放器浮层）上时传浅色，默认跟随主题。 */
    activeColor: Color = LegadoTheme.colorScheme.onSurface,
    inactiveColor: Color = LegadoTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    /** 手势锁定时只读：仍显示进度，但不响应点击/拖动。 */
    enabled: Boolean = true,
) {
    val rangeLength = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
    var isDragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var previewFraction by remember(valueRange) {
        mutableFloatStateOf(((value - valueRange.start) / rangeLength).coerceIn(0f, 1f))
    }
    /**
     * 松手/点击后提交的进度。实际播放位置要等 seek 生效才会追上，
     * 这段时间进度条保持提交位置，否则会先「跳回原处」再跳过去，看起来像没滑动成功。
     */
    var pendingFraction by remember { mutableStateOf<Float?>(null) }
    val latestValue by rememberUpdatedState(value)
    LaunchedEffect(value, valueRange) {
        if (isDragging) return@LaunchedEffect
        val target = ((value - valueRange.start) / rangeLength).coerceIn(0f, 1f)
        val pending = pendingFraction
        if (pending != null && abs(target - pending) > 0.01f) {
            previewFraction = pending
        } else {
            pendingFraction = null
            previewFraction = target
        }
    }
    // 只有兜底时长到了才放弃保持：seek 生效慢（长缓冲）时不至于半路跳回旧位置
    LaunchedEffect(pendingFraction) {
        if (pendingFraction != null) {
            delay(10_000)
            pendingFraction = null
            previewFraction = ((latestValue - valueRange.start) / rangeLength).coerceIn(0f, 1f)
        }
    }
    val trackScale by animateFloatAsState(
        targetValue = if (isDragging) 1.35f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "player_progress_track_scale",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .graphicsLayer { scaleY = trackScale }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(valueRange, widthPx, enabled) {
                if (!enabled) return@pointerInput
                fun fractionAt(x: Float): Float = (x / widthPx).coerceIn(0f, 1f)
                fun commit(fraction: Float) {
                    previewFraction = fraction
                    pendingFraction = fraction
                    onValueChange(valueRange.start + fraction * rangeLength)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startX = down.position.x
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent()
                            .changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved && change.pressed &&
                            abs(change.position.x - startX) > viewConfiguration.touchSlop
                        ) {
                            moved = true
                            isDragging = true
                        }
                        if (moved) {
                            previewFraction = fractionAt(change.position.x)
                            change.consume()
                        }
                        if (!change.pressed) {
                            // 拖动松手或原地点击都提交到手指位置
                            commit(fractionAt(change.position.x))
                            break
                        }
                    }
                    isDragging = false
                }
            },
    ) {
        val centerY = size.height / 2f
        val progressX = size.width * previewFraction
        val trackHeight = 2.dp.toPx()
        val trackRadius = trackHeight / 2f
        val trackClip = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = centerY - trackRadius,
                    right = size.width,
                    bottom = centerY + trackRadius,
                    cornerRadius = CornerRadius(trackRadius),
                )
            )
        }
        val tickSpacing = 4.dp.toPx()
        val tickHeight = 2.dp.toPx()
        clipPath(trackClip) {
            var tickX = 0f
            while (tickX <= size.width) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(tickX, centerY - tickHeight / 2f),
                    end = Offset(tickX, centerY + tickHeight / 2f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                tickX += tickSpacing
            }
            if (progressX > 0f) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(progressX, centerY),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Butt,
                )
            }
        }
        // 拖动/提交后的滑块：明确标出当前选中的位置
        val highlighted = isDragging || pendingFraction != null
        if (highlighted) {
            // 外圈光晕，让「已选中」更明显
            drawCircle(
                color = activeColor.copy(alpha = 0.28f),
                radius = 10.dp.toPx(),
                center = Offset(progressX, centerY),
            )
        }
        drawCircle(
            color = activeColor,
            radius = if (highlighted) 6.dp.toPx() else 3.dp.toPx(),
            center = Offset(progressX, centerY),
        )
    }
}
