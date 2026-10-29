package io.legado.app.ui.book.video

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w400dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class VideoSwipeSeekComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressSeekSurvivesAndroidViewAndPreviewOverlay() {
        val preview = mutableStateOf<VideoSeekPreview?>(null)
        val seeks = mutableListOf<Long>()
        val player = VideoSwipeSeekPlayerViewTest.SeekablePlayer()
        compose.setContent {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().testTag("video")) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VideoSwipeSeekPlayerView(context).apply {
                                this.player = player
                                onSeekPreview = { preview.value = it }
                                onSeek = { seeks += it }
                            }
                        },
                        onRelease = { it.player = null },
                    )
                    preview.value?.let {
                        Surface(Modifier.align(Alignment.Center)) { Text(it.positionMs.toString()) }
                    }
                }
            }
        }
        compose.onNodeWithTag("video").performTouchInput {
            down(Offset(width * 0.25f, height * 0.25f))
            advanceEventTime(700)
            moveTo(Offset(width * 0.5f, height * 0.25f))
            up()
        }
        compose.waitForIdle()
        assertTrue(seeks.isEmpty())
        assertEquals(1, player.seekRequests.size)
        assertTrue(player.seekRequests.single() in 130_000..140_000)
    }
}
