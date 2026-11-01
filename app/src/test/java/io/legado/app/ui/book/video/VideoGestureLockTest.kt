package io.legado.app.ui.book.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGestureLockTest {

    @Test
    fun lockingDisablesProgressSeekingOnly() {
        assertFalse(videoProgressSeekingEnabled(gestureLocked = true))
        assertTrue(videoProgressSeekingEnabled(gestureLocked = false))
    }
}
