package com.taverndream.sd

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 端侧 SD 模型：一个 zip（HuggingFace 上托管），解压到 filesDir/models/<id>/。 */
data class SdModel(
    val id: String,
    val name: String,
    val fileUri: String,
    val runOnCpu: Boolean,
    val useCpuClip: Boolean,
    val isSdxl: Boolean = false,
    val approxSize: String = "",
    /** 模型原生出图边长：SD1.5 是 512，SD2.1 是 768。 */
    val nativeSize: Int = 512,
    /** 文本嵌入维度，透传给后端的 --text_embedding_size。 */
    val textEmbeddingSize: Int = 768,
    /** SD2.1 用 v-prediction，需要 --use_v_pred。 */
    val useVpred: Boolean = false,
    /**
     * 传给后端的采样器。dpm（DPM++ 2M）是 SD1.5/2.1 的默认值；
     * SDXL 必须用 euler_a —— 实测 DPM++ 2M 在这套 QNN SDXL 图上会输出纯噪声。
     */
    val scheduler: String = "dpm",
    /** 非 zip 模型：远端相对路径 -> 本地文件名。为空时走 zip 流程。 */
    val remoteFiles: List<Pair<String, String>> = emptyList(),
)

object SdModelManager {

    private const val PORT = 8081

    private val chipsets = mapOf(
        "SM8475" to "8gen1", "SM8450" to "8gen1",
        "SM8550" to "8gen2", "SM8550P" to "8gen2", "QCS8550" to "8gen2", "QCM8550" to "8gen2",
        "SM8650" to "8gen2", "SM8650P" to "8gen2",
        "SM8750" to "8gen2", "SM8750P" to "8gen2",
        "SM8850" to "8gen2", "SM8850P" to "8gen2",
        "SM8735" to "8gen2", "SM8845" to "8gen2",
    )

    fun soc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "CPU"

    /** SD2.1 仓库按 8gen1/2/3/4 分档，比 SD1.5 分得更细。 */
    private val sd21Chipsets = mapOf(
        "SM8750" to "8gen4", "SM8750P" to "8gen4",
        "SM8850" to "8gen4", "SM8850P" to "8gen4",
        "SM8650" to "8gen3", "SM8650P" to "8gen3",
        "SM8550" to "8gen2", "SM8550P" to "8gen2", "QCS8550" to "8gen2", "QCM8550" to "8gen2",
        "SM8450" to "8gen1", "SM8475" to "8gen1",
    )

    fun sd21Suffix(): String? = sd21Chipsets[soc()]

    /** 与 local-dream 一致的芯片映射；非高通返回 null（不支持 NPU 模型）。 */
    fun suffix(): String? {
        chipsets[soc()]?.let { return it }
        if (!isQualcomm()) return null
        return if (soc().startsWith("SM")) "min" else null
    }

    fun isQualcomm(): Boolean {
        val soc = soc().uppercase()
        val prefixes = listOf(
            "SM", "QCS", "QCM", "CQ", "IPQ", "SXR", "AIC", "SSG",
            "SC", "SA", "SDM", "MSM", "QRB", "X1E", "X1P"
        )
        return prefixes.any { soc.startsWith(it) }
    }

    fun models(context: Context): List<SdModel> {
        val list = mutableListOf<SdModel>()
        suffix()?.let { s ->
            list += SdModel(
                id = "anythingv5",
                name = "Anything V5（二次元通用）",
                fileUri = "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_$s.zip",
                runOnCpu = false,
                useCpuClip = true,
                approxSize = "1.0GB",
            )
            list += SdModel(
                id = "meinamix",
                name = "MeinaMix V12（二次元精致）",
                fileUri = "xororz/sd-qnn/resolve/main/MeinaMixV12_qnn2.28_$s.zip",
                runOnCpu = false,
                useCpuClip = true,
                approxSize = "1.0GB",
            )
            list += SdModel(
                id = "majicmix",
                name = "MajicMix Realistic V7（写实人像）",
                fileUri = "xororz/sd-qnn/resolve/main/MajicmixRealisticV7_qnn2.28_$s.zip",
                runOnCpu = false,
                useCpuClip = true,
                approxSize = "1.0GB",
            )
            list += SdModel(
                id = "dreamshaper",
                name = "DreamShaper V8（通用插画）",
                fileUri = "xororz/sd-qnn/resolve/main/DreamShaperV8_qnn2.28_$s.zip",
                runOnCpu = false,
                useCpuClip = true,
                approxSize = "1.0GB",
            )
        }
        sd21Suffix()?.let { s21 ->
            val base = "xororz/SD21/resolve/main"
            list += SdModel(
                id = "sd21",
                name = "SD 2.1（提示词理解更好）",
                fileUri = "",
                runOnCpu = false,
                useCpuClip = false,
                approxSize = "1.3GB",
                // 实测：768 分辨率出噪声；512 正常。v-pred 打开会出纯色糊图，必须关掉。
                nativeSize = 512,
                textEmbeddingSize = 1024,
                useVpred = false,
                remoteFiles = listOf(
                    "$base/unet_$s21.bin" to "unet.bin",
                    "$base/clip_$s21.bin" to "clip.bin",
                    "$base/vae_decoder_$s21.bin" to "vae_decoder.bin",
                    "$base/tokenizer.json" to "tokenizer.json",
                ),
            )
        }
        list += SdModel(
            id = "anythingv5cpu",
            name = "Anything V5（CPU，通用）",
            fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip",
            runOnCpu = true,
            useCpuClip = false,
            approxSize = "1.1GB",
        )
        // SDXL：官方仓库只提供 8gen3 构建，8gen2/8gen3/8gen4 芯片共用同一份（Local Dream 同款）
        // 里程碑：1024 原生、单张 36 秒左右；调度器必须 euler_a，dpm 出噪声（BUG-Task08-001）
        if (suffix() != null) {
            list += SdModel(
                id = "illustrious",
                name = "Illustrious v16（SDXL 高清二次元）",
                fileUri = "xororz/sdxl-qnn/resolve/main/illustrious_v16_qnn2.28_8gen3.zip",
                runOnCpu = false,
                useCpuClip = true,
                isSdxl = true,
                approxSize = "4.2GB",
                nativeSize = 1024,
                textEmbeddingSize = 768,
                scheduler = "euler_a",
            )
        }
        return list
    }

