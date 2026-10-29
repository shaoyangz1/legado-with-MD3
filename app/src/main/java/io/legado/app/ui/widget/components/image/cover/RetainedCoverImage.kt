package io.legado.app.ui.widget.components.image.cover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.asPainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade

/**
 * 详情页持有入口已解码的图及其 URL。同 URL 不重新执行请求，也不因详情尺寸更大重采样。
 * 换 URL 时先加载，成功才替换；旧图保持不透明，直到新图完全覆盖它，失败则保留旧图。
 * 调用方用 bookUrl/sourceOrigin 为组合提供 key，避免把上一书源的图片带到新 owner。
 */
@Composable
internal fun RetainedCoverImage(
    request: ImageRequest,
    imageLoader: ImageLoader,
    entryPath: String,
    entryMemoryCacheKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onSuccess: (coil3.Image) -> Unit = {},
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val sizeResolver = rememberConstraintsSizeResolver()
    var displayed by remember(imageLoader, entryPath, entryMemoryCacheKey) {
        val cached = entryMemoryCacheKey?.let { imageLoader.memoryCache?.get(MemoryCache.Key(it)) }
        mutableStateOf(cached?.image?.let { entryPath to it })
    }
    var previous by remember(imageLoader, entryPath, entryMemoryCacheKey) {
        mutableStateOf<coil3.Image?>(null)
    }
    val opacity = remember { Animatable(1f) }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnError by rememberUpdatedState(onError)

    LaunchedEffect(request, imageLoader, entryPath, entryMemoryCacheKey) {
        // 快速连续换图时，先完成上一张图的显示，再等待新的请求。
        opacity.snapTo(1f)
        previous = null
        val path = request.data as String
        displayed?.takeIf { it.first == path }?.let {
            currentOnSuccess(it.second)
            return@LaunchedEffect
        }
        val cached = request.memoryCacheKey?.let {
            imageLoader.memoryCache?.get(MemoryCache.Key(it))?.image
        }
        val image = cached ?: (imageLoader.execute(
            request.newBuilder().apply {
                if (request.defined.sizeResolver == null) size(sizeResolver)
            }.crossfade(false).build()
        ) as? SuccessResult)?.image
        if (image == null) {
            if (displayed == null) currentOnError()
            return@LaunchedEffect
        }
        previous = displayed?.second
        opacity.snapTo(if (previous == null) 1f else 0f)
        displayed = path to image
        currentOnSuccess(image)
        opacity.animateTo(1f, tween(200))
        previous = null
    }

    Box(modifier.then(sizeResolver)) {
        previous?.let {
            Image(
                painter = it.asPainter(context),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        displayed?.second?.let {
            Image(
                painter = it.asPainter(context),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value },
            )
        }
    }
}
