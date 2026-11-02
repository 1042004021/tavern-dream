package com.taverndream.data

import android.content.Context
import android.content.SharedPreferences

/** 全局设置（SharedPreferences）。 */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", "https://api.openai.com/v1") ?: ""
        set(v) = sp.edit().putString("baseUrl", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini") ?: ""
        set(v) = sp.edit().putString("model", v).apply()

    var userName: String
        get() = sp.getString("userName", "我") ?: "我"
        set(v) = sp.edit().putString("userName", v).apply()

    var temperature: Float
        get() = sp.getFloat("temperature", 0.9f)
        set(v) = sp.edit().putFloat("temperature", v).apply()

    var maxTokens: Int
        get() = sp.getInt("maxTokens", 800)
        set(v) = sp.edit().putInt("maxTokens", v).apply()

    var contextChars: Int
        get() = sp.getInt("contextChars", 6000)
        set(v) = sp.edit().putInt("contextChars", v).apply()

    // ---- 对话引擎 ----
    /** "api" = 外部 OpenAI 兼容接口；"local" = 内置 MNN 本地模型 */
    var chatBackend: String
        get() = sp.getString("chatBackend", "api") ?: "api"
        set(v) = sp.edit().putString("chatBackend", v).apply()

    var localModelId: String
        get() = sp.getString("localModelId", "qwen3-1p7b") ?: "qwen3-1p7b"
        set(v) = sp.edit().putString("localModelId", v).apply()

    var llmThreads: Int
        get() = sp.getInt("llmThreads", 6)
        set(v) = sp.edit().putInt("llmThreads", v).apply()

    // ---- 生图 ----
    var sdModelId: String
        get() = sp.getString("sdModelId", "anythingv5") ?: "anythingv5"
        set(v) = sp.edit().putString("sdModelId", v).apply()

    var hfMirror: String
        get() = sp.getString("hfMirror", "https://aifasthub.com") ?: "https://aifasthub.com"
        set(v) = sp.edit().putString("hfMirror", v).apply()

    var sdSteps: Int
        get() = sp.getInt("sdSteps", 20)
        set(v) = sp.edit().putInt("sdSteps", v).apply()

    var sdCfg: Float
        get() = sp.getFloat("sdCfg", 7.5f)
        set(v) = sp.edit().putFloat("sdCfg", v).apply()

    var sdSize: Int
        get() = sp.getInt("sdSize", 512)
        set(v) = sp.edit().putInt("sdSize", v).apply()

    /** 对话中自动配图的总开关。 */
    var autoImage: Boolean
        get() = sp.getBoolean("autoImage", true)
        set(v) = sp.edit().putBoolean("autoImage", v).apply()

    /** 自动配图使用的负面提示词。 */
    var sdNegative: String
        get() = sp.getString("sdNegative", DEFAULT_NEGATIVE) ?: DEFAULT_NEGATIVE
        set(v) = sp.edit().putString("sdNegative", v).apply()

    /** 主题："system" | "light" | "dark"。 */
    var themeMode: String
        get() = sp.getString("themeMode", "system") ?: "system"
        set(v) = sp.edit().putString("themeMode", v).apply()

    /** 出图比例：1:1 / 4:3 / 3:4（模型固定 512，宽高靠居中裁切实现）。 */
    var sdRatio: String
        get() = sp.getString("sdRatio", "4:3") ?: "4:3"
        set(v) = sp.edit().putString("sdRatio", v).apply()

    /** 出图后是否走 4x 超分（realesrgan）。 */
    var sdUpscale: Boolean
        get() = sp.getBoolean("sdUpscale", true)
        set(v) = sp.edit().putBoolean("sdUpscale", v).apply()

    /** 关闭模型"思考模式"（Qwen3 等会先输出 reasoning 再回答），实测快 3~4 倍。 */
    var disableThinking: Boolean
        get() = sp.getBoolean("disableThinking", true)
        set(v) = sp.edit().putBoolean("disableThinking", v).apply()

    /** 每张图强制带上角色的外观 tag，保证同一角色长相接近。 */
    var lockAppearance: Boolean
        get() = sp.getBoolean("lockAppearance", true)
        set(v) = sp.edit().putBoolean("lockAppearance", v).apply()

    /** 同一角色固定随机种子：画面更统一，但构图变化变少。 */
    var fixedSeed: Boolean
        get() = sp.getBoolean("fixedSeed", false)
        set(v) = sp.edit().putBoolean("fixedSeed", v).apply()

    /**
     * 成人向内容：放开配图改写对露骨描写的限制。
     * 自用离线场景，默认关闭；开启后场景/动作会按 danbooru tag 直白描述。
     */
    var nsfwMode: Boolean
        get() = sp.getBoolean("nsfwMode", false)
        set(v) = sp.edit().putBoolean("nsfwMode", v).apply()

    /** 固定的画风 tag（英文），用来保证每次出图画风一致。 */
    var styleTags: String
        get() = sp.getString("styleTags", "anime style, clean lineart, soft lighting") ?: ""
        set(v) = sp.edit().putString("styleTags", v).apply()

    companion object {
        const val DEFAULT_NEGATIVE = "lowres, bad anatomy, bad hands, worst quality, watermark, text,"
    }
}
