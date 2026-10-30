package com.taverndream.llm

import com.taverndream.data.CharacterCard
import com.taverndream.data.ChatMessage

data class LlmMessage(val role: String, val content: String)

/**
 * 提示词组装：角色卡字段 -> system，历史消息 -> messages。
 * 参考 SillyTavern 的默认 Chat Completion 结构，做了必要裁剪。
 */
object PromptBuilder {

    fun render(template: String, char: String, user: String): String =
        template.replace("{{char}}", char).replace("{{user}}", user)

    fun systemPrompt(card: CharacterCard, userName: String, imagineHint: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("请始终以「${card.name}」的身份与用户对话，不要跳出角色，不要代替用户说话。\n")
        if (card.description.isNotBlank()) {
            sb.append("\n【角色设定】\n").append(card.description.trim()).append('\n')
        }
        if (card.personality.isNotBlank()) {
            sb.append("\n【性格】\n").append(card.personality.trim()).append('\n')
        }
        if (card.scenario.isNotBlank()) {
            sb.append("\n【场景】\n").append(card.scenario.trim()).append('\n')
        }
        if (card.mesExample.isNotBlank()) {
            sb.append("\n【对话示例】\n").append(card.mesExample.trim()).append('\n')
        }
        if (card.systemPrompt.isNotBlank()) {
            sb.append("\n【补充要求】\n").append(card.systemPrompt.trim()).append('\n')
        }
        sb.append("\n当前用户的名字是「").append(userName).append("」。")
        if (imagineHint) {
            sb.append("\n\n【配图约定】\n")
            sb.append("你自带一个插画师。只在**画面确实变化**时才需要插图，判断标准：\n")
            sb.append("换地点、换时间（昼夜/天气变）、有新角色出场、发生大的动作或情绪转折。\n")
            sb.append("同一场景里继续闲聊**不要**重复出图。用户明确要求画图时必须出图。\n")
            sb.append("需要插图时，在回复的最后单独一行输出：\n")
            sb.append("<imagine>英文画面描述，逗号分隔</imagine>\n")
            sb.append("画面描述的写法：先写表情、动作、镜头，再写环境（地点、光线、氛围）。\n")
            sb.append("**不要重复外貌**（发色、瞳色、服装），那是固定的、会自动补上，重复写只会挤掉场景。\n")
            sb.append("**不要写角色名字**，否则会被画成别的东西（比如名字叫 Cricket 就被画成板球）。\n")
            sb.append("这一行不会展示给用户，只用来生成插画。不需要配图时不要输出这一行。")
        }
        return render(sb.toString(), card.name, userName)
    }

    /** 按字符预算裁剪历史，保留最近的消息。 */
    fun build(
        card: CharacterCard,
        history: List<ChatMessage>,
        userName: String,
        budgetChars: Int,
        imagineHint: Boolean = false,
    ): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        out.add(LlmMessage("system", systemPrompt(card, userName, imagineHint)))
        var used = out[0].content.length
        val tail = mutableListOf<LlmMessage>()
        for (m in history.asReversed()) {
            val text = when {
                m.imagePath != null -> if (m.role == "user") "[${userName}发送了一张图片]" else "[${card.name}发了一张图片]"
                else -> m.text
            }
            if (text.isBlank()) continue
            if (used + text.length > budgetChars && tail.isNotEmpty()) break
            used += text.length
            tail.add(LlmMessage(if (m.role == "user") "user" else "assistant", text))
        }
        out.addAll(tail.asReversed())
        return out
    }
}
