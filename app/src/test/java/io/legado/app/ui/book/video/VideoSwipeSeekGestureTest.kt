package io.legado.app.ui.book.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSwipeSeekGestureTest {
    private val gesture = VideoSwipeSeekGesture(touchSlop = 8f)

    @Test
    fun rightSwipePreviewsForwardAndCommitsOnlyOnceOnRelease() {
        begin()
        assertEquals(VideoSeekPreview(85_000, 300_000), move(750f, 400f))
        assertEquals(85_000L, gesture.finish(true))
        assertNull(gesture.finish(true))
    }

    @Test
    fun leftSwipeMovesBackwardFromOriginalPosition() {
        begin()
        assertEquals(35_000L, move(250f, 400f)?.positionMs)
        assertEquals(10_000L, move(0f, 400f)?.positionMs)
    }

    @Test
    fun seekIsClampedAtBothEnds() {
        begin(position = 2135_000)
        assertEquals(300_000L, move(1000f, 400f)?.positionMs)
        begin(position = 10_000)
        assertEquals(0L, move(0f, 400f)?.positionMs)
    }

    @Test
    fun shortVideosUseTheirOwnDurationAsSwipeRange() {
        begin(position = 15_000, duration = 30_000)
        assertEquals(17_500L, move(750f, 400f)?.positionMs)
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
            gesture.begin(edgeX, 400f, 1000f, 60_000, 300_000, 24f)
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
    fun horizontalSwipeSeeksWithoutWaitingForLongPress() {
        begin()
        assertEquals(75_000L, move(650f, 400f)?.positionMs)
        assertEquals(85_000L, move(750f, 400f)?.positionMs)
        assertEquals(85_000L, gesture.finish(true))
    }

    @Test
    fun slightMovementThenSwipeSeeks() {
        begin()
        assertNull(gesture.move(503f, 402f))
        assertEquals(85_000L, gesture.move(750f, 400f)?.positionMs)
    }

    @Test
    fun releasingWithoutMovingDoesNotSeek() {
        begin()
        assertNull(gesture.move(500f, 400f))
        assertNull(gesture.finish(true))
    }

    private fun move(x: Float, y: Float) = gesture.move(x, y)

    private fun begin(position: Long = 60_000, duration: Long = 300_000) {
        gesture.begin(500f, 400f, 1000f, position, duration, 24f)
    }
}
