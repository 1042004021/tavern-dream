package com.taverndream.sd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 后端返回的是**原始像素**（RGB，channels=3），不是 PNG。
 * 需要自己转成 Bitmap 再编码 PNG。
 */
class SdImage(
    val width: Int,
    val height: Int,
    val channels: Int,
    val bytes: ByteArray,
)

/** 调用端侧 /generate（SSE 流式返回进度与最终图片）。 */
class SdClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun healthy(): Boolean = runCatching {
        client.newCall(
            Request.Builder().url("http://127.0.0.1:${SdBackendService.PORT}/health").build()
        ).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /**
     * 同步调用，需在 IO 线程执行。
     * @param onProgress (当前步, 总步数, 预览图 base64 可空)
     * @return 原始像素数据（宽、高、通道数、RGB 字节）
     */
    fun generate(
        prompt: String,
        negative: String,
        steps: Int,
        cfg: Float,
        width: Int,
        height: Int,
        seed: Long,
        scheduler: String = "dpm",
        onProgress: (Int, Int, String?) -> Unit,
    ): SdImage {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("negative_prompt", negative)
            put("steps", steps)
            put("cfg", cfg)
            put("width", width)
            put("height", height)
            put("seed", seed)
            put("scheduler", scheduler)
        }.toString()

        val req = Request.Builder()
            .url("http://127.0.0.1:${SdBackendService.PORT}/generate")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("生成失败 HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IllegalStateException("无响应体")
            var event = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        val obj = runCatching { json.parseToJsonElement(payload) as JsonObject }.getOrNull()
                            ?: continue
                        when (event.ifEmpty { strOf(obj, "type") }) {
                            "progress" -> {
                                val step = intOf(obj, "step")
                                val total = intOf(obj, "total_steps")
                                val img = strOf(obj, "image").takeIf { it.isNotBlank() }
                                onProgress(step, total, img)
                            }
                            "complete" -> {
                                val b64 = strOf(obj, "image")
                                if (b64.isBlank()) throw IllegalStateException("未收到图片数据")
                                return SdImage(
                                    width = intOf(obj, "width").takeIf { it > 0 } ?: width,
                                    height = intOf(obj, "height").takeIf { it > 0 } ?: height,
                                    channels = intOf(obj, "channels").takeIf { it > 0 } ?: 3,
                                    bytes = Base64.decode(b64, Base64.DEFAULT),
                                )
                            }
                            "error" -> throw IllegalStateException(strOf(obj, "message").ifBlank { "生成失败" })
                        }
                    }
                }
            }
        }
        throw IllegalStateException("未收到生成结果")
    }

    /**
     * 调本地 4x 超分：请求体是原始 RGB 像素，后端直接回 JPEG。
     * 阻塞调用，需在 IO 线程执行。
     */
    fun upscale(rgb: ByteArray, width: Int, height: Int, upscalerPath: String): android.graphics.Bitmap {
        val req = Request.Builder()
            .url("http://127.0.0.1:${SdBackendService.PORT}/upscale")
            .header("X-Image-Width", width.toString())
            .header("X-Image-Height", height.toString())
            .header("X-Upscaler-Path", upscalerPath)
            .post(rgb.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("超分失败 HTTP ${resp.code}")
            val body = resp.body?.bytes() ?: throw IllegalStateException("超分无响应体")
            return android.graphics.BitmapFactory.decodeByteArray(body, 0, body.size)
                ?: throw IllegalStateException("超分结果解码失败")
        }
    }

    private fun strOf(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.content.orEmpty()

    private fun intOf(o: JsonObject, k: String): Int =
        (o[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
}
