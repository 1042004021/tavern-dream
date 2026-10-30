package com.taverndream.sd

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 出图后的 4x 超分模型（realesrgan_x4plus_anime_6b）。
 *
 * 单个 bin 约 8MB，直接下载，不打 zip。与生图模型共用同一套芯片后缀映射，
 * 保证和 unet 的 HTP 架构一致。
 */
object UpscalerManager {

    private const val REPO = "xororz/upscaler/resolve/main/realesrgan_x4plus_anime_6b"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    sealed class State {
        data object Idle : State()
        data class Downloading(val progress: Float, val bytes: Long, val total: Long) : State()
        data object Done : State()
        data class Failed(val message: String) : State()
    }

    fun file(context: Context): File =
        File(File(context.filesDir, "upscaler"), "upscaler.bin")

    fun isDownloaded(context: Context): Boolean =
        file(context).let { it.exists() && it.length() > 1_000_000L }

    /** 当前机型对应仓库里的超分模型文件；非高通返回 null。 */
    fun remotePath(): String? =
        SdModelManager.suffix()?.let { "$REPO/upscaler_$it.bin" }

    suspend fun download(context: Context, mirror: String) = withContext(Dispatchers.IO) {
        val remote = remotePath() ?: run {
            _state.value = State.Failed("当前机型没有对应的超分模型")
            return@withContext
        }
        val url = mirror.trimEnd('/') + "/" + remote
        val out = file(context)
        val tmp = File(out.parentFile, "upscaler.bin.part")
        try {
            out.parentFile?.mkdirs()
            _state.value = State.Downloading(0f, 0, 0)
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("空响应")
                val total = body.contentLength()
                var done = 0L
                var last = System.currentTimeMillis()
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200) {
                                last = now
                                _state.value = State.Downloading(
                                    progress = if (total > 0) done.toFloat() / total else 0f,
                                    bytes = done,
                                    total = total,
                                )
                            }
                        }
                    }
                }
            }
            if (tmp.length() < 1_000_000L) throw IllegalStateException("文件不完整")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("保存失败")
            _state.value = State.Done
            android.util.Log.i("TD-SD", "超分模型下载完成：${out.absolutePath} (${out.length()} bytes)")
        } catch (t: Throwable) {
            tmp.delete()
            _state.value = State.Failed(t.message ?: "下载失败")
        }
    }
}