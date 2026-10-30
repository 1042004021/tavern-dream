package com.taverndream.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 本地对话模型：MNN 格式，直接从 HuggingFace 按文件下载（不打 zip）。
 * 文件名与 taobao-mnn 官方仓库一致。
 */
data class LlmModel(
    val id: String,
    val name: String,
    val repo: String,
    val approxSize: String,
    val note: String,
)

object LlmModelManager {

    private val files = listOf(
        "config.json",
        "llm_config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "tokenizer.txt",
    )

    val models: List<LlmModel> = listOf(
        LlmModel(
            id = "qwen3-1p7b",
            name = "Qwen3 1.7B",
            repo = "taobao-mnn/Qwen3-1.7B-MNN",
            approxSize = "1.2GB",
            note = "默认档：角色扮演够用，速度约 38 字符/秒",
        ),
        LlmModel(
            id = "qwen3-0p6b",
            name = "Qwen3 0.6B",
            repo = "taobao-mnn/Qwen3-0.6B-MNN",
            approxSize = "450MB",
            note = "最轻：秒开，质量弱，适合低配机",
        ),
        LlmModel(
            id = "qwen2p5-1p5b",
            name = "Qwen2.5 1.5B",
            repo = "taobao-mnn/Qwen2.5-1.5B-Instruct-MNN",
            approxSize = "870MB",
            note = "老一代通用模型，中文稳、体积小",
        ),
        LlmModel(
            id = "qwen2p5-0p5b",
            name = "Qwen2.5 0.5B",
            repo = "taobao-mnn/Qwen2.5-0.5B-Instruct-MNN",
            approxSize = "280MB",
            note = "极小体积，只适合简单问答",
        ),
        LlmModel(
            id = "qwen3-4b",
            name = "Qwen3 4B",
            repo = "taobao-mnn/Qwen3-4B-MNN",
            approxSize = "2.6GB",
            note = "质量最好，但 12GB 机型会挤掉后台应用",
        ),
    )

    sealed class State {
        data object Idle : State()
        data class Downloading(
            val file: String,
            val index: Int,
            val count: Int,
            val bytes: Long,
            val total: Long,
        ) : State()

        data object Done : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun dir(context: Context, id: String): File =
        File(context.filesDir, "llm/$id").apply { if (!exists()) mkdirs() }

    fun isDownloaded(context: Context, model: LlmModel): Boolean {
        val d = dir(context, model.id)
        return File(d, "llm.mnn.weight").exists() && File(d, "runtime.json").exists()
    }

    fun runtimeConfigPath(context: Context, id: String): File =
        File(dir(context, id), "runtime.json")

    suspend fun download(context: Context, model: LlmModel, mirror: String, threads: Int) =
        withContext(Dispatchers.IO) {
            val dest = dir(context, model.id)
            val base = mirror.trimEnd('/') + "/" + model.repo + "/resolve/main/"
            try {
                files.forEachIndexed { index, name ->
                    downloadFile(base + name, File(dest, name), name, index, files.size)
                }
                writeRuntimeConfig(dest, threads)
                _state.value = State.Done
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: "下载失败")
            }
        }

    /** 单文件下载，带断点续传与 3 次重试。 */
    private fun downloadFile(
        url: String,
        target: File,
        name: String,
        index: Int,
        count: Int,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            var offset = if (target.exists()) target.length() else 0L
            try {
                val builder = Request.Builder().url(url)
                if (offset > 0) builder.addHeader("Range", "bytes=$offset-")
                client.newCall(builder.build()).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        throw IllegalStateException("$name: HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("$name: 空响应")
                    // 服务器不支持 Range 时会回 200，必须从头写
                    if (resp.code == 200) offset = 0L
                    val remain = body.contentLength()
                    val total = if (resp.code == 206) offset + remain else remain
                    if (total > 0 && offset == total) {
                        _state.value = State.Downloading(name, index, count, total, total)
                        return
                    }
                    var done = offset
                    var last = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(target, offset > 0).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 400) {
                                    last = now
                                    _state.value = State.Downloading(name, index, count, done, total)
                                }
                            }
                        }
                    }
                    _state.value = State.Downloading(name, index, count, done, total)
                    if (total <= 0 || done >= total) return
                    throw IllegalStateException("$name: 连接提前结束（$done/$total）")
                }
            } catch (t: Throwable) {
                if (attempt >= 3) throw t
                _state.value = State.Downloading(name, index, count, offset, 0L)
            }
        }
    }

    /**
     * 把模型自带的 config.json 与我们需要的运行参数合并成 runtime.json。
     * 不动原文件，避免影响以后手动替换模型。
     */
    private fun writeRuntimeConfig(dir: File, threads: Int) {
        val source = File(dir, "config.json")
        val base: JsonObject = if (source.exists()) {
            runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
        } else {
            JsonObject(emptyMap())
        }
        val merged = buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("llm_model", "llm.mnn")
            put("llm_weight", "llm.mnn.weight")
            put("llm_config", "llm_config.json")
            put("backend_type", "cpu")
            put("thread_num", threads)
            put("precision", "low")
            put("memory", "low")
            put("power", "high")
            // 小模型容易复读同一句，重复惩罚统一提到 1.15
            put("penalty", 1.15)
            put("prompt_cache", true)
            put("use_mmap", true)
            put("tmp_path", dir.absolutePath)
            put("max_new_tokens", 1024)
            // 关闭 Qwen3 的思考模式：模板里不注入  thinking，避免角色扮演被推理过程污染
            put("jinja", mergeJinja(base))
        }
        File(dir, "runtime.json").writeText(json.encodeToString(JsonObject.serializer(), merged))
    }

    /** 保留模型自带的 jinja.chat_template，只把 enable_thinking=false 塞进 context。 */
    private fun mergeJinja(base: JsonObject): JsonObject {
        val jinja = base["jinja"] as? JsonObject ?: JsonObject(emptyMap())
        val context = jinja["context"] as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            jinja.forEach { (k, v) -> put(k, v) }
            put("context", buildJsonObject {
                context.forEach { (k, v) -> put(k, v) }
                put("enable_thinking", false)
            })
        }
    }
}