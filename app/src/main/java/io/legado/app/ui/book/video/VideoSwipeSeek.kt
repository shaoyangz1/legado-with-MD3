package io.legado.app.ui.book.video

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.compose.runtime.Stable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import io.legado.app.R
import kotlin.math.abs
import kotlin.math.roundToLong

@Stable
internal data class VideoSeekPreview(val positionMs: Long, val durationMs: Long)

/** 横向拖动多少屏宽才相当于整段视频时长；值越大，进度变化越迟钝。 */
private const val SEEK_SCREENS_PER_DURATION = 3f

/** 横向拖动，一屏对应整段视频时长；以按下时的位置计算，避免播放时钟改变基准。 */
internal class VideoSwipeSeekGesture(
    private val touchSlop: Float,
) {
    private var startX = 0f
    private var startY = 0f
    private var width = 0f
    private var startPosition = 0L
    private var duration = 0L
    private var tracking = false
    private var dragging = false
    private var target = 0L

    fun begin(x: Float, y: Float, widthPx: Float, positionMs: Long, durationMs: Long, edgePx: Float) {
        finish(false)
        if (durationMs <= 0 || widthPx <= 0 || x < edgePx || x > widthPx - edgePx) return
        startX = x
        startY = y
        width = widthPx
        startPosition = positionMs.coerceIn(0, durationMs)
        duration = durationMs
        target = startPosition
        tracking = true
    }

    fun move(x: Float, y: Float): VideoSeekPreview? {
        if (!tracking) return null
        val dx = x - startX
        val dy = y - startY
        if (!dragging) {
            if (maxOf(abs(dx), abs(dy)) <= touchSlop) return null
            if (abs(dy) >= abs(dx)) {
                tracking = false
                return null
            }
            dragging = true
        }
        val offset = (dx.toDouble() / (width * SEEK_SCREENS_PER_DURATION) * duration).roundToLong()
        target = (startPosition + offset).coerceIn(0, duration)
        return VideoSeekPreview(target, duration)
    }

    /** 竖向滑动会让手势放弃追踪，调用方据此转到亮度/音量。 */
    fun isTracking(): Boolean = tracking

    fun finish(commit: Boolean): Long? {
        val result = target.takeIf { commit && dragging }
        tracking = false
        dragging = false
        return result
    }
}

/** 保留 Media3 的进度条和点击事件，仅在横向拖动越过阈值后接管事件；竖向拖动交给页面调亮度/音量。 */
internal class VideoSwipeSeekPlayerView(context: Context) : PlayerView(context) {
    var onSeekPreview: (VideoSeekPreview?) -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onControllerVisibilityChanged: (Int) -> Unit = {}
    /** isLeft 为 true 表示左半屏（亮度），false 表示右半屏（音量）。 */
    var onVerticalDragStart: (isLeft: Boolean) -> Unit = {}
    var onVerticalDrag: (isLeft: Boolean, fraction: Float) -> Unit = { _, _ -> }
    var onVerticalDragEnd: (isLeft: Boolean) -> Unit = {}
    var gesturesEnabled = true
        set(value) {
            if (!value) cancelSwipe()
            field = value
        }
    private val gesture = VideoSwipeSeekGesture(
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )
    private val timeBar get() = findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
    private var barDownTime = 0L
    private var barX = 0f
    private var savedControllerTimeout: Int? = null
    private var ownsTouch = false
    private var gestureMediaItem: MediaItem? = null
    private var gestureMediaIndex = -1
    private val refreshFallbackButtons = Runnable { updateFallbackSeekButtons() }
    private var pendingDown: MotionEvent? = null
    private var awaitingSeekInfo = false
    private var pendingEdge = 0f
    private var downX = 0f
    private var downY = 0f
    private var downEdge = 0f
    private var downOnControl = false
    private var verticalActive = false
    private var verticalLeft = false
    private var verticalStartY = 0f
    private var locked = false
    private lateinit var lockButton: ImageButton
    private var seekStepSeconds = 10
    private var stepBackButton: ImageButton? = null
    private var stepForwardButton: ImageButton? = null
    private var stepLabel: TextView? = null

