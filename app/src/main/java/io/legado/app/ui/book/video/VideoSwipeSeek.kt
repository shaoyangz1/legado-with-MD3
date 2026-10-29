package io.legado.app.ui.book.video

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.runtime.Stable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import kotlin.math.abs
import kotlin.math.roundToLong

@Stable
internal data class VideoSeekPreview(val positionMs: Long, val durationMs: Long)

/** 长按后横向拖动，一屏最多移动两分钟；以按下时的位置计算，避免播放时钟改变基准。 */
internal class VideoSwipeSeekGesture(
    private val touchSlop: Float,
    private val longPressTimeoutMs: Long,
) {
    private var startX = 0f
    private var startY = 0f
    private var width = 0f
    private var startPosition = 0L
    private var duration = 0L
    private var tracking = false
    private var dragging = false
    private var target = 0L
    private var downTimeMs = 0L

    fun begin(x: Float, y: Float, widthPx: Float, positionMs: Long, durationMs: Long, edgePx: Float, timeMs: Long) {
        finish(false)
        if (durationMs <= 0 || widthPx <= 0 || x < edgePx || x > widthPx - edgePx) return
        startX = x
        startY = y
        width = widthPx
        startPosition = positionMs.coerceIn(0, durationMs)
        duration = durationMs
        target = startPosition
        downTimeMs = timeMs
        tracking = true
    }

    fun move(x: Float, y: Float, timeMs: Long): VideoSeekPreview? {
        if (!tracking) return null
        val dx = x - startX
        val dy = y - startY
        if (!dragging) {
            if (maxOf(abs(dx), abs(dy)) <= touchSlop) return null
            // 长按成立之前已经滑动的手势整段放弃，不能滑着滑着突然变成快进。
            if (timeMs - downTimeMs < longPressTimeoutMs) {
                tracking = false
                return null
            }
            if (abs(dy) >= abs(dx)) {
                tracking = false
                return null
            }
            dragging = true
        }
        val offset = (dx.toDouble() / width * minOf(duration, 120_000L)).roundToLong()
        target = (startPosition + offset).coerceIn(0, duration)
        return VideoSeekPreview(target, duration)
    }

    fun finish(commit: Boolean): Long? {
        val result = target.takeIf { commit && dragging }
        tracking = false
        dragging = false
        return result
    }
}

/** 保留 Media3 的进度条和点击事件，仅在长按后的横向拖动越过阈值后接管事件。 */
internal class VideoSwipeSeekPlayerView(context: Context) : PlayerView(context) {
    var onSeekPreview: (VideoSeekPreview?) -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    private val gesture = VideoSwipeSeekGesture(
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
    )
    private var ownsTouch = false
    private var gestureMediaItem: MediaItem? = null
    private var gestureMediaIndex = -1

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSwipe()
                val current = player
                if (current != null && canSeek(current) && !hitsControl(this, event.rawX, event.rawY)) {
                    gestureMediaItem = current.currentMediaItem
                    gestureMediaIndex = current.currentMediaItemIndex
                    val insets = ViewCompat.getRootWindowInsets(this)
                        ?.getInsets(WindowInsetsCompat.Type.systemGestures())
                    val edge = maxOf(24f * resources.displayMetrics.density,
                        (insets?.left ?: 0).toFloat(), (insets?.right ?: 0).toFloat())
                    gesture.begin(event.x, event.y, width.toFloat(), current.currentPosition, current.duration, edge, event.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sameMedia() || event.pointerCount != 1) {
                    gesture.finish(false)
                    onSeekPreview(null)
                } else {
                    gesture.move(event.x, event.y, event.eventTime)?.let { preview ->
                        if (!ownsTouch) {
                            ownsTouch = true
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        onSeekPreview(preview)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gesture.finish(false)
                onSeekPreview(null)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = ownsTouch
                val target = gesture.finish(event.actionMasked == MotionEvent.ACTION_UP && sameMedia())
                cancelSwipe()
                if (consumed) {
                    target?.let(onSeek)
                    return true
                }
            }
        }
        return ownsTouch || super.dispatchTouchEvent(event)
    }

    fun cancelSwipe() {
        gesture.finish(false)
        ownsTouch = false
        gestureMediaItem = null
        gestureMediaIndex = -1
        onSeekPreview(null)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onDetachedFromWindow() {
        cancelSwipe()
        super.onDetachedFromWindow()
    }

    private fun sameMedia(): Boolean = player?.let {
        canSeek(it) && it.currentMediaItem == gestureMediaItem && it.currentMediaItemIndex == gestureMediaIndex
    } == true

    private fun canSeek(player: Player): Boolean = player.duration > 0 &&
        player.isCurrentMediaItemSeekable && !player.isPlayingAd &&
        player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    private fun hitsControl(group: ViewGroup, x: Float, y: Float): Boolean {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (!child.isShown) continue
            val bounds = Rect()
            if (!child.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) continue
            if (child is TimeBar || child.isClickable) return true
            if (child is ViewGroup && hitsControl(child, x, y)) return true
        }
        return false
    }
}
