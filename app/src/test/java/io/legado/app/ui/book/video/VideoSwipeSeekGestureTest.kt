package io.legado.app.ui.book.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSwipeSeekGestureTest {
    private val gesture = VideoSwipeSeekGesture(touchSlop = 8f, longPressTimeoutMs = 500)

    @Test
    fun rightSwipePreviewsForwardAndCommitsOnlyOnceOnRelease() {
        begin()
        assertEquals(VideoSeekPreview(90_000, 300_000), move(750f, 400f))
        assertEquals(90_000L, gesture.finish(true))
        assertNull(gesture.finish(true))
    }

    @Test
    fun leftSwipeMovesBackwardFromOriginalPosition() {
        begin()
        assertEquals(30_000L, move(250f, 400f)?.positionMs)
        assertEquals(0L, move(0f, 400f)?.positionMs)
    }

    @Test
    fun seekIsClampedAtBothEnds() {
        begin(position = 290_000)
        assertEquals(300_000L, move(1000f, 400f)?.positionMs)
        begin(position = 10_000)
        assertEquals(0L, move(0f, 400f)?.positionMs)
    }

    @Test
    fun shortVideosUseTheirOwnDurationAsSwipeRange() {
        begin(position = 15_000, duration = 30_000)
        assertEquals(22_500L, move(750f, 400f)?.positionMs)
    }

    @Test
    fun tapsAndSmallMovementsDoNotSeek() {
        begin()
        assertNull(move(506f, 403f))
        assertNull(gesture.finish(true))
    }

    @Test
    fun verticalGestureCannotTurnIntoASeekLater() {
        begin()
        assertNull(move(502f, 450f))
        assertNull(move(900f, 450f))
        assertNull(gesture.finish(true))
    }

    @Test
    fun cancellationDropsPreviewWithoutSeeking() {
        begin()
        move(750f, 400f)
        assertNull(gesture.finish(false))
        assertNull(move(900f, 400f))
        assertNull(gesture.finish(true))
    }

    @Test
    fun edgeGesturesRemainAvailableForSystemNavigation() {
        listOf(0f, 20f, 980f, 1000f).forEach { edgeX ->
            gesture.begin(edgeX, 400f, 1000f, 60_000, 300_000, 24f, timeMs = 0)
            assertNull(move(500f, 400f))
            assertNull(gesture.finish(true))
        }
    }

    @Test
    fun unknownDurationDoesNotEnableSeeking() {
        begin(duration = 0)
        assertNull(move(750f, 400f))
        assertNull(gesture.finish(true))
        begin(duration = -1)
        assertNull(move(750f, 400f))
    }

    @Test
    fun ordinarySwipeDoesNotSeekEvenIfItLaterExceedsLongPressDuration() {
        begin()
        assertNull(gesture.move(650f, 400f, timeMs = 100))
        assertNull(gesture.move(750f, 400f, timeMs = 600))
        assertNull(gesture.finish(true))
    }

    @Test
    fun slightMovementWhileHoldingStillAllowsLongPressSeek() {
        begin()
        assertNull(gesture.move(503f, 402f, timeMs = 200))
        assertEquals(90_000L, gesture.move(750f, 400f, timeMs = 500)?.positionMs)
    }

    @Test
    fun releasingLongPressWithoutDraggingDoesNotSeek() {
        begin()
        assertNull(gesture.move(500f, 400f, timeMs = 800))
        assertNull(gesture.finish(true))
    }

    private fun move(x: Float, y: Float) = gesture.move(x, y, timeMs = 600)

    private fun begin(position: Long = 60_000, duration: Long = 300_000) {
        gesture.begin(500f, 400f, 1000f, position, duration, 24f, timeMs = 0)
    }
}
