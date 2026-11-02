package com.taverndream.sd

/**
 * 生图提示词整形。
 *
 * SD1.5 的 CLIP 硬上限是 77 token，超出的部分会被直接丢掉——这就是「提示词一复杂就崩」的根因。
 * 这里做三件事：去掉换行和重复标签、按重要性截到安全长度、补质量词。
 */
object SdPrompts {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /** 是否含中文（中文必须先翻译成 tag，CLIP 不认中文）。 */
    fun hasChinese(text: String): Boolean = cjk.containsMatchIn(text)

    /** 粗略估算 token 数：英文约 4 个字符 1 个 token。 */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    /** 把一段描述切成干净的 tag 列表（去重、去空、去换行）。 */
    private fun tags(text: String): List<String> {
        val seen = HashSet<String>()
        return text
            .replace("\n", ",")
            .replace("，", ",")
            .split(',', '、', ';', '；')
            .map { it.trim().trim('.', '。', '!', '！', ' ', '\t') }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it.lowercase()) }
    }

    /**
     * 截到 [maxTokens] 以内并补质量词。
     * 前面是用户/模型给的重点描述，所以按顺序保留，超出部分丢弃。
     */
    fun optimize(text: String, maxTokens: Int = 70, quality: Boolean = true): String {
        val list = tags(text).toMutableList()
        if (quality) list.add("masterpiece, best quality, highly detailed")
        val out = StringBuilder()
        for (t in list) {
            val candidate = if (out.isEmpty()) t else "$out, $t"
            if (estimateTokens(candidate) > maxTokens) break
            out.setLength(0)
            out.append(candidate)
        }
        return if (out.isEmpty()) "masterpiece, best quality" else out.toString()
    }

    /** 清理模型返回的一行 tag：去掉代码块、引号、"tags:" 前缀、多余标点。 */
    fun cleanTagLine(raw: String): String =
        raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            .removePrefix("```").removeSuffix("```")
            .replace(Regex("(?i)^(tags|prompt)\\s*[:：]\\s*"), "")
            .trim()
            .trim('"', '\'', '`', '.', '。', ' ')

    /**
     * 去掉角色名：SD 会把名字当题材（角色叫 Cricket，结果画成板球运动场）。
     * 只删独立单词，不碰正常词里的子串。
     */
    fun stripName(text: String, name: String?): String {
        if (name.isNullOrBlank() || name.length < 2) return text
        val escaped = Regex.escape(name)
        return text.replace(Regex("(?i)\\b$escaped\\b"), "")
            .replace(Regex("\\s*,\\s*,\\s*"), ", ")
            .replace(Regex("^\\s*,\\s*"), "")
            .replace(Regex("\\s*,\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 按 token 预算取前面的标签，超出的丢掉。
     * @param seen 跨组去重用（外貌、画风、场景三组之间不重复）
     */
    private fun takeTags(text: String, maxTokens: Int, seen: MutableSet<String>): List<String> {
        val out = mutableListOf<String>()
        for (t in tags(text)) {
            if (!seen.add(t.lowercase())) continue
            val candidate = if (out.isEmpty()) t else out.joinToString(", ") + ", " + t
            if (estimateTokens(candidate) > maxTokens) {
                seen.remove(t.lowercase())
                continue
            }
            out.add(t)
        }
        return out
    }

    /**
     * 拼最终提示词，按 token 预算分组分配：
     * 外貌锚定（≤30，截断时优先保住脸）→ 画风（≤12）→ 场景（剩余）→ 质量词。
     */
    fun compose(anchors: String, style: String, scene: String, maxTokens: Int = 70): String {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        // 外观锚定只留稳定特征（脸、发色），服装留给场景
        out += takeTags(anchors, minOf(18, maxTokens / 3), seen)
        out += takeTags(style, 8, seen)
        val used = estimateTokens(out.joinToString(", "))
        out += takeTags(scene, (maxTokens - used - 6).coerceAtLeast(12), seen)
        val withQuality = out + "masterpiece, best quality"
        return (if (estimateTokens(withQuality.joinToString(", ")) <= maxTokens) withQuality else out)
            .joinToString(", ")
    }

    /** 给模型用的翻译/压缩系统提示词。 */
    const val REWRITE_SYSTEM_PROMPT =
        "You convert a scene description into Stable Diffusion prompt tags. " +
            "Output ONLY comma-separated English tags covering: subject, appearance, hair, clothing, " +
            "pose, action, setting, lighting, art style. No sentences, no explanations, no quotes. " +
            "Keep it under 50 words and put the most important visual details first."
}