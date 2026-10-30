package com.taverndream.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 一条消息。role = user / assistant ；图片消息 text 为空、imagePath 有值。 */
data class ChatMessage(
    val role: String,
    val text: String,
    val imagePath: String? = null,
    val ts: Long = System.currentTimeMillis(),
)

/** 每个角色一份聊天文件：filesDir/chats/<charId>.json */
object ChatStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context, charId: String): File {
        val d = File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }
        return File(d, "$charId.json")
    }

    fun load(context: Context, charId: String): MutableList<ChatMessage> {
        val f = file(context, charId)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = json.parseToJsonElement(f.readText()) as JsonArray
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ChatMessage(
                    role = (o["role"] as? JsonPrimitive)?.content ?: "user",
                    text = (o["text"] as? JsonPrimitive)?.content.orEmpty(),
                    imagePath = (o["image"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                    ts = (o["ts"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, charId: String, messages: List<ChatMessage>) {
        val arr = JsonArray(messages.map {
            buildJsonObject {
                put("role", it.role)
                put("text", it.text)
                put("image", it.imagePath.orEmpty())
                put("ts", it.ts)
            }
        })
        file(context, charId).writeText(arr.toString())
    }

    fun clear(context: Context, charId: String) {
        file(context, charId).delete()
    }
}
