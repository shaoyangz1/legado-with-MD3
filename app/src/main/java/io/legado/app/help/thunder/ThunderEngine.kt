package io.legado.app.help.thunder

import com.xunlei.downloadlib.ThunderContext
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.GetTaskId
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 迅雷 BT 引擎的薄封装。
 *
 * 两段式：先只拉磁力元数据拿到文件列表，选中文件后再建 BT 任务，等下载开始后
 * 由 SDK 的本地 HTTP 流地址喂给 ExoPlayer。所有 SDK 调用串行执行（原生库不是并发安全的）。
 */
object ThunderEngine {

    private const val POLL_INTERVAL_MS = 200L
    private const val TIMEOUT_MS = 30_000L
    private const val STATUS_ERROR = 3
    private const val STATUS_DONE = 2

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    @Volatile
    private var activeTask: GetTaskId? = null

    @Volatile
    private var content: MagnetContent? = null

    data class MagnetFile(
        val index: Int,
        val name: String,
        val size: String,
        val sizeBytes: Long,
        val file: File,
    )

    data class MagnetContent(
        val name: String,
        val torrentFile: File,
        val dir: File,
        val files: List<MagnetFile>,
    )

    private fun helper(): XLTaskHelper {
        ThunderContext.init(appCtx)
        return XLTaskHelper.get()
    }

    /** 解析磁力元数据并返回可播放文件列表；解析完立即停掉元数据任务，保留 .torrent 文件。 */
    suspend fun resolve(magnet: String): MagnetContent = withContext(worker) {
        stopInternal(deleteFiles = true)
        val helper = helper()
        val dir = File(appCtx.cacheDir, "thunder/${MD5Utils.md5Encode(magnet)}").apply { mkdirs() }
        val task = helper.parse(magnet, dir)
        try {
            awaitTaskDone(helper, task)
            val torrentFile = task.saveFile
            val files = helper.getTorrentInfo(torrentFile).medias.map {
                MagnetFile(
                    index = it.fileIndex,
                    name = it.playName,
                    size = it.size,
                    sizeBytes = it.fileSize,
                    file = File(dir, it.playName),
                )
            }.sortedByDescending { it.sizeBytes }
            check(files.isNotEmpty()) { "磁力里没有可播放的视频文件" }
            MagnetContent(
                name = task.fileName,
                torrentFile = torrentFile,
                dir = dir,
                files = files,
            ).also { content = it }
        } finally {
            runCatching { helper.stopTask(task) }
        }
    }

    /** 只下载选中文件，返回 SDK 的本地播放地址。 */
    suspend fun play(file: MagnetFile): String = withContext(worker) {
        val current = checkNotNull(content) { "磁力尚未解析" }
        stopInternal(deleteFiles = false)
        val helper = helper()
        val task = helper.addTorrentTask(current.torrentFile, current.dir, file.index)
        activeTask = task
        try {
            awaitSubTaskStarted(helper, task, file.index)
            val url = helper.getLocalUrl(file.file)
            check(url.isNotBlank()) { "无法获取本地播放地址" }
            url
        } catch (e: Throwable) {
            stopInternal(deleteFiles = false)
            throw e
        }
    }

    /** 停止当前任务并清理已下载的分片；播放页关闭时调用。 */
    fun stop() {
        content = null
        scope.launch { stopInternal(deleteFiles = true) }
    }

    private fun stopInternal(deleteFiles: Boolean) {
        val task = activeTask ?: return
        activeTask = null
        runCatching {
            val helper = helper()
            if (deleteFiles) helper.deleteTask(task) else helper.stopTask(task)
        }
    }

    private suspend fun awaitTaskDone(helper: XLTaskHelper, task: GetTaskId) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val info = helper.getTaskInfo(task)
            when (info.taskStatus) {
                STATUS_ERROR -> throw IllegalStateException(info.errorMsg ?: "磁力解析失败")
                STATUS_DONE -> return
            }
            check(System.currentTimeMillis() < deadline) { "解析磁力超时" }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitSubTaskStarted(helper: XLTaskHelper, task: GetTaskId, index: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val info = helper.getBtSubTaskInfo(task, index).mTaskInfo
            when (info.taskStatus) {
                STATUS_ERROR -> throw IllegalStateException(info.errorMsg ?: "磁力下载失败")
                0 -> Unit
                else -> return
            }
            check(System.currentTimeMillis() < deadline) { "连接磁力节点超时" }
            delay(POLL_INTERVAL_MS)
        }
    }
}