    init {
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = previewScrub(position)
            override fun onScrubMove(timeBar: TimeBar, position: Long) = previewScrub(position)
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (ownsTouch) onSeekPreview(null)
            }
        })
        setControllerVisibilityListener(ControllerVisibilityListener { visibility ->
            // 控制栏重新显示也会刷新原生按钮的 enabled，等这一轮刷新完成再恢复兜底。
            removeCallbacks(refreshFallbackButtons)
            post(refreshFallbackButtons)
            onControllerVisibilityChanged(visibility)
        })
        installLockButton()
        installSeekStepControls()
    }

    /** 时间栏右侧加快退/快进/步长选择，步长 5、10、20 秒。 */
    private fun installSeekStepControls() {
        val time = findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_time) ?: return
        // 容器默认 noHideDescendants 会把新增按钮的无障碍信息一起隐藏。
        time.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val size = (36 * resources.displayMetrics.density).toInt()
        stepBackButton = seekStepButton(androidx.media3.ui.R.drawable.exo_styled_controls_rewind, size) {
            seekBy(-seekStepSeconds * 1000L)
        }
        stepForwardButton = seekStepButton(androidx.media3.ui.R.drawable.exo_styled_controls_fastforward, size) {
            seekBy(seekStepSeconds * 1000L)
        }
        stepLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(size / 6, 0, size / 6, 0)
            setOnClickListener { showSeekStepMenu(this) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, size
            )
        }
        time.addView(stepBackButton)
        time.addView(stepForwardButton)
        time.addView(stepLabel)
        updateSeekStepLabels()
    }

    private fun seekStepButton(iconRes: Int, size: Int, onClick: () -> Unit) =
        ImageButton(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            val typed = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, typed, true
                )
            ) {
                setBackgroundResource(typed.resourceId)
            }
            setImageResource(iconRes)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    private fun showSeekStepMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        listOf(5, 10, 20).forEach { seconds ->
            menu.menu.add(0, seconds, 0, "${seconds}s").apply {
                isCheckable = true
                isChecked = seconds == seekStepSeconds
            }
        }
        menu.menu.setGroupCheckable(0, true, true)
        menu.setOnMenuItemClickListener { item ->
            seekStepSeconds = item.itemId
            updateSeekStepLabels()
            true
        }
        menu.show()
    }

    private fun updateSeekStepLabels() {
        val seconds = seekStepSeconds
        stepBackButton?.contentDescription = resources.getQuantityString(
            androidx.media3.ui.R.plurals.exo_controls_rewind_by_amount_description,
            seconds, seconds,
        )
        stepForwardButton?.contentDescription = resources.getQuantityString(
            androidx.media3.ui.R.plurals.exo_controls_fastforward_by_amount_description,
            seconds, seconds,
        )
        stepLabel?.text = "${seconds}s"
        stepLabel?.contentDescription = context.getString(R.string.video_seek_step, seconds)
    }

    private fun seekBy(deltaMs: Long) {
        val current = player ?: return
        onSeek(clampSeekPosition(current.currentPosition + deltaMs, current.duration))
    }

    /** 把锁按钮放进控制栏，与设置按钮同一行。 */
    private fun installLockButton() {
        val basicControls = findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        val settings = findViewById<View>(androidx.media3.ui.R.id.exo_settings)
        val button = ImageButton(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            val typed = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, typed, true
                )
            ) {
                setBackgroundResource(typed.resourceId)
            }
            setImageResource(R.drawable.ic_video_unlock)
            contentDescription = context.getString(R.string.video_gesture_lock)
            setOnClickListener { toggleLock() }
        }
        val params = (settings?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { source ->
            LinearLayout.LayoutParams(source.width, source.height).apply {
                setMargins(source.leftMargin, source.topMargin, source.rightMargin, source.bottomMargin)
            }
        } ?: LinearLayout.LayoutParams(
            (40 * resources.displayMetrics.density).toInt(),
            (40 * resources.displayMetrics.density).toInt(),
        )
        val index = settings?.let { basicControls.indexOfChild(it) }
            ?.takeIf { it >= 0 } ?: basicControls.childCount
        basicControls.addView(button, index, params)
        lockButton = button
    }

    private fun toggleLock() {
        locked = !locked
        gesturesEnabled = !locked
        if (::lockButton.isInitialized) {
            lockButton.setImageResource(
                if (locked) R.drawable.ic_video_lock else R.drawable.ic_video_unlock
            )
            lockButton.contentDescription = context.getString(
                if (locked) R.string.video_gesture_unlock else R.string.video_gesture_lock
            )
        }
    }

    private val seekCapabilityListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (gestureMediaItem != null && !sameMediaIdentity()) cancelSwipe()
            if (awaitingSeekInfo) armPendingGesture()
            else if (ownsTouch && !sameMedia()) cancelSwipe()
            updateFallbackSeekButtons()
        }
    }

    override fun setPlayer(player: Player?) {
        getPlayer()?.removeListener(seekCapabilityListener)
        cancelSwipe()
        super.setPlayer(player)
        player?.addListener(seekCapabilityListener)
        updateFallbackSeekButtons()
    }

    private fun needsFallbackSeek(): Boolean = player?.let {
        !it.currentTimeline.isEmpty && it.playbackState != Player.STATE_IDLE &&
            !it.isPlayingAd && !canSeek(it)
    } == true

    private fun updateFallbackSeekButtons() {
        val show = needsFallbackSeek()
        setShowRewindButton(show)
        setShowFastForwardButton(show)
        // Media3 会按 seek command 禁用原生按钮；这里允许用户主动尝试绝对时间跳转。
        // 点击仍走页面的 SeekTo intent，不改变播放器报告的能力，也不保证媒体可跳转。
        listOf(
            androidx.media3.ui.R.id.exo_rew_with_amount to -5_000L,
            androidx.media3.ui.R.id.exo_ffwd_with_amount to 15_000L,
        ).forEach { (id, offset) ->
            findViewById<TextView>(id)?.apply {
                isEnabled = show
                alpha = 1f
                val seconds = (abs(offset) / 1000).toInt()
                text = seconds.toString()
                contentDescription = resources.getQuantityString(
                    if (offset < 0) androidx.media3.ui.R.plurals.exo_controls_rewind_by_amount_description
                    else androidx.media3.ui.R.plurals.exo_controls_fastforward_by_amount_description,
                    seconds, seconds,
                )
                setOnClickListener {
                    if (needsFallbackSeek()) {
                        player?.let { onSeek(clampSeekPosition(it.currentPosition + offset, it.duration)) }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSwipe()
                downX = event.x
                downY = event.y
                val current = player
                val onControl = hitsControl(this, event.rawX, event.rawY)
                downOnControl = onControl
                val insets = ViewCompat.getRootWindowInsets(this)
                    ?.getInsets(WindowInsetsCompat.Type.systemGestures())
                downEdge = maxOf(24f * resources.displayMetrics.density,
                    (insets?.left ?: 0).toFloat(), (insets?.right ?: 0).toFloat())
                if (current?.currentMediaItem != null && !current.isPlayingAd && !onControl) {
                    gestureMediaItem = current.currentMediaItem
                    gestureMediaIndex = current.currentMediaItemIndex
                    if (width > 0 && event.x >= downEdge && event.x <= width - downEdge) {
                        pendingDown = MotionEvent.obtain(event)
                        pendingEdge = downEdge
                        awaitingSeekInfo = true
                        armPendingGesture()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (awaitingSeekInfo) {
                    val down = pendingDown
                    if (!sameMediaIdentity() || event.pointerCount != 1 || down == null ||
                        maxOf(abs(event.x - down.x), abs(event.y - down.y)) > ViewConfiguration.get(context).scaledTouchSlop) {
                        cancelSwipe()
                    }
                } else if (gestureMediaItem != null && (!sameMedia() || event.pointerCount != 1)) {
                    cancelSwipe()
                } else {
                    val preview = gesture.move(event.x, event.y)
                    if (preview != null) {
                        if (!ownsTouch) {
                            startNativeScrub(preview, event.eventTime)
                        } else {
                            barX = scrubX(preview)
                            sendBarTouch(MotionEvent.ACTION_MOVE, event.eventTime)
                        }
                    } else if (verticalActive) {
                        verticalDrag(event.y)
                    } else if (!gesture.isTracking() && canStartVertical()) {
                        verticalActive = true
                        verticalLeft = downX < width / 2f
                        verticalStartY = downY
                        onVerticalDragStart(verticalLeft)
                        verticalDrag(event.y)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gestureMediaItem != null) cancelSwipe()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = ownsTouch
                val target = gesture.finish(event.actionMasked == MotionEvent.ACTION_UP && sameMedia())
                if (consumed && target != null) {
                    // 原生 TimeBar 的 onScrubStop 统一提交跳转，画面手势不再另发 SeekTo。
                    sendBarTouch(MotionEvent.ACTION_UP, event.eventTime)
                    ownsTouch = false
                }
                cancelSwipe()
                if (consumed) return true
            }
        }
        return ownsTouch || super.dispatchTouchEvent(event)
    }

    private fun armPendingGesture() {
        val down = pendingDown ?: return
        val current = player ?: return
        if (!canSeek(current)) return
        awaitingSeekInfo = false
        gesture.begin(down.x, down.y, width.toFloat(), current.currentPosition, current.duration, pendingEdge)
    }

    private fun canStartVertical(): Boolean =
        gestureMediaItem != null && !downOnControl && height > 0 &&
            downX >= downEdge && downX <= width - downEdge

    private fun verticalDrag(y: Float) {
        onVerticalDrag(verticalLeft, (verticalStartY - y) / height)
    }

    private fun startNativeScrub(preview: VideoSeekPreview, time: Long) {
        ownsTouch = true
        pendingDown?.let { down ->
            val cancel = MotionEvent.obtain(down).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            down.recycle()
        }
        pendingDown = null
        parent?.requestDisallowInterceptTouchEvent(true)
        savedControllerTimeout = controllerShowTimeoutMs
        controllerShowTimeoutMs = 0
        showController()
        barDownTime = time
        barX = scrubX(preview)
        sendBarTouch(MotionEvent.ACTION_DOWN, time)
    }

    private fun previewScrub(position: Long) {
        if (ownsTouch) player?.let { onSeekPreview(VideoSeekPreview(position, it.duration)) }
    }

    private fun scrubX(preview: VideoSeekPreview): Float {
        val bar = timeBar
        val fraction = preview.positionMs.toDouble() / preview.durationMs
        // 当前使用 Media3 默认样式；轨道还需扣除滑块半径，不能直接按 View 全宽换算。
        val scrubberSizeDp = maxOf(DefaultTimeBar.DEFAULT_SCRUBBER_ENABLED_SIZE_DP,
            DefaultTimeBar.DEFAULT_SCRUBBER_DISABLED_SIZE_DP, DefaultTimeBar.DEFAULT_SCRUBBER_DRAGGED_SIZE_DP)
        val scrubberPadding = ((scrubberSizeDp * resources.displayMetrics.density + 0.5f).toInt() + 1) / 2
        val left = (bar.paddingLeft + scrubberPadding).toFloat()
        val right = (bar.width - bar.paddingRight - scrubberPadding).toFloat().coerceAtLeast(left)
        return (left + fraction * (right - left)).toFloat()
    }

    private fun sendBarTouch(action: Int, time: Long) {
        val bar = timeBar
        val event = MotionEvent.obtain(barDownTime, time, action, barX, bar.height / 2f, 0)
        bar.dispatchTouchEvent(event)
        event.recycle()
    }

    fun cancelSwipe() {
        if (verticalActive) onVerticalDragEnd(verticalLeft)
        verticalActive = false
        pendingDown?.recycle()
        pendingDown = null
        awaitingSeekInfo = false
        if (ownsTouch) sendBarTouch(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis())
        savedControllerTimeout?.let { controllerShowTimeoutMs = it }
        savedControllerTimeout = null
        gesture.finish(false)
        ownsTouch = false
        gestureMediaItem = null
        gestureMediaIndex = -1
        onSeekPreview(null)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshFallbackButtons)
        cancelSwipe()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFallbackSeekButtons()
    }

    private fun sameMediaIdentity(): Boolean = player?.let {
        !it.isPlayingAd && it.currentMediaItem == gestureMediaItem && it.currentMediaItemIndex == gestureMediaIndex
    } == true

    private fun sameMedia(): Boolean = sameMediaIdentity() && player?.let(::canSeek) == true

    private fun canSeek(player: Player): Boolean = player.duration > 0 &&
        !player.isPlayingAd &&
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
