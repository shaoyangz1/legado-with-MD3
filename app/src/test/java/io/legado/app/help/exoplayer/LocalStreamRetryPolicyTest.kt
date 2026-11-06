package io.legado.app.help.exoplayer

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 迅雷本地流的重试判定。
 *
 * 只对 127.0.0.1/localhost 上的 404、416 放宽重试；普通网络流必须交回默认策略，
 * 否则真实站点的 404 会被拖成 30 秒才报错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class LocalStreamRetryPolicyTest {

    private fun error(code: Int, url: String): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "error",
            null,
            emptyMap(),
            DataSpec.Builder().setUri(url).build(),
            ByteArray(0),
        )

    @Test
    fun retriesLocalStream404() {
        assertEquals(
            1_000L,
            ExoPlayerHelper.localStreamRetryDelayMs(error(404, "http://127.0.0.1:1234/a.mp4"), 1),
        )
    }

    @Test
    fun retriesLocalStream416() {
        assertEquals(
            1_000L,
            ExoPlayerHelper.localStreamRetryDelayMs(error(416, "http://localhost:1234/a.mp4"), 1),
        )
    }

    @Test
    fun givesUpAfterRetryWindow() {
        assertEquals(
            C.TIME_UNSET,
            ExoPlayerHelper.localStreamRetryDelayMs(error(404, "http://127.0.0.1:1234/a.mp4"), 31),
        )
    }

    @Test
    fun leavesRemote404ToDefaultPolicy() {
        assertNull(ExoPlayerHelper.localStreamRetryDelayMs(error(404, "https://example.com/a.mp4"), 1))
    }

    @Test
    fun leavesOtherLocalErrorsToDefaultPolicy() {
        assertNull(ExoPlayerHelper.localStreamRetryDelayMs(error(500, "http://127.0.0.1:1234/a.mp4"), 1))
    }
}