    fun dir(context: Context, id: String): File =
        File(context.filesDir, "models/$id").apply { if (!exists()) mkdirs() }

    fun isDownloaded(context: Context, model: SdModel): Boolean {
        val d = dir(context, model.id)
        return File(d, if (model.runOnCpu) "unet.mnn" else "unet.bin").exists()
    }

    sealed class State {
        data object Idle : State()
        data class Downloading(val progress: Float, val bytes: Long, val total: Long) : State()
        data object Extracting : State()
        data object Done : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 单个文件最多重试几次（每次都会从断点续传）。 */
    private const val MAX_RETRY = 20

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(context: Context, model: SdModel, mirror: String) = withContext(Dispatchers.IO) {
        if (model.remoteFiles.isNotEmpty()) {
            downloadFiles(context, model, mirror)
            return@withContext
        }
        val url = mirror.trimEnd('/') + "/" + model.fileUri
        val zip = File(context.cacheDir, "${model.id}.zip")
        try {
            _state.value = State.Downloading(0f, 0, 0)
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("空响应")
                val total = body.contentLength()
                var done = 0L
                var last = 0L
                body.byteStream().use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 400) {
                                last = now
                                _state.value = State.Downloading(
                                    if (total > 0) done.toFloat() / total else 0f, done, total
                                )
                            }
                        }
                    }
                }
            }
            _state.value = State.Extracting
            val dest = dir(context, model.id)
            dest.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val name = e.name.substringAfterLast('/')
                        if (name.isNotEmpty() && !name.startsWith(".") && !name.startsWith("__MACOSX")) {
                            FileOutputStream(File(dest, name)).use { o -> zis.copyTo(o) }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            zip.delete()
            _state.value = State.Done
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "下载失败")
        }
    }

    /** SD2.1 这类散文件模型：逐个下载到 models/<id>/，进度按文件数加权。 */
    private fun downloadFiles(context: Context, model: SdModel, mirror: String) {
        val dest = dir(context, model.id).apply { mkdirs() }
        val n = model.remoteFiles.size
        try {
            _state.value = State.Downloading(0f, 0, 0)
            model.remoteFiles.forEachIndexed { index, (remote, local) ->
                val url = mirror.trimEnd('/') + "/" + remote
                val target = File(dest, local)
                val tmp = File(dest, "$local.part")
                fetchResumable(url, local, tmp) { done, total ->
                    val inner = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _state.value = State.Downloading(
                        progress = ((index + inner) / n).coerceIn(0f, 1f),
                        bytes = done,
                        total = total,
                    )
                }
                if (tmp.length() <= 0L) throw IllegalStateException("$local 下载不完整")
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("$local 保存失败")
            }
            _state.value = State.Done
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "下载失败")
        }
    }

    /**
     * 带断点续传的下载。
     *
     * aifasthub 的连接跑一段时间就会被服务端掐断（实测 20~160MB 不等），所以这里
     * 用 Range 从 .part 已有长度接着下，最多重试 [MAX_RETRY] 次；用户重新点下载时也能续上。
     */
    private fun fetchResumable(
        url: String,
        label: String,
        tmp: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        var attempt = 0
        var droppedToZero = false
        while (true) {
            val have = if (tmp.exists()) tmp.length() else 0L
            val req = Request.Builder().url(url).apply {
                if (have > 0) header("Range", "bytes=$have-")
            }.build()
            var total = 0L
            var wrote = have
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        throw IllegalStateException("HTTP ${resp.code}（$label）")
                    }
                    if (resp.code == 200 && have > 0) {
                        // 服务端不支持 Range，只能从头再来一次
                        tmp.delete()
                        if (droppedToZero) throw IllegalStateException("服务端不支持断点续传")
                        droppedToZero = true
                        return fetchResumable(url, label, tmp, onProgress)
                    }
                    val body = resp.body ?: throw IllegalStateException("空响应")
                    val len = body.contentLength()
                    total = if (len > 0) have + len else 0L
                    var last = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmp, true).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val m = input.read(buf)
                                if (m <= 0) break
                                out.write(buf, 0, m)
                                wrote += m
                                val now = System.currentTimeMillis()
                                if (now - last > 400) {
                                    last = now
                                    onProgress(wrote, total)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                attempt++
                val finished = total > 0 && tmp.length() >= total
                if (finished || attempt > MAX_RETRY) throw t
                Thread.sleep(1000L * attempt)
                continue
            }
            if (total > 0 && tmp.length() < total) {
                attempt++
                if (attempt > MAX_RETRY) throw IllegalStateException("下载中断（$label）")
                Thread.sleep(1000L * attempt)
                continue
            }
            onProgress(wrote, total)
            return
        }
    }

    fun port(): Int = PORT
}
