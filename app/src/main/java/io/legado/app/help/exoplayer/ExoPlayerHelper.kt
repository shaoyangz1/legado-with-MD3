package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.HttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.gson.reflect.TypeToken
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import okhttp3.CacheControl
import okhttp3.Dns
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /** \u672C\u5730\u6D41\u91CD\u8BD5\uFF1A30 \u6B21\uFF0C\u6BCF\u6B21 1 \u79D2\uFF0C\u7ED9 BT \u7559\u51FA\u628A\u76EE\u6807\u4F4D\u7F6E\u4E0B\u8F7D\u51FA\u6765\u7684\u65F6\u95F4\u3002 */
    private const val LOCAL_STREAM_RETRY_COUNT = 30
    private const val LOCAL_STREAM_RETRY_DELAY_MS = 1_000L
    private val LOCAL_STREAM_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val builder = MediaItem.Builder().setUri(formatUrl)
        // SPLIT_TAG 破坏了 URI 后缀，DefaultMediaSourceFactory 无法据扩展名推断类型
        if (url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()

        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(resolvingDataSource)
                .setLoadErrorHandlingPolicy(mediaLoadErrorPolicy)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }

    /**
     * 迅雷磁力的本地流只能读出已下载的部分：拖到还没下到的位置，本地服务直接回 404。
     * 默认策略重试三次（约 3 秒）就放弃，表现成「进度条拉快一点就报链接 404」。
     * 这里只给本地流放宽重试窗口，等 BT 把目标位置下载出来；普通网络流仍走默认策略。
     */
    internal fun localStreamRetryDelayMs(error: IOException, errorCount: Int): Long? {
        val httpError = error as? HttpDataSource.HttpDataSourceException ?: return null
        if (httpError.dataSpec.uri.host !in LOCAL_STREAM_HOSTS) return null
        val code = (httpError as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != 404 && code != 416) return null
        return if (errorCount <= LOCAL_STREAM_RETRY_COUNT) LOCAL_STREAM_RETRY_DELAY_MS
        else C.TIME_UNSET
    }

    private val mediaLoadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            localStreamRetryDelayMs(loadErrorInfo.exception, loadErrorInfo.errorCount)
                ?: super.getRetryDelayMsFor(loadErrorInfo)
    }




    private val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(cacheDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    private val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        return@lazy CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            // 部分网络下 DNS 会返回不可用的 IPv6（伪造/污染地址），浏览器会回退，
            // OkHttp 却直接失败。有 IPv4 时只走 IPv4，纯 IPv6 站点仍用原解析结果。
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    val ipv4 = addresses.filterIsInstance<Inet4Address>()
                    return ipv4.ifEmpty { addresses }
                }
            })
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * Exoplayer 内置的缓存
     */
    private val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //100M的缓存
            LeastRecentlyUsedCacheEvictor((100 * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }

}