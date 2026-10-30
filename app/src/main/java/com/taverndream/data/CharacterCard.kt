package com.taverndream.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.zip.ZipInputStream

/** SillyTavern V1/V2/V3 角色卡（只保留本 App 需要的字段）。 */
data class CharacterCard(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creatorNotes: String = "",
    val avatarFile: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun greeting(): String = firstMes.ifBlank { alternateGreetings.firstOrNull().orEmpty() }
}

object CardParser {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 解析 PNG / JSON / CharX(zip)。返回卡片与头像原始字节。 */
    fun parse(bytes: ByteArray): Pair<CharacterCard, ByteArray?>? {
        return runCatching {
            when {
                isPng(bytes) -> {
                    val text = readPngCard(bytes) ?: return null
                    toCard(json.parseToJsonElement(text).jsonObject) to bytes
                }

                isZip(bytes) -> parseCharx(bytes)
                else -> {
                    val text = bytes.toString(Charsets.UTF_8).trim()
                    toCard(json.parseToJsonElement(text).jsonObject) to null
                }
            }
        }.getOrNull()
    }

    private fun parseCharx(bytes: ByteArray): Pair<CharacterCard, ByteArray?>? {
        var cardText: String? = null
        var avatar: ByteArray? = null
        ZipInputStream(bytes.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    when {
                        name.endsWith("card.json", ignoreCase = true) ->
                            cardText = zis.readBytes().toString(Charsets.UTF_8)
                        avatar == null && (name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                                name.endsWith(".webp", true)) -> avatar = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val text = cardText ?: return null
        return toCard(json.parseToJsonElement(text).jsonObject) to avatar
    }

    fun toCard(root: JsonObject): CharacterCard {
        // V2/V3 的内容在 data 下，V1 直接在根上
        val d = (root["data"] as? JsonObject) ?: root
        return CharacterCard(
            id = "",
            name = str(d, "name").ifBlank { "未命名角色" },
            description = str(d, "description"),
            personality = str(d, "personality"),
            scenario = str(d, "scenario"),
            firstMes = str(d, "first_mes"),
            mesExample = str(d, "mes_example"),
            systemPrompt = str(d, "system_prompt"),
            postHistoryInstructions = str(d, "post_history_instructions"),
            alternateGreetings = strList(d, "alternate_greetings"),
            tags = strList(d, "tags"),
            creatorNotes = str(d, "creator_notes"),
        )
    }

    fun str(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    fun strList(o: JsonObject, key: String): List<String> =
        (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    private fun isPng(b: ByteArray) =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
                b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun isZip(b: ByteArray) =
        b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /**
     * 读取 PNG 的 tEXt 块。SillyTavern 把角色卡 JSON 以 base64 存在关键字 chara / ccv3 中。
     */
    fun readPngCard(bytes: ByteArray): String? {
        var pos = 8
        var found: String? = null
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                    ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            val dataEnd = dataStart + len
            if (len < 0 || dataEnd + 4 > bytes.size) break
            if (type == "tEXt") {
                val text = String(bytes, dataStart, len, Charsets.ISO_8859_1)
                val sep = text.indexOf('\u0000')
                if (sep > 0) {
                    val keyword = text.substring(0, sep)
                    if (keyword == "ccv3" || keyword == "chara") {
                        val b64 = text.substring(sep + 1).trim()
                        val decoded = runCatching {
                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                .toString(Charsets.UTF_8)
                        }.getOrNull()
                        if (keyword == "ccv3" && decoded != null) return decoded
                        if (decoded != null) found = decoded
                    }
                }
            }
            pos = dataEnd + 4
        }
        return found
    }
}
