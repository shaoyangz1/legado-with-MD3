package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Test

class BookInfoCoverSizingTest {

    @Test
    fun entryCoverUsesDiscoveryRatioBeforeDetailImageLoads() {
        val ratio = bookInfoEntryCoverAspectRatio(true, 16f / 9f)
        assertEquals(202.5f, bookInfoCoverHeightDp(ratio, 360f), 0.01f)
    }

    @Test
    fun restrictedCoverIgnoresDiscoveryRatio() {
        assertEquals(5f / 7f, bookInfoEntryCoverAspectRatio(false, 16f / 9f), 0.01f)
    }

    @Test
    fun missingOrInvalidEntryRatioHasStableFallback() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { ratio ->
            assertEquals(5f / 7f, bookInfoEntryCoverAspectRatio(true, ratio), 0.01f)
        }
    }

    @Test
    fun standardPortraitRatioPreservesExistingDetailHeight() {
        assertEquals(336f, bookInfoCoverHeightDp(5f / 7f), 0.01f)
    }

    @Test
    fun wideCoversKeepOriginalRatio() {
        assertEquals(240f, bookInfoCoverHeightDp(1f), 0.01f)
    }

    @Test
    fun tallCoversUseWidthScaledMaximumHeight() {
        assertEquals(600f, bookInfoCoverHeightDp(0.4f), 0.01f)
    }

    @Test
    fun invalidRatiosUseTheStandardPortraitRatio() {
        assertEquals(336f, bookInfoCoverHeightDp(Float.NaN), 0.01f)
    }
}
