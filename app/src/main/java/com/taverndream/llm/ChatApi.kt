package com.taverndream.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** OpenAI 兼容的 /chat/completions 流式客户端。 */
class ChatApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    /**
     * 阻塞式流式请求，需在 IO 线程调用。
     * @param onDelta 每收到一段文本回调一次
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int,
        disableThinking: Boolean = false,
        onDelta: (String) -> Unit,
    ) {
        try {
            streamOnce(baseUrl, apiKey, model, messages, temperature, maxTokens, disableThinking, onDelta)
        } catch (t: Throwable) {
            // 个别服务不认识 enable_thinking 会回 400，自动退回带思考再试一次
            val msg = t.message.orEmpty()
            if (!disableThinking || !msg.contains("HTTP 400")) throw t
            streamOnce(baseUrl, apiKey, model, messages, temperature, maxTokens, false, onDelta)
        }
    }

    /** 单次请求；[stream] 负责在不支持 enable_thinking 的服务上自动降级。 */
    private fun streamOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmMessage>,
        temperature: Float,
        maxTokens: Int,
        disableThinking: Boolean,
        onDelta: (String) -> Unit,
    ) {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            // 大多数 OpenAI 兼容服务会忽略未知字段；个别服务会报 400，设置里可关掉
            if (disableThinking) put("enable_thinking", false)
            put("messages", JsonArray(messages.map {
                buildJsonObject {
                    put("role", it.role)
                    put("content", it.content)
                }
            }))
        }.toString()

        val req = Request.Builder()
            .url(endpoint(baseUrl))
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(500)
                throw IllegalStateException("HTTP ${resp.code}: $err")
            }
            val source = resp.body?.source() ?: throw IllegalStateException("响应为空")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(payload) as JsonObject }.getOrNull() ?: continue
                val choices = obj["choices"] as? JsonArray ?: continue
                val delta = (choices.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: continue
                val content = (delta["content"] as? JsonPrimitive)?.content.orEmpty()
                if (content.isNotEmpty()) onDelta(content)
            }
        }
    }
}
