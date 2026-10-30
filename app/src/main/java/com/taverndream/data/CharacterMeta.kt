package com.taverndream.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 角色辅助信息，每个角色生成一次后缓存。
 *
 * @property tags  英文 Stable Diffusion 外观 tag，用作每张图的「外观锚定」，让同一角色长相接近
 * @property intro 中文开局设定：世界观 / 你扮演谁 / 当前场景
 */
data class CharacterMeta(
    val tags: String = "",
    val intro: String = "",
) {
    val isEmpty: Boolean get() = tags.isBlank() && intro.isBlank()
}

/** 与角色卡同目录：characters/<id>/meta.json */
object CharacterMetaStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun file(context: Context, id: String): File =
        File(CharacterStore.dir(context, id), "meta.json")

    fun read(context: Context, id: String): CharacterMeta {
        val f = file(context, id)
        if (!f.exists()) return CharacterMeta()
        return runCatching {
            val o = json.parseToJsonElement(f.readText()) as JsonObject
            CharacterMeta(
                tags = o["tags"]?.jsonPrimitive?.content.orEmpty(),
                intro = o["intro"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.getOrDefault(CharacterMeta())
    }

    fun save(context: Context, id: String, meta: CharacterMeta) {
        val f = file(context, id)
        f.parentFile?.mkdirs()
        f.writeText(
            buildJsonObject {
                put("tags", meta.tags)
                put("intro", meta.intro)
            }.toString()
        )
    }
}