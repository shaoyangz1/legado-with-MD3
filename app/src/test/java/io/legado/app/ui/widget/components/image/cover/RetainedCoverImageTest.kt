package io.legado.app.ui.widget.components.image.cover

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.Image
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RetainedCoverImageTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var loader: ImageLoader
    private val path = mutableStateOf("entry")
    private val width = mutableStateOf(240)
    private val requestRevision = mutableStateOf(0)
    private val requests = CopyOnWriteArrayList<String>()
    private val requestSizes = CopyOnWriteArrayList<Size>()
    private val response = CompletableDeferred<Image>()
    private val successes = AtomicInteger()
    private val errors = AtomicInteger()

    @Before
    fun setUp() {
        loader = ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .memoryCache { MemoryCache.Builder().maxSizeBytes(1024 * 1024).build() }
            .components {
                add(Interceptor { chain ->
                    requests += chain.request.data.toString()
                    requestSizes += chain.size
                    SuccessResult(response.await(), chain.request, DataSource.NETWORK)
                })
            }.build()
        cache("entry", android.graphics.Color.RED)
    }

    @After
    fun tearDown() {
        loader.shutdown()
    }

    @Test
    fun sameUrlReusesDecodedEntryEvenWhenDetailIsLargerOrCacheIsEvicted() {
        show()
        compose.waitForIdle()
        assertEquals(Color.Red, pixel())
        compose.runOnIdle {
            loader.memoryCache?.clear()
            width.value = 300
        }
        compose.waitForIdle()
        assertEquals(Color.Red, pixel())
        assertTrue("same URL must not even execute the loader", requests.isEmpty())
    }

    @Test
    fun changedUrlKeepsOldImageDuringLoadAndDoesNotFadeThroughWhite() {
        show()
        compose.runOnIdle { path.value = "detail" }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
        assertEquals(listOf("detail"), requests.toList())
        assertEquals(Color.Red, pixel())
        compose.mainClock.autoAdvance = false
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil { successes.get() >= 2 }
        compose.mainClock.advanceTimeBy(100)
        val halfway = pixel()
        assertTrue("sample an actual intermediate frame", halfway.red > 0f && halfway.blue > 0f)
        assertEquals("white background must never show through", 0f, halfway.green, 0.01f)
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
        compose.runOnIdle { width.value = 300 }
        compose.waitForIdle()
        assertEquals(listOf("detail"), requests.toList())
    }

    @Test
    fun failedReplacementKeepsOldImageInsteadOfFallingBackToBlank() {
        show()
        compose.runOnIdle { path.value = "detail" }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
        assertEquals(listOf("detail"), requests.toList())
        response.completeExceptionally(IOException("test failure"))
        compose.waitForIdle()
        assertEquals(Color.Red, pixel())
        assertEquals(0, errors.get())
    }

    @Test
    fun sameUrlWithoutImageStillLoadsRatherThanTreatingUrlRecordAsCache() {
        loader.memoryCache?.clear()
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        show()
        compose.waitUntil { successes.get() == 1 }
        compose.waitForIdle()
        assertEquals(listOf("entry"), requests.toList())
        assertEquals(Color.Blue, pixel())
    }

    @Test
    fun alreadyCachedDetailUrlSwitchesWithoutExecutingLoader() {
        cache("detail", android.graphics.Color.BLUE)
        path.value = "detail"
        show()
        compose.waitUntil { successes.get() == 1 }
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun backdropKeepsEntryImageBeyondOldFadeDurationAndHonorsDecodeSize() {
        path.value = "detail"
        show(backdrop = true)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("old background must survive the loading interval", Color.Red, pixel())
        assertEquals(listOf(Size(384, 384)), requestSizes.toList())
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil(timeoutMillis = 5000) { successes.get() > 0 }
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
    }

    @Test
    fun sameImageRequestChangeDoesNotSnapAnActiveTransitionToTheEnd() {
        show()
        compose.runOnIdle { path.value = "detail" }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
        compose.mainClock.autoAdvance = false
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil { successes.get() >= 2 }
        compose.mainClock.advanceTimeBy(80)
        val before = pixel()
        assertTrue(before.red > 0f && before.blue > 0f)
        compose.runOnIdle { requestRevision.value++ }
        compose.mainClock.advanceTimeByFrame()
        val after = pixel()
        assertTrue("a request update must not finish the fade abruptly", after.red > 0f)
        assertEquals(0f, after.green, 0.01f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
        assertEquals(listOf("detail"), requests.toList())
    }

    @Test
    fun backdropFadeIsStillInProgressAtTwoHundredMilliseconds() {
        show(backdrop = true)
        compose.runOnIdle { path.value = "detail" }
        compose.waitForIdle()
        compose.waitUntil { requests.isNotEmpty() }
        compose.mainClock.autoAdvance = false
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil { successes.get() >= 2 }
        compose.mainClock.advanceTimeBy(240)
        val halfway = pixel()
        assertTrue(halfway.red > 0f && halfway.blue > 0f)
        assertEquals(0f, halfway.green, 0.01f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
    }

    @Test
    fun backdropWithoutEntryCacheFadesInInsteadOfAppearingInOneFrame() {
        loader.memoryCache?.clear()
        show(backdrop = true)
        compose.waitForIdle()
        compose.waitUntil { requests.isNotEmpty() }
        compose.mainClock.autoAdvance = false
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil { successes.get() > 0 }
        compose.mainClock.advanceTimeBy(160)
        val firstFrames = pixel()
        assertTrue(firstFrames.blue > 0f && firstFrames.blue < 1f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(Color.Blue, pixel())
    }

    @Test
    fun nextCoverArrivingMidFadeDoesNotFlashTheUnfinishedTarget() {
        show(backdrop = true)
        compose.runOnIdle { path.value = "detail" }
        compose.waitForIdle()
        compose.waitUntil { requests.isNotEmpty() }
        compose.mainClock.autoAdvance = false
        response.complete(ColorImage(android.graphics.Color.BLUE, 20, 20))
        compose.waitUntil { successes.get() >= 2 }
        compose.mainClock.advanceTimeBy(160)
        val before = pixel()
        assertTrue(before.red > 0f && before.blue > 0f)
        cache("next#book-info-backdrop", android.graphics.Color.GREEN)
        compose.runOnIdle { path.value = "next" }
        compose.mainClock.advanceTimeBy(32)
        val after = pixel()
        assertTrue("new result must not abruptly remove the original cover", after.red > 0f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(Color.Green, pixel())
    }

    private fun show(backdrop: Boolean = false) {
        compose.setContent {
            RetainedCoverImage(
                request = ImageRequest.Builder(RuntimeEnvironment.getApplication())
                    .data(path.value)
                    .diskCacheKey("test-revision-${requestRevision.value}")
                    .memoryCacheKey(if (backdrop) "${path.value}#book-info-backdrop" else path.value)
                    .apply { if (backdrop) size(384, 384) }.build(),
                imageLoader = loader,
                entryPath = "entry",
                entryMemoryCacheKey = "entry",
                modifier = Modifier.size(width.value.dp).background(if (backdrop) Color.Black else Color.White).testTag("cover"),
                onSuccess = { successes.incrementAndGet() },
                onError = { errors.incrementAndGet() },
                transitionDurationMillis = if (backdrop) 600 else 200,
                fadeInFirstImage = backdrop,
            )
        }
    }

    private fun cache(key: String, color: Int) {
        loader.memoryCache?.set(MemoryCache.Key(key), MemoryCache.Value(ColorImage(color, 20, 20)))
    }

    private fun pixel(): Color {
        val pixels = compose.onNodeWithTag("cover").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
}
