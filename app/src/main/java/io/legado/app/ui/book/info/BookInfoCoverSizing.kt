package io.legado.app.ui.book.info

internal const val BOOK_INFO_COVER_WIDTH_DP = 240f
internal const val BOOK_INFO_COVER_DEFAULT_ASPECT_RATIO = 5f / 7f

/** 入口比例优先；缺失时只接受首个有效加载比例，此后不随换图改变布局。 */
internal fun resolveBookInfoCoverRatio(
    adaptRatio: Boolean,
    resolvedRatio: Float?,
    loadedRatio: Float?,
): Float? = if (!adaptRatio) BOOK_INFO_COVER_DEFAULT_ASPECT_RATIO
else resolvedRatio?.takeIf { it.isFinite() && it > 0f }
    ?: loadedRatio?.takeIf { it.isFinite() && it > 0f }

/** 入口缓存决定本次详情页的封面比例；无缓存时使用稳定的默认比例。 */
internal fun bookInfoEntryCoverAspectRatio(adaptRatio: Boolean, cachedRatio: Float?): Float =
    cachedRatio?.takeIf { adaptRatio && it.isFinite() && it > 0f }
        ?: BOOK_INFO_COVER_DEFAULT_ASPECT_RATIO

internal fun bookInfoCoverHeightDp(
    aspectRatio: Float,
    widthDp: Float = BOOK_INFO_COVER_WIDTH_DP,
): Float {
    val safeAspectRatio =
        if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio
        else BOOK_INFO_COVER_DEFAULT_ASPECT_RATIO
    return widthDp / safeAspectRatio
}
