package io.legado.app.ui.book.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun VideoPlayerScreen(
    bookUrl: String,
    onBack: () -> Unit,
    viewModel: VideoPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    // 横屏全屏时系统栏会隐藏，insets 有动画延迟；按方向取固定值，避免按钮在切换瞬间漂移。
    val backTopPadding = if (
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp
    }
    val controlsVisible = remember { mutableStateOf(true) }
    var seekPreview by remember(bookUrl, state.chapterIndex) { mutableStateOf<VideoSeekPreview?>(null) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
    }
    var mediaOverlay by remember { mutableStateOf<VideoMediaOverlay?>(null) }
    var startBrightness by remember { mutableFloatStateOf(0f) }
    var startVolume by remember { mutableIntStateOf(0) }

    fun windowBrightnessFraction(): Float {
        val window = activity?.window ?: return 0.5f
        val current = window.attributes.screenBrightness
        return if (current >= 0f) current
        else Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }

    // 导航退场动画期间旧页面仍在组合中，等 onDispose 再恢复方向会让详情页先按横屏出现再转回去。
    val leaveScreen: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }
    BackHandler(onBack = leaveScreen)

    LaunchedEffect(bookUrl) {
        viewModel.init(bookUrl)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is VideoPlayerEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // 配置变化（旋转/分屏）重建时 ON_STOP 也会派发，此时不应暂停
        if (activity?.isChangingConfigurations != true) {
            viewModel.onIntent(VideoPlayerIntent.Pause)
        }
    }

    DisposableEffect(Unit) {
        val previousKeepScreenOn = view.keepScreenOn
        val previousBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
            activity?.window?.let { window ->
                if (window.attributes.screenBrightness != previousBrightness) {
                    val attrs = window.attributes
                    attrs.screenBrightness = previousBrightness
                    window.attributes = attrs
                }
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    LaunchedEffect(state.isFullscreen, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (state.isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoSwipeSeekPlayerView(context).apply {
                        useController = true
                        setShowPreviousButton(false)
                        setShowNextButton(false)
                        player = viewModel.exoPlayer
                        onControllerVisibilityChanged = { visibility ->
                            controlsVisible.value = visibility == View.VISIBLE
                        }
                        setFullscreenButtonClickListener {
                            viewModel.onIntent(VideoPlayerIntent.ToggleFullscreen)
                        }
                    }
                },
                update = { playerView ->
                    playerView.onSeekPreview = { seekPreview = it }
                    playerView.onSeek = { viewModel.onIntent(VideoPlayerIntent.SeekTo(it)) }
                    playerView.onVerticalDragStart = { isLeft ->
                        if (isLeft) {
                            startBrightness = windowBrightnessFraction()
                            mediaOverlay = VideoMediaOverlay.Brightness(startBrightness)
                        } else {
                            startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            mediaOverlay = VideoMediaOverlay.Volume(startVolume.toFloat() / maxVolume)
                        }
                    }
                    playerView.onVerticalDrag = { isLeft, fraction ->
                        if (isLeft) {
                            val target = (startBrightness + fraction).coerceIn(0.01f, 1f)
                            activity?.window?.let { window ->
                                val attrs = window.attributes
                                attrs.screenBrightness = target
                                window.attributes = attrs
                            }
                            mediaOverlay = VideoMediaOverlay.Brightness(target)
                        } else {
                            val target = (startVolume + fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            mediaOverlay = VideoMediaOverlay.Volume(target.toFloat() / maxVolume)
                        }
                    }
                    playerView.onVerticalDragEnd = { mediaOverlay = null }
                },
                onRelease = {
                    it.cancelSwipe()
                    it.player = null
                },
            )
            seekPreview?.let { preview ->
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White,
                ) {
                    Text(
                        text = "${DateUtils.formatElapsedTime(preview.positionMs / 1000)} / " +
                            DateUtils.formatElapsedTime(preview.durationMs / 1000),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
            mediaOverlay?.let { overlay ->
                val icon = when (overlay) {
                    is VideoMediaOverlay.Brightness -> Icons.Default.Brightness6
                    is VideoMediaOverlay.Volume -> Icons.AutoMirrored.Filled.VolumeUp
                }
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = icon, contentDescription = null)
                        Text(
                            text = "${(overlay.fraction * 100).roundToInt()}%",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (controlsVisible.value) {
                IconButton(
                    onClick = leaveScreen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = backTopPadding, start = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White,
                    )
                }
            }
            if (state.error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = state.error ?: "", color = Color.White)
                    Button(
                        onClick = { viewModel.onIntent(VideoPlayerIntent.Retry) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Stable
private sealed interface VideoMediaOverlay {
    val fraction: Float

    data class Brightness(override val fraction: Float) : VideoMediaOverlay

    data class Volume(override val fraction: Float) : VideoMediaOverlay
}
