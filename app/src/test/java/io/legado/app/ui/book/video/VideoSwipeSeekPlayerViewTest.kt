package io.legado.app.ui.book.video

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.ui.TimeBar
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class VideoSwipeSeekPlayerViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java)
    private lateinit var view: VideoSwipeSeekPlayerView
    private var preview: VideoSeekPreview? = null
    private val seeks = mutableListOf<Long>()

    @Before
    fun setUp() {
        activity.setup()
        view = VideoSwipeSeekPlayerView(activity.get()).apply {
            player = SeekablePlayer()
            setControllerAnimationEnabled(false)
            setShowPreviousButton(false)
            setShowNextButton(false)
            onSeekPreview = { preview = it }
            onSeek = { seeks += it }
        }
        activity.get().setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 600)
    }

    @After
    fun tearDown() { activity.pause().stop().destroy() }

    @Test
    fun swipeOnPictureWorksWithControllerVisible() {
        view.showController()
        swipe()
    }

    @Test
    fun swipeOnPictureWorksWithControllerHidden() {
        view.hideController()
        swipe()
    }

    @Test
    fun holdingPictureWithoutMovingDoesNotStartScrub() {
        view.hideController()
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 250f, 150f, 0).let {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertNull("holding alone must not start scrubbing", preview)
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 250f, 150f, 0).let {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
        assertTrue("releasing without movement must not seek", (view.player as SeekablePlayer).seekRequests.isEmpty())
    }

    @Test
    fun swipeDuringLoadingStartsScrubbingWhenDurationArrives() {
        val current = SeekablePlayer(seekable = false, seekCommand = false,
            durationUs = C.TIME_UNSET, playbackState = Player.STATE_BUFFERING)
        view.player = current
        view.hideController()
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 150f, 0).let {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals(null, preview)
        current.finishLoading()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("waits for the finger to move", null, preview)
        event(MotionEvent.ACTION_MOVE, 180f)
        val target = preview!!.positionMs
        event(MotionEvent.ACTION_UP, 180f)
        assertEquals(listOf(target), current.seekRequests)
    }

    @Test
    fun releasedLoadingHoldDoesNotStartScrubbing() {
        val current = SeekablePlayer(seekable = false, seekCommand = false,
            durationUs = C.TIME_UNSET, playbackState = Player.STATE_BUFFERING)
        view.player = current
        view.hideController()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(down, down, action, 100f, 150f, 0).let {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        current.finishLoading()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals(null, preview)
        assertTrue(current.seekRequests.isEmpty())
    }

    @Test
    fun nonSeekableMediaOffersClickableFiveAndFifteenSecondFallbacks() {
        val current = SeekablePlayer(seekable = false, seekCommand = false)
        view.player = current
        view.showController()
        val back = view.findViewById<TextView>(androidx.media3.ui.R.id.exo_rew_with_amount)
        val forward = view.findViewById<TextView>(androidx.media3.ui.R.id.exo_ffwd_with_amount)
        assertEquals(View.VISIBLE, back.visibility)
        assertEquals(View.VISIBLE, forward.visibility)
        assertTrue(back.isEnabled && forward.isEnabled)
        view.hideController()
        view.showController()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("fallback remains enabled when controls reopen", back.isEnabled && forward.isEnabled)
        assertEquals("5", back.text.toString())
        assertEquals("15", forward.text.toString())
        back.performClick()
        forward.performClick()
        assertEquals(listOf(55_000L, 75_000L), seeks)

        current.changeCapabilities(seekable = true, seekCommand = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, back.visibility)
        assertEquals(View.GONE, forward.visibility)
        current.changeCapabilities(seekable = false, seekCommand = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, forward.visibility)
        assertTrue(forward.isEnabled)
    }

    @Test
    fun unknownDurationStillAllowsFallbackAndClampsBackToZero() {
        view.player = SeekablePlayer(seekable = false, seekCommand = false, durationUs = C.TIME_UNSET, positionMs = 2_000)
        view.showController()
        view.findViewById<View>(androidx.media3.ui.R.id.exo_rew_with_amount).performClick()
        view.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount).performClick()
        assertEquals(listOf(0L, 17_000L), seeks)
        view.player = null
        assertEquals(View.GONE, view.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount).visibility)
    }

    @Test
    fun fallbackForwardClampsToKnownDuration() {
        view.player = SeekablePlayer(seekable = false, seekCommand = false, positionMs = 295_000)
        view.showController()
        view.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount).performClick()
        assertEquals(listOf(300_000L), seeks)
    }

    @Test
    fun cancelingPictureDragCancelsNativeScrubWithoutSeeking() {
        view.showController()
        touch(MotionEvent.ACTION_DOWN, 250f, 150f, 0)
        touch(MotionEvent.ACTION_MOVE, 500f, 150f, 700)
        assertNotNull(preview)
        touch(MotionEvent.ACTION_CANCEL, 500f, 150f, 720)
        assertEquals(null, preview)
        assertTrue((view.player as SeekablePlayer).seekRequests.isEmpty())
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun losingSeekCommandCancelsNativeScrubWithoutSeeking() {
        val current = view.player as SeekablePlayer
        view.showController()
        touch(MotionEvent.ACTION_DOWN, 250f, 150f, 0)
        touch(MotionEvent.ACTION_MOVE, 500f, 150f, 700)
        assertNotNull(preview)
        current.changeCapabilities(seekable = false, seekCommand = false)
        shadowOf(Looper.getMainLooper()).idle()
        touch(MotionEvent.ACTION_UP, 500f, 150f, 720)
        assertEquals(null, preview)
        assertTrue(current.seekRequests.isEmpty())
    }

    @Test
    fun verticalSwipeOnLeftHalfAdjustsBrightness() {
        var side: Boolean? = null
        var fraction = 0f
        var ended = false
        view.onVerticalDragStart = { side = it }
        view.onVerticalDrag = { _, value -> fraction = value }
        view.onVerticalDragEnd = { ended = true }
        view.hideController()
        touch(MotionEvent.ACTION_DOWN, 250f, 300f, 0)
        touch(MotionEvent.ACTION_MOVE, 250f, 100f, 100)
        assertEquals(true, side)
        assertTrue("upward drag must raise the value", fraction > 0.3f)
        touch(MotionEvent.ACTION_UP, 250f, 100f, 200)
        assertTrue(ended)
        assertTrue((view.player as SeekablePlayer).seekRequests.isEmpty())
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun verticalSwipeOnRightHalfAdjustsVolume() {
        var side: Boolean? = null
        var fraction = 0f
        view.onVerticalDragStart = { side = it }
        view.onVerticalDrag = { _, value -> fraction = value }
        view.hideController()
        touch(MotionEvent.ACTION_DOWN, 750f, 300f, 0)
        touch(MotionEvent.ACTION_MOVE, 750f, 100f, 100)
        assertEquals(false, side)
        assertTrue("upward drag must raise the value", fraction > 0.3f)
        assertTrue((view.player as SeekablePlayer).seekRequests.isEmpty())
    }

    @Test
    fun lockedGesturesIgnoreSwipes() {
        var vertical = false
        var previews = 0
        view.onVerticalDragStart = { vertical = true }
        view.onSeekPreview = { if (it != null) previews++ }
        view.gesturesEnabled = false
        view.hideController()
        touch(MotionEvent.ACTION_DOWN, 250f, 300f, 0)
        touch(MotionEvent.ACTION_MOVE, 250f, 100f, 100)
        touch(MotionEvent.ACTION_MOVE, 750f, 300f, 150)
        touch(MotionEvent.ACTION_UP, 750f, 300f, 200)
        assertFalse(vertical)
        assertEquals(0, previews)
        assertTrue((view.player as SeekablePlayer).seekRequests.isEmpty())
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun lockButtonSitsNextToSettingsAndDisablesSwipes() {
        val bar = view.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
        val settings = view.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
        val lock = bar.getChildAt(bar.indexOfChild(settings) - 1) as ImageButton
        assertNotNull(lock.contentDescription)
        lock.performClick()
        view.hideController()
        touch(MotionEvent.ACTION_DOWN, 250f, 300f, 0)
        touch(MotionEvent.ACTION_MOVE, 250f, 100f, 100)
        touch(MotionEvent.ACTION_UP, 250f, 100f, 200)
        assertTrue((view.player as SeekablePlayer).seekRequests.isEmpty())
    }

    @Test
    fun seekStepButtonsSkipByTenSecondsByDefault() {
        val time = view.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_time)
        val rewind = time.getChildAt(time.childCount - 3) as ImageButton
        val forward = time.getChildAt(time.childCount - 2) as ImageButton
        rewind.performClick()
        forward.performClick()
        assertEquals(listOf(50_000L, 70_000L), seeks)
    }

    private fun swipe() {
        val bar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as TimeBar
        var nativePreview: Long? = null
        bar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { nativePreview = position }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { nativePreview = position }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {}
        })
        assertTrue("AndroidView must accept DOWN to receive MOVE/UP", touch(MotionEvent.ACTION_DOWN, 250f, 150f, 0))
        touch(MotionEvent.ACTION_MOVE, 500f, 150f, 700)
        assertNotNull("Clickable descendants: ${clickableViews(view)}", preview)
        val target = preview!!.positionMs
        assertEquals("overlay and native bar must share the same scrub position", nativePreview, target)
        val current = view.player as SeekablePlayer
        assertTrue("preview follows the slowed-down ratio", target in 80_000..90_000)
        assertTrue("drag must not seek before release", current.seekRequests.isEmpty())
        touch(MotionEvent.ACTION_UP, 500f, 150f, 720)
        assertEquals(listOf(target), current.seekRequests)
        assertTrue("gesture must use the native bar, not fallback SeekTo", seeks.isEmpty())
    }

    private fun touch(action: Int, x: Float, y: Float, elapsed: Long): Boolean =
        MotionEvent.obtain(1000L, 1000L + elapsed, action, x, y, 0).let {
            val handled = view.dispatchTouchEvent(it)
            it.recycle()
            handled
        }

    private fun clickableViews(group: ViewGroup): List<String> = buildList {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isShown && child.isClickable) add("${child.javaClass.simpleName}:${child.id}")
            if (child is ViewGroup) addAll(clickableViews(child))
        }
    }

    internal class SeekablePlayer(
        private var seekable: Boolean = true,
        private var seekCommand: Boolean = true,
        private var durationUs: Long = 300_000_000,
        private val positionMs: Long = 60_000,
        private var playbackState: Int = Player.STATE_READY,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seekRequests = mutableListOf<Long>()
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seekRequests += positionMs
            return Futures.immediateVoidFuture()
        }

        fun finishLoading() {
            durationUs = 300_000_000
            playbackState = Player.STATE_READY
            changeCapabilities(seekable = true, seekCommand = true)
        }

        fun changeCapabilities(seekable: Boolean, seekCommand: Boolean) {
            this.seekable = seekable
            this.seekCommand = seekCommand
            invalidateState()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_PLAY_PAUSE,
            ).apply { if (seekCommand) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }.build())
            .setPlaylist(listOf(MediaItemData.Builder("video")
                .setMediaItem(MediaItem.fromUri("https://example.test/video.mp4"))
                .setIsSeekable(seekable).setDurationUs(durationUs).build()))
            .setPlaybackState(playbackState)
            .setContentPositionMs(positionMs)
            .build()
    }
}
