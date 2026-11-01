package io.legado.app.ui.book.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.thunder.ThunderEngine
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.utils.startService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 播放页的状态宿主：把 [VideoPlay] 的会话状态映射成 UiState，用户动作分发给模型与服务。
 *
 * 播放器实例不在 ViewModel 里：退出播放页只是离开 UI，播放由 VideoPlayService 继续托管；
 * 这里只在清除时补一次进度保存。
 */
class VideoPlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<VideoPlayerEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var initializedBookUrl: String? = null

    private val _magnetState = MutableStateFlow<MagnetPanelState?>(null)
    val magnetState = _magnetState.asStateFlow()
    private var magnetUrl: String? = null
    private var magnetTitle: String? = null
    private var magnetAuthor: String? = null
    private var magnetIntro: String? = null
    private var magnetFiles: List<ThunderEngine.MagnetFile> = emptyList()
    private var lastMagnetPlayUrl: String? = null
    private var magnetJob: Job? = null

    /**
     * 播放进度：冷流，只有播放页在收集时才跑，离开页面自动停。
     *
     * 不写进 [_uiState]：500ms 一拍会让整页状态快照变化，信息区跟着重组。
     */
    val progress: Flow<VideoPlaybackProgress> = flow {
        while (currentCoroutineContext().isActive) {
            val player = VideoPlay.player
            emit(
                VideoPlaybackProgress(
                    positionMs = runCatching { player.currentPosition }
                        .getOrDefault(0L).coerceAtLeast(0L),
                    durationMs = runCatching { player.duration }
                        .getOrDefault(0L).coerceAtLeast(0L),
                )
            )
            delay(PROGRESS_INTERVAL_MS)
        }
    }

    init {
        viewModelScope.launch {
            VideoPlay.state.collect { state ->
                // 书源能力与书籍信息不在 VideoPlayState 里，随会话快照一起派生，
                // 免得 Screen 组合期去读全局模型
                val source = VideoPlay.bookSource
                val book = VideoPlay.book
                _uiState.update { current ->
                    current.copy(
                        bookName = state.bookName,
                        chapterTitle = state.chapterTitle,
                        volumes = state.volumes.toPersistentList(),
                        volumeIndex = state.volumeIndex,
                        episodes = state.episodes.toPersistentList(),
                        episodeIndex = state.episodeIndex,
                        isLoading = state.isLoading,
                        isPlaying = state.isPlaying,
                        error = state.error,
                        showDanmaku = VideoPlay.danmakuShow,
                        bookSourceUrl = source?.bookSourceUrl.orEmpty(),
                        bookSourceName = source?.bookSourceName.orEmpty(),
                        hasSourceLogin = !source?.loginUrl.isNullOrBlank(),
                        hasCustomButton = source?.customButton == true,
                        author = book?.author ?: state.author,
                        intro = if (book != null) {
                            // 列表/发现规则给的简介更适合播放页；没有时退回详情简介
                            book.listIntro?.takeIf { it.isNotBlank() } ?: book.intro.orEmpty()
                        } else {
                            state.intro
                        },
                        coverUrl = book?.coverUrl.orEmpty(),
                        bookUrl = book?.bookUrl.orEmpty(),
                    )
                }
            }
        }
        _uiState.update {
            it.copy(
                speed = VideoPlay.playSpeed,
                autoPlay = VideoPlay.autoPlay,
                startFull = VideoPlay.startFull,
                longPressSpeed = VideoPlay.longPressSpeed,
                seekStep = VideoPlay.seekStepSeconds,
                seekScreens = VideoPlay.seekScreensPerDuration,
                showDanmaku = VideoPlay.danmakuShow,
                floatingEnabled = VideoPlay.floatingEnabled,
            )
        }
    }

    fun init(bookUrl: String) {
        if (initializedBookUrl == bookUrl) return
        initializedBookUrl = bookUrl
        // 播放器由服务托管：先起服务，避免退出页面后播放被系统回收
        appCtx.startService<VideoPlayService> { action = IntentAction.play }
        viewModelScope.launch {
            VideoPlay.openBook(bookUrl)
        }
    }

    /** RSS 文章里的视频：定位信息足够，正文由 [VideoPlay] 按源规则重新解析。 */
    fun initRss(article: RssArticle) {
        val key = "rss:${article.origin}|${article.link}|${article.sort}"
        if (initializedBookUrl == key) return
        initializedBookUrl = key
        appCtx.startService<VideoPlayService> { action = IntentAction.play }
        viewModelScope.launch {
            VideoPlay.openRssArticle(article)
        }
    }

    /** 直链播放（磁力文件等）：没有书籍/章节上下文，界面其余部分与书籍播放一致。 */
    fun initMagnet(url: String, title: String?, author: String?, intro: String?) {
        if (magnetUrl == url && _magnetState.value != null) return
        magnetUrl = url
        magnetTitle = title
        magnetAuthor = author
        magnetIntro = intro
        resolveMagnet()
    }

    fun retryMagnet() = resolveMagnet()

    private fun resolveMagnet() {
        val url = magnetUrl ?: return
        magnetJob?.cancel()
        magnetFiles = emptyList()
        _magnetState.value = MagnetPanelState(loading = true)
        magnetJob = viewModelScope.launch {
            try {
                val content = ThunderEngine.resolve(url)
                magnetFiles = content.files
                _magnetState.update {
                    it?.copy(
                        loading = false,
                        files = content.files.map { file ->
                            MagnetFileChip(file.index, file.name, file.size)
                        }.toImmutableList(),
                        error = null,
                    )
                }
                // 默认直接播体积最大的文件
                content.files.firstOrNull()?.let { playMagnet(it.index) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _magnetState.update { it?.copy(loading = false, error = t.message) }
                _effects.tryEmit(VideoPlayerEffect.ShowToast(t.message.orEmpty()))
            }
        }
    }

    fun playMagnet(index: Int) {
        val file = magnetFiles.firstOrNull { it.index == index } ?: return
        if (_magnetState.value?.preparingIndex != null) return
        _magnetState.update { it?.copy(preparingIndex = index, error = null) }
        viewModelScope.launch {
            try {
                val localUrl = ThunderEngine.play(file)
                lastMagnetPlayUrl = localUrl
                appCtx.startService<VideoPlayService> { action = IntentAction.play }
                VideoPlay.openUrl(localUrl, magnetTitle, magnetAuthor, magnetIntro)
                _magnetState.update { it?.copy(preparingIndex = null, playingIndex = index) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _magnetState.update { it?.copy(preparingIndex = null, error = t.message) }
                _effects.tryEmit(VideoPlayerEffect.ShowToast(t.message.orEmpty()))
            }
        }
    }

    fun onIntent(intent: VideoPlayerIntent) {
        when (intent) {
            VideoPlayerIntent.TogglePlay -> VideoPlay.togglePlay()
            VideoPlayerIntent.Pause -> {
                VideoPlay.pause()
                VideoPlay.saveRead()
            }

            is VideoPlayerIntent.SeekTo -> VideoPlay.seekTo(intent.positionMs)
            is VideoPlayerIntent.SeekBy -> VideoPlay.seekBy(intent.seconds)
            is VideoPlayerIntent.SetSeekStep -> {
                VideoPlay.seekStepSeconds = intent.seconds
                _uiState.update { it.copy(seekStep = intent.seconds) }
            }
            is VideoPlayerIntent.SetSeekScreens -> {
                VideoPlay.seekScreensPerDuration = intent.value
                _uiState.update { it.copy(seekScreens = intent.value) }
            }
            VideoPlayerIntent.PreviousEpisode -> VideoPlay.upDurIndex(-1)
            VideoPlayerIntent.NextEpisode -> VideoPlay.upDurIndex(1)
            is VideoPlayerIntent.SelectVolume -> {
                VideoPlay.selectVolume(intent.index)
                _uiState.update { it.copy(sheet = null) }
            }

            is VideoPlayerIntent.SelectEpisode -> {
                VideoPlay.selectEpisode(_uiState.value.volumeIndex, intent.index)
                _uiState.update { it.copy(sheet = null) }
            }

            // 不关面板：倍速是拖滑块选的，松手就关会让用户没法接着微调或反悔
            is VideoPlayerIntent.SetSpeed -> {
                VideoPlay.setSpeed(intent.speed)
                _uiState.update { it.copy(speed = intent.speed) }
            }

            is VideoPlayerIntent.SetFullscreen ->
                _uiState.update { it.copy(isFullscreen = intent.enabled) }

            VideoPlayerIntent.Retry -> {
                val url = initializedBookUrl
                if (url != null) {
                    initializedBookUrl = null
                    init(url)
                }
            }

            is VideoPlayerIntent.OpenSheet -> _uiState.update { it.copy(sheet = intent.sheet) }
            VideoPlayerIntent.CloseSheet -> _uiState.update { it.copy(sheet = null) }

            is VideoPlayerIntent.SetAutoPlay -> {
                VideoPlay.autoPlay = intent.enabled
                _uiState.update { it.copy(autoPlay = intent.enabled) }
            }

            is VideoPlayerIntent.SetStartFull -> {
                VideoPlay.startFull = intent.enabled
                _uiState.update { it.copy(startFull = intent.enabled) }
            }

            is VideoPlayerIntent.SetLongPressSpeed -> {
                VideoPlay.longPressSpeed = intent.value
                _uiState.update { it.copy(longPressSpeed = intent.value) }
            }

            VideoPlayerIntent.ToggleDanmaku -> {
                val show = !VideoPlay.danmakuShow
                VideoPlay.danmakuShow = show
                _uiState.update { it.copy(showDanmaku = show) }
            }

            VideoPlayerIntent.ToggleFloating ->
                appCtx.startService<VideoPlayService> {
                    action = IntentAction.toggleVideoFloating
                }

            is VideoPlayerIntent.SetFloatingEnabled -> {
                VideoPlay.floatingEnabled = intent.enabled
                _uiState.update { it.copy(floatingEnabled = intent.enabled) }
            }

            VideoPlayerIntent.CopyPlayUrl -> copyPlayUrl()
            VideoPlayerIntent.OpenInExternalPlayer -> openExternal()
            VideoPlayerIntent.OpenSourceLogin -> emitSource(
                { VideoPlayerEffect.OpenSourceLogin(it) }
            )

            VideoPlayerIntent.OpenSourceEdit -> emitSource(
                { VideoPlayerEffect.OpenSourceEdit(it) }
            )

            VideoPlayerIntent.RunCustomButton -> runCustomButton()
            is VideoPlayerIntent.LongPressSpeed -> {
                val speed = if (intent.active) {
                    VideoPlay.longPressSpeed / 10f
                } else {
                    VideoPlay.playSpeed
                }
                VideoPlay.player.playbackParameters =
                    VideoPlay.player.playbackParameters.withSpeed(speed)
            }
        }
    }

    /** 书源相关动作都要求当前会话有书源（RSS/直链没有）。 */
    private fun emitSource(effect: (String) -> VideoPlayerEffect) {
        val sourceUrl = _uiState.value.bookSourceUrl
        if (sourceUrl.isBlank()) {
            _effects.tryEmit(
                VideoPlayerEffect.ShowToast(appCtx.getString(R.string.source_not_found))
            )
        } else {
            _effects.tryEmit(effect(sourceUrl))
        }
    }

    /** 自定义按钮：书源没声明按钮（或会话没有书源）时不做无意义回调。 */
    private fun runCustomButton() {
        val source = VideoPlay.bookSource
        val book = VideoPlay.book
        if (source == null || book == null || !source.customButton) {
            _effects.tryEmit(
                VideoPlayerEffect.ShowToast(appCtx.getString(R.string.source_no_custom_button))
            )
            return
        }
        _effects.tryEmit(
            VideoPlayerEffect.RunSourceCallback(
                event = SourceCallBack.CLICK_CUSTOM_BUTTON,
                source = source,
                book = book,
                chapter = VideoPlay.chapter,
            )
        )
    }

    private fun copyPlayUrl() {
        val url = VideoPlay.playUrl
        if (url.isNullOrBlank()) {
            _effects.tryEmit(
                VideoPlayerEffect.ShowToast(appCtx.getString(R.string.video_link_not_found))
            )
            return
        }
        // 书源可以接管复制行为，需要 Activity，交给宿主执行
        _effects.tryEmit(VideoPlayerEffect.CopyPlayUrl(url))
    }

    private fun openExternal() {
        val url = VideoPlay.playUrl
        if (url.isNullOrBlank()) {
            _effects.tryEmit(
                VideoPlayerEffect.ShowToast(appCtx.getString(R.string.video_link_not_found))
            )
            return
        }
        _effects.tryEmit(
            VideoPlayerEffect.OpenExternalPlayer(url, VideoPlay.videoTitle ?: VideoPlay.book?.name)
        )
    }

    override fun onCleared() {
        // 离开播放页不等于停止播放：只把进度落库，播放交给服务继续
        VideoPlay.saveRead()
        // 磁力流可能还在悬浮窗/后台播放，此时清理会让画面断流；
        // 留着任务，等下次解析或进程退出时收尾。
        val url = lastMagnetPlayUrl
        if (url == null || VideoPlay.playUrl != url || !VideoPlay.state.value.isPlaying) {
            ThunderEngine.stop()
        }
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
