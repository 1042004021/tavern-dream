package com.taverndream.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 角色卡本地仓库：filesDir/characters/<id>/{card.json, avatar.png} */
object CharacterStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun root(context: Context): File =
        File(context.filesDir, "characters").apply { if (!exists()) mkdirs() }

    fun dir(context: Context, id: String): File = File(root(context), id)

    fun list(context: Context): List<CharacterCard> {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { d ->
            val f = File(d, "card.json")
            if (!f.exists()) null else runCatching {
                fromJson(json.parseToJsonElement(f.readText()).let { it as JsonObject },
                    File(d, "avatar.png"))
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun import(context: Context, uri: Uri): CharacterCard? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val (parsed, avatar) = CardParser.parse(bytes) ?: return null
        val id = UUID.randomUUID().toString()
        val d = dir(context, id).apply { mkdirs() }
        val card = parsed.copy(id = id, createdAt = System.currentTimeMillis())
        if (avatar != null) File(d, "avatar.png").writeBytes(avatar)
        File(d, "card.json").writeText(toJson(card))
        return card
    }

    /** 覆盖写回卡片（头像文件不动），用于导入后的翻译结果保存。 */
    fun save(context: Context, card: CharacterCard) {
        val d = dir(context, card.id).apply { mkdirs() }
        File(d, "card.json").writeText(toJson(card))
    }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
    }

    fun toJson(c: CharacterCard): String = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("description", c.description)
        put("personality", c.personality)
        put("scenario", c.scenario)
        put("first_mes", c.firstMes)
        put("mes_example", c.mesExample)
        put("system_prompt", c.systemPrompt)
        put("post_history_instructions", c.postHistoryInstructions)
        put("alternate_greetings", JsonArray(c.alternateGreetings.map { JsonPrimitive(it) }))
        put("tags", JsonArray(c.tags.map { JsonPrimitive(it) }))
        put("creator_notes", c.creatorNotes)
        put("avatar_file", "avatar.png")
        put("created_at", c.createdAt)
    }.toString()

    private fun fromJson(o: JsonObject, avatar: File): CharacterCard = CharacterCard(
        id = CardParser.str(o, "id"),
        name = CardParser.str(o, "name"),
        description = CardParser.str(o, "description"),
        personality = CardParser.str(o, "personality"),
        scenario = CardParser.str(o, "scenario"),
        firstMes = CardParser.str(o, "first_mes"),
        mesExample = CardParser.str(o, "mes_example"),
        systemPrompt = CardParser.str(o, "system_prompt"),
        postHistoryInstructions = CardParser.str(o, "post_history_instructions"),
        alternateGreetings = CardParser.strList(o, "alternate_greetings"),
        tags = CardParser.strList(o, "tags"),
        creatorNotes = CardParser.str(o, "creator_notes"),
        avatarFile = avatar.absolutePath,
        createdAt = (o["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
    )
}
