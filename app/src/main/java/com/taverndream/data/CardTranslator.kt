package com.taverndream.data

/**
 * 角色卡翻译：把英文卡片的文字字段翻成简体中文。
 *
 * 名字和标签不翻（`Cricket` 翻成「蟋蟀」很怪），其余字段逐个翻；
 * 某个字段翻失败就保留原文，不影响导入。
 */
object CardTranslator {

    /** 中日韩字符（含日文假名、韩文），出现即认为无需翻译。 */
    private val cjk = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff\\uac00-\\ud7af]")

    private val fence = Regex("^```[a-zA-Z]*\\s*|\\s*```$")

    /** 判定一段文本是不是英文（无中日韩字符且字母够多）。 */
    fun isEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        if (cjk.containsMatchIn(text)) return false
        return text.count { it.isLetter() } >= 8
    }

    /**
     * 需要翻译的字段，key 是内部标识。
     * 只返回确实含英文的字段，中英混排的卡也能逐字段处理。
     */
    fun englishFields(card: CharacterCard): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(key: String, value: String) {
            if (isEnglish(value)) out[key] = value
        }
        put("description", card.description)
        put("personality", card.personality)
        put("scenario", card.scenario)
        put("first_mes", card.firstMes)
        put("mes_example", card.mesExample)
        card.alternateGreetings.forEachIndexed { i, g -> put("alt_$i", g) }
        put("system_prompt", card.systemPrompt)
        put("post_history_instructions", card.postHistoryInstructions)
        put("creator_notes", card.creatorNotes)
        return out
    }

    /** 把翻译结果写回卡片；没翻的字段保持原样。 */
    fun apply(card: CharacterCard, translated: Map<String, String>): CharacterCard {
        val alts = card.alternateGreetings.mapIndexed { i, g -> translated["alt_$i"] ?: g }
        return card.copy(
            description = translated["description"] ?: card.description,
            personality = translated["personality"] ?: card.personality,
            scenario = translated["scenario"] ?: card.scenario,
            firstMes = translated["first_mes"] ?: card.firstMes,
            mesExample = translated["mes_example"] ?: card.mesExample,
            alternateGreetings = alts,
            systemPrompt = translated["system_prompt"] ?: card.systemPrompt,
            postHistoryInstructions = translated["post_history_instructions"]
                ?: card.postHistoryInstructions,
            creatorNotes = translated["creator_notes"] ?: card.creatorNotes,
        )
    }

    /** 长文本按段落切块，避免单次请求超长（对话模型上下文有限）。 */
    fun chunks(text: String, maxChars: Int = 1500): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.split("\n")) {
            if (sb.isNotEmpty() && sb.length + line.length + 1 > maxChars) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (line.length > maxChars) {
                // 单行本身就超长，硬切
                var i = 0
                while (i < line.length) {
                    val end = minOf(i + maxChars, line.length)
                    out += line.substring(i, end)
                    i = end
                }
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.filter { it.isNotBlank() }
    }

    /** 二分重试时的切分点：优先在段落、句末断开，别把一句话劈两半。 */
    fun splitPoint(text: String): Int {
        val mid = text.length / 2
        val window = text.length / 4
        for (i in mid downTo maxOf(1, mid - window)) {
            if (text[i - 1] == '\n' || text[i - 1] == '.' || text[i - 1] == '。' ||
                text[i - 1] == '!' || text[i - 1] == '?'
            ) return i
        }
        return mid
    }

    /** 去掉模型爱加的前后缀（代码块、引号、「译文：」等）。 */
    fun clean(raw: String): String {
        var s = raw.trim()
        s = fence.replace(s, "")
        s = s.trim()
        for (prefix in listOf("译文：", "译文:", "翻译：", "翻译:", "中文：", "中文:")) {
            if (s.startsWith(prefix)) s = s.removePrefix(prefix).trim()
        }
        if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("“") && s.endsWith("”")))
        ) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    /** 翻译系统提示词：要求保留格式与占位符，只输出译文。 */
    const val SYSTEM_PROMPT =
        "你是角色卡翻译助手。把用户给的英文文本翻译成简体中文：\n" +
            "1) 只输出译文，不要解释、不要前缀、不要加引号；\n" +
            "2) 保留原始格式：星号动作描写、引号、换行、Markdown、缩进都照原样；\n" +
            "3) 原样保留 {{char}}、{{user}} 等占位符和 <START> 之类的标记；\n" +
            "4) 人名、地名保留英文原文，不要音译；\n" +
            "5) 不要增删内容、不要总结、不要改写语气。"
}