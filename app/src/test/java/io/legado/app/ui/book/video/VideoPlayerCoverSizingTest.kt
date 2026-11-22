package io.legado.app.ui.book.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerCoverSizingTest {

    @Test
    fun ignoresPreviousSessionCoverUntilRequestedBookIsLoaded() {
        assertEquals(
            "detail-cover",
            resolveVideoCoverUrl(
                requestedBookUrl = "book-b",
                sessionBookUrl = "book-a",
                sessionCoverUrl = "old-session-cover",
                fallbackCoverUrl = "detail-cover",
            ),
        )
    }

    @Test
    fun usesSessionCoverAfterRequestedBookLoads() {
        assertEquals(
            "loaded-cover",
            resolveVideoCoverUrl(
                requestedBookUrl = "book-b",
                sessionBookUrl = "book-b",
                sessionCoverUrl = "loaded-cover",
                fallbackCoverUrl = "detail-cover",
            ),
        )
    }

    @Test
    fun rssRouteDoesNotReusePreviousBookCover() {
        assertEquals(
            "",
            resolveVideoCoverUrl(
                requestedBookUrl = null,
                sessionBookUrl = "old-session-book",
                sessionCoverUrl = "old-session-cover",
                fallbackCoverUrl = null,
            ),
        )
    }

    @Test
    fun matchingSessionWithNoCoverKeepsDetailFallback() {
        assertEquals(
            "detail-cover",
            resolveVideoCoverUrl(
                requestedBookUrl = "book-b",
                sessionBookUrl = "book-b",
                sessionCoverUrl = "",
                fallbackCoverUrl = "detail-cover",
            ),
        )
    }

    @Test
    fun initialRatioUsesCachedAspectRatioWhenAvailable() {
        assertEquals(16f / 9f, videoPlayerInitialCoverAspectRatio(16f / 9f), 0.001f)
    }

    @Test
    fun initialRatioFallsBackToPortraitRatioWhenUncached() {
        assertEquals(5f / 7f, videoPlayerInitialCoverAspectRatio(null), 0.001f)
    }

    @Test
    fun invalidCachedRatioFallsBackToPortraitRatio() {
        assertEquals(5f / 7f, videoPlayerInitialCoverAspectRatio(Float.NaN), 0.001f)
    }
}
