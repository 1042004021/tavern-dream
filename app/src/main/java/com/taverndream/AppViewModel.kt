package com.taverndream

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taverndream.data.CharacterCard
import com.taverndream.data.CharacterStore
import com.taverndream.data.CharacterMeta
import com.taverndream.data.CharacterMetaStore
import com.taverndream.data.CardTranslator
import com.taverndream.data.ChatMessage
import com.taverndream.data.ChatStore
import com.taverndream.data.Settings
import com.taverndream.llm.ChatApi
import com.taverndream.llm.LlmModelManager
import com.taverndream.llm.LocalLlmEngine
import com.taverndream.llm.PromptBuilder
import com.taverndream.sd.SdBackendService
import com.taverndream.sd.SdState
import com.taverndream.sd.SdClient
import com.taverndream.sd.SdImage
import com.taverndream.sd.SdModelManager
import com.taverndream.sd.SdPrompts
import com.taverndream.llm.LlmMessage
import com.taverndream.sd.UpscalerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    private val chatApi = ChatApi()
    private val sdClient = SdClient()

    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
        private set

    var current by mutableStateOf<CharacterCard?>(null)
        private set

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    /** 正在流式接收的文本 */
    var streamingText by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var toast by mutableStateOf<String?>(null)

    /** 角色卡翻译进度（null 表示没在翻译）。 */
    var translatingLabel by mutableStateOf<String?>(null)

    /** 当前角色的辅助信息：外观锚定 tag + 开局设定。 */
    var characterMeta by mutableStateOf<CharacterMeta?>(null)
        private set

    /** 正在生成外观/设定（首次进入角色时）。 */
    var metaLoading by mutableStateOf(false)
        private set

    // 生图
    var sdState by mutableStateOf<SdState>(SdState.Idle)
    var sdModelState by mutableStateOf<SdModelManager.State>(SdModelManager.State.Idle)
    var upscalerState by mutableStateOf<UpscalerManager.State>(UpscalerManager.State.Idle)

    /** 主题，改成 Compose 状态，切换后立刻重组。 */
    var themeMode by mutableStateOf(settings.themeMode)
        private set
    var imageBusy by mutableStateOf(false)
        private set
    var imageProgress by mutableStateOf("")
        private set

    // 本地对话模型
    var llmState by mutableStateOf<LocalLlmEngine.State>(LocalLlmEngine.State.Unloaded)
        private set
    var llmModelState by mutableStateOf<LlmModelManager.State>(LlmModelManager.State.Idle)
        private set

    init {
        refreshCharacters()
        viewModelScope.launch {
            SdBackendService.state.collect { sdState = it }
        }
        viewModelScope.launch {
            SdModelManager.state.collect { sdModelState = it }
        }
        viewModelScope.launch {
            UpscalerManager.state.collect { upscalerState = it }
        }
        viewModelScope.launch {
            LocalLlmEngine.state.collect { llmState = it }
        }
        viewModelScope.launch {
            LlmModelManager.state.collect { llmModelState = it }
        }
    }

    fun refreshCharacters() {
        viewModelScope.launch {
            characters = withContext(Dispatchers.IO) { CharacterStore.list(getApplication<Application>()) }
        }
    }

    fun importCard(uri: Uri) {
        viewModelScope.launch {
            val card = withContext(Dispatchers.IO) {
                CharacterStore.import(getApplication<Application>(), uri)
            }
            if (card == null) {
                toast = "无法识别这个文件（需要 PNG 角色卡 / JSON / CharX）"
            } else {
                refreshCharacters()
                toast = "已导入：${card.name}"
                translateCardIfNeeded(card)
            }
        }
    }

    /**
     * 英文角色卡导入后自动翻成中文（名字和标签不翻）。
     * 没有可用对话引擎、或翻译失败时保留英文原文，不阻断导入。
     */
    private fun translateCardIfNeeded(card: CharacterCard) {
        val fields = CardTranslator.englishFields(card)
        if (fields.isEmpty()) return
        val useLocal = settings.chatBackend == "local"
        if (useLocal && llmState !is LocalLlmEngine.State.Ready) {
            toast = "英文角色卡保留原文（本地模型未加载）"
            return
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val done = LinkedHashMap<String, String>()
            var index = 0
            try {
                translatingLabel = "正在翻译「${card.name}」"
                for ((key, text) in fields) {
                    index++
                    translatingLabel = "正在翻译「${card.name}」 $index/${fields.size}"
                    translateText(text)?.let { done[key] = it }
                }
            } finally {
                translatingLabel = null
            }
            if (done.isEmpty()) {
                toast = "翻译失败，已保留英文原文"
                return@launch
            }
            val updated = CardTranslator.apply(card, done)
            withContext(Dispatchers.IO) { CharacterStore.save(ctx, updated) }
            refreshCharacters()
            if (current?.id == updated.id) current = updated
            toast = "「${updated.name}」已翻译 ${done.size} 个字段"
        }
    }

    /** 翻译一段文本；超长按段落切块，逐块翻译后拼接。失败返回 null。 */
    private suspend fun translateText(raw: String): String? = withContext(Dispatchers.IO) {
        val limit = if (settings.chatBackend == "local") 700 else 1500
        val sb = StringBuilder()
        runCatching {
            for (part in CardTranslator.chunks(raw, limit)) {
                val done = translateChunkSafe(part)
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(done)
            }
        }.getOrNull()
        sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 单块翻译，失败自动二分重试。
     *
     * Qwen3 这类模型会先"思考"再输出，max_tokens 给小了会把预算全耗在思考上、正文返回空，
     * 长文本因此整段被丢掉（实测 1500 字 + 2048 token 必空）。这里对空返回按长度二分重试，
     * 最坏情况也能翻出大部分内容。
     */
    private suspend fun translateChunkSafe(text: String): String {
        runCatching { return translateChunk(text) }
        if (text.length <= 200) throw IllegalStateException("空译文")
        val cut = CardTranslator.splitPoint(text)
        return translateChunkSafe(text.substring(0, cut)) + translateChunkSafe(text.substring(cut))
    }

    /** 翻译单块文本；正文为空视为失败。 */
    private suspend fun translateChunk(text: String): String {
        val msgs = listOf(
            LlmMessage("system", CardTranslator.SYSTEM_PROMPT),
            LlmMessage("user", text),
        )
        val one = StringBuilder()
        if (settings.chatBackend == "local") {
            LocalLlmEngine.generate(msgs, 1200) { d ->
                one.append(d)
                true
            }
        } else {
            chatApi.stream(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
                messages = msgs,
                temperature = 0.2f,
                // 8192 给"思考"留足余量：实测 4096 够 1500 字，翻倍更稳
                maxTokens = 8192,
                disableThinking = settings.disableThinking,
            ) { d -> one.append(d) }
        }
        val cleaned = CardTranslator.clean(one.toString())
        if (cleaned.isBlank()) throw IllegalStateException("空译文")
        return cleaned
    }

    /** 一次性短问答（非流式），本地/在线两条通道都走这里。 */
    private suspend fun askOnce(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float = 0.3f,
    ): String? = withContext(Dispatchers.IO) {
        val msgs = listOf(LlmMessage("system", system), LlmMessage("user", user))
        val sb = StringBuilder()
        runCatching {
            if (settings.chatBackend == "local") {
                LocalLlmEngine.generate(msgs, maxTokens) { d ->
                    sb.append(d)
                    true
                }
            } else {
                chatApi.stream(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    messages = msgs,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    disableThinking = settings.disableThinking,
                ) { d -> sb.append(d) }
            }
        }.getOrNull()
        sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun cardBrief(card: CharacterCard): String = buildString {
        append("角色名：").append(card.name).append('\n')
        if (card.description.isNotBlank()) append("角色描述：").append(card.description.take(2200)).append('\n')
        if (card.personality.isNotBlank()) append("性格：").append(card.personality.take(400)).append('\n')
        if (card.scenario.isNotBlank()) append("场景设定：").append(card.scenario.take(600)).append('\n')
        if (card.firstMes.isNotBlank()) append("开场白：").append(
            PromptBuilder.render(card.firstMes.take(700), card.name, settings.userName)
        ).append('\n')
        append("玩家名字：").append(settings.userName)
    }

    /** 提取角色的固定外观 tag（英文），用于每张图的外观锚定。 */
    private suspend fun buildAppearanceTags(card: CharacterCard): String? =
        askOnce(APPEARANCE_SYSTEM_PROMPT, cardBrief(card), 200, 0.3f)
            ?.let { SdPrompts.cleanTagLine(it) }
            ?.takeIf { it.isNotBlank() }

    /** 生成开局设定：世界观 / 你扮演谁 / 当前场景。 */
    private suspend fun buildSceneIntro(card: CharacterCard): String? =
        askOnce(INTRO_SYSTEM_PROMPT, cardBrief(card), 600, 0.5f)

    private companion object {
        const val APPEARANCE_SYSTEM_PROMPT =
            "You write Stable Diffusion tags describing ONE character's fixed appearance. " +
                "Output ONLY comma-separated English tags, max 30 words, one line, no explanation. " +
                "Never include the character's name, the scene, or the pose. " +
                "Always cover: gender, age, hair color and length, eye color, facial features, " +
                "body type, and the outfit they usually wear. " +
                "Example: 1girl, young woman, short brown hair, grey eyes, freckles, slim build, white dress shirt"

        const val INTRO_SYSTEM_PROMPT =
            "你在为角色扮演 App 生成「开局设定卡」，帮玩家搞清楚自己是谁、现在在哪。" +
                "只输出三行正文，不要标题符号、不要 Markdown、不要解释，全文 120~220 字：\n" +
                "世界观：（世界是什么样、有哪些规则或势力）\n" +
                "你扮演：（{{user}} 在这段剧情里是谁、什么身份、和 {{char}} 什么关系、为什么在这里）\n" +
                "当前场景：（时间、地点、正在发生什么）"

        const val SCENE_SYSTEM_PROMPT =
            "You are an illustrator for a roleplay app. Read the recent dialogue and describe " +
                "the current scene as Stable Diffusion tags. " +
                "Output ONLY one line of comma-separated English tags, max 30 words, no sentences, no explanation. " +
                "Do NOT describe hair, eyes or clothing: the character's fixed appearance is added automatically " +
                "and repeating it wastes the prompt budget. " +
                "Only describe: expression, action, camera shot, setting, lighting, mood. Never write character names."
    }

    fun deleteCharacter(card: CharacterCard) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                CharacterStore.delete(getApplication<Application>(), card.id)
                ChatStore.clear(getApplication<Application>(), card.id)
            }
            if (current?.id == card.id) current = null
            refreshCharacters()
        }
    }

    fun openCharacter(card: CharacterCard) {
        current = card
        characterMeta = null
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val meta = withContext(Dispatchers.IO) { CharacterMetaStore.read(ctx, card.id) }
            characterMeta = meta
            val saved = withContext(Dispatchers.IO) {
                ChatStore.load(ctx, card.id)
            }.toMutableList()
            if (saved.isEmpty()) {
                // 角色卡里的开场白常带 {{char}}/{{user}} 占位符，必须渲染后再入库
                val greet = PromptBuilder.render(card.greeting(), card.name, settings.userName)
                if (greet.isNotBlank()) saved.add(ChatMessage("assistant", greet))
            }
            messages = saved
            if (meta.isEmpty) ensureCharacterMeta(card)
        }
    }

    /** 首次进入角色时生成外观 tag 与开局设定；已缓存就直接复用，不重复花额度。 */
    fun ensureCharacterMeta(card: CharacterCard) {
        if (metaLoading) return
        if (settings.chatBackend == "local" && llmState !is LocalLlmEngine.State.Ready) {
            return
        }
        viewModelScope.launch {
            metaLoading = true
            try {
                val ctx = getApplication<Application>()
                val tags = runCatching { buildAppearanceTags(card) }.getOrNull().orEmpty()
                val intro = runCatching { buildSceneIntro(card) }.getOrNull().orEmpty()
                if (tags.isNotBlank() || intro.isNotBlank()) {
                    val meta = CharacterMeta(
                        tags = tags,
                        // 设定里若带 {{user}}/{{char}} 占位符，存盘前换成真实名字
                        intro = PromptBuilder.render(intro, card.name, settings.userName),
                    )
                    withContext(Dispatchers.IO) { CharacterMetaStore.save(ctx, card.id, meta) }
                    if (current?.id == card.id) characterMeta = meta
                }
            } finally {
                metaLoading = false
            }
        }
    }

    /** 清空缓存后重新生成（菜单里用）。 */
    fun regenerateMeta() {
        val c = current ?: return
        characterMeta = null
        ensureCharacterMeta(c)
    }

    fun closeChat() {
        current = null
        messages = emptyList()
        streamingText = ""
        characterMeta = null
        metaLoading = false
    }

    fun clearChat() {
        val c = current ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ChatStore.clear(getApplication<Application>(), c.id) }
            messages = emptyList()
            openCharacter(c)
        }
    }

    fun send(text: String) {
        val c = current ?: return
        val input = text.trim()
        if (input.isEmpty() || busy) return
        // 生图在后台跑，不再阻塞聊天：出完图会自己插到对话里
        val useLocal = settings.chatBackend == "local"
        if (!useLocal && settings.apiKey.isBlank()) {
            toast = "请先在设置里填写 API Key，或切换成本地模型"
            return
        }

        val list = messages.toMutableList()
        list.add(ChatMessage("user", input))
        messages = list
        persist(c.id, list)

        busy = true
        streamingText = ""
        // 生图模型没下载时，不注入配图约定，也不尝试自动出图
        val sdReady = !settings.autoImage || SdModelManager.models(getApplication<Application>())
            .firstOrNull { it.id == settings.sdModelId }
            ?.let { SdModelManager.isDownloaded(getApplication<Application>(), it) } == true
        viewModelScope.launch {
            val imagineHint = sdReady
            val payload = PromptBuilder.build(
                c, list, settings.userName, settings.contextChars, imagineHint
            )
            var acc = StringBuilder()
            var autoPrompt: String? = null
            try {
                if (useLocal) {
                    val model = LlmModelManager.models.firstOrNull { it.id == settings.localModelId }
                        ?: throw IllegalStateException("没有选择本地模型")
                    withContext(Dispatchers.IO) {
                        val tLoad = System.currentTimeMillis()
                        if (!LocalLlmEngine.ensureLoaded(getApplication(), model, settings.llmThreads)) {
                            val msg = (llmState as? LocalLlmEngine.State.Failed)?.message
                            throw IllegalStateException(msg ?: "本地模型加载失败")
                        }
                        android.util.Log.i(
                            "TD-LLM",
                            "model=${model.id} load=${System.currentTimeMillis() - tLoad}ms (已加载则为 0)"
                        )
                        var lastLog = System.currentTimeMillis()
                        val t0 = System.currentTimeMillis()
                        val code = LocalLlmEngine.generate(payload, settings.maxTokens) { delta ->
                            acc.append(delta)
                            streamingText = displayText(acc.toString())
                            val now = System.currentTimeMillis()
                            if (now - lastLog > 2000) {
                                lastLog = now
                                val sec = (now - t0) / 1000f
                                android.util.Log.i(
                                    "TD-LLM",
                                    "streaming ${acc.length} chars, ${sec}s, ${(acc.length / sec).toInt()} char/s"
                                )
                            }
                            if (isRepeating(acc)) {
                                android.util.Log.w("TD-LLM", "检测到复读，主动中止 at ${acc.length} chars")
                                LocalLlmEngine.stop()
                                false
                            } else {
                                true
                            }
                        }
                        val ms = System.currentTimeMillis() - t0
                        val tps = if (ms > 0) code * 1000f / ms else 0f
                        android.util.Log.i(
                            "TD-LLM",
                            "gen done tokens=$code elapsed=${ms}ms speed=${"%.2f".format(tps)} tok/s chars=${acc.length}"
                        )
                        if (code < 0 && code != -4) throw IllegalStateException("本地推理失败（$code）")
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        chatApi.stream(
                            baseUrl = settings.baseUrl,
                            apiKey = settings.apiKey,
                            model = settings.model,
                            messages = payload,
                            temperature = settings.temperature,
                            maxTokens = settings.maxTokens,
                            disableThinking = settings.disableThinking,
                        ) { delta ->
                            acc.append(delta)
                            streamingText = displayText(acc.toString())
                        }
                    }
                }
                val (finalText, imaginePrompt) = parseImagine(acc.toString().trim())
                if (finalText.isNotEmpty()) {
                    val newList = messages.toMutableList()
                    newList.add(ChatMessage("assistant", finalText))
                    messages = newList
                    persist(c.id, newList)
                }
                // 自动配图：优先用模型给的画面描述，其次看用户是否明确要求
                if (settings.autoImage && sdReady) {
                    autoPrompt = imaginePrompt
                        ?: if (wantsImage(input) || looksLikeSceneChange(input)) {
                            // 模型没给画面描述时，按最近对话总结一个，比直接用角色卡描述贴合场景
                            runCatching { summarizeScene(c) }.getOrNull() ?: buildFallbackPrompt(c)
                        } else null
                }
            } catch (t: Throwable) {
                toast = "请求失败：${t.message}"
            } finally {
                streamingText = ""
                busy = false
            }
            autoPrompt?.let { generateAndAttach(it, settings.sdNegative) }
        }
    }

    // ---------------- 自动配图相关 ----------------

    private val imagineRegex = Regex("<imagine>([\\s\\S]*?)</imagine>", RegexOption.IGNORE_CASE)

    /** 流式展示时把未写完的 <imagine> 标签及其后内容藏起来。 */
    private fun displayText(raw: String): String {
        val idx = raw.indexOf("<imagine", ignoreCase = true)
        return if (idx >= 0) raw.substring(0, idx).trimEnd() else raw
    }

    /** 拆出 <imagine> 里的画面描述，并从正文中移除。 */
    private fun parseImagine(raw: String): Pair<String, String?> {
        val m = imagineRegex.find(raw)
        val prompt = m?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        var clean = raw.replace(imagineRegex, "")
        val idx = clean.indexOf("<imagine", ignoreCase = true)
        if (idx >= 0) clean = clean.substring(0, idx)
        return clean.trim() to prompt
    }

    private val imageKeywords = listOf(
        "画一张", "画张", "画个", "画一个", "画一下", "画幅", "来一张", "生成一张",
        "生成图片", "生图", "配图", "插图", "出图", "给我画",
        "draw", "paint", "illustrate", "picture of", "image of",
        "generate an image", "show me a picture",
    )

    private fun wantsImage(userText: String): Boolean {
        val t = userText.lowercase()
        return imageKeywords.any { t.contains(it) }
    }

    /** 场景切换类动词/地点词：模型没给 <imagine> 时的兜底判断。 */
    private val sceneChangeKeywords = listOf(
        "出门", "出去", "离开", "走进", "进入", "来到", "到达", "推门", "下楼", "上楼",
        "回去", "前往", "赶去", "出发", "上车", "下车", "上船", "登船",
        "夜里", "晚上", "天亮", "清晨", "黄昏", "下雨", "下雪", "起风",
        "外面", "街上", "码头", "酒馆", "森林", "洞穴", "城堡", "房间", "办公室",
    )

    /** 最近几条消息里是否已经有图；有就说明这个场景画过了。 */
    private fun hasRecentImage(count: Int = 4): Boolean =
        messages.takeLast(count).any { !it.imagePath.isNullOrBlank() }

    private fun looksLikeSceneChange(userText: String): Boolean {
        if (hasRecentImage()) return false
        val t = userText
        return sceneChangeKeywords.any { t.contains(it) }
    }

    /** 模型没给画面描述时的兜底：用角色卡外观描述拼英文提示词。 */
    private fun buildFallbackPrompt(card: CharacterCard): String {
        val desc = card.description
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\b(type|species|appearance|personality)\\s*:"), ",")
            .replace(Regex(",\\s*,"), ",")
            .trim()
            .trim(',')
            .take(200)
        val base = desc.ifBlank { "1girl, solo" }
        return "$base, masterpiece, best quality"
    }

    /** 连续多行完全相同就认为模型在复读。 */
    private fun isRepeating(sb: StringBuilder): Boolean {
        if (sb.length < 160) return false
        val tail = sb.substring(maxOf(0, sb.length - 500))
        val lines = tail.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 5) {
            val last = lines.takeLast(5)
            if (last.all { it == last.first() } && last.first().length >= 3) return true
        }
        // 单行内重复：末尾一小段连续出现 4 次以上
        if (tail.length >= 60) {
            val unit = tail.takeLast(20)
            if (unit.isNotBlank() && tail.takeLast(80).windowed(20, 1).count { it == unit } >= 4) return true
        }
        return false
    }

    fun stopStreaming() {
        // API 通道：OkHttp 调用在 IO 线程阻塞，这里只复位状态；
        // 本地通道：可以真正打断生成。
        LocalLlmEngine.stop()
        busy = false
    }

    // ---------------- 本地对话模型 ----------------

    fun downloadLlmModel(modelId: String = settings.localModelId) {
        val model = LlmModelManager.models.firstOrNull { it.id == modelId } ?: return
        android.util.Log.i("TD-LLM", "download requested: ${model.id} mirror=${settings.hfMirror}")
        viewModelScope.launch {
            LlmModelManager.download(getApplication(), model, settings.hfMirror, settings.llmThreads)
        }
    }

    /** 卸载常驻的本地模型，释放约 3GB 内存（生图前建议调用）。 */
    fun unloadLocalLlm() {
        viewModelScope.launch(Dispatchers.IO) { LocalLlmEngine.release() }
    }

    fun persist(charId: String, list: List<ChatMessage>) {
        viewModelScope.launch(Dispatchers.IO) {
            ChatStore.save(getApplication<Application>(), charId, list)
        }
    }

    // ---------------- 生图 ----------------

    fun startSdBackend(modelId: String = settings.sdModelId) {
        val model = SdModelManager.models(getApplication<Application>())
            .firstOrNull { it.id == modelId } ?: return
        if (!SdModelManager.isDownloaded(getApplication<Application>(), model)) {
            toast = "模型还没下载，请到「设置 → 本地生图」下载"
            return
        }
        SdBackendService.start(getApplication<Application>(), model.id)
    }

    fun stopSdBackend() {
        SdBackendService.stop(getApplication<Application>())
    }

    fun downloadSdModel(modelId: String = settings.sdModelId) {
        val model = SdModelManager.models(getApplication<Application>())
            .firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch {
            SdModelManager.download(getApplication<Application>(), model, settings.hfMirror)
        }
    }

    fun applyTheme(mode: String) {
        settings.themeMode = mode
        themeMode = mode
    }

    fun downloadUpscaler() {
        viewModelScope.launch {
            UpscalerManager.download(getApplication<Application>(), settings.hfMirror)
        }
    }

    /** 手动生图入口（设置菜单里用）。 */
    fun generateImage(prompt: String, negative: String) {
        viewModelScope.launch { generateAndAttach(prompt, negative) }
    }

    /** 输入框旁的生图按钮：总结当前环境、剧情、人物，直接画一张。 */
    fun generateSceneImage() {
        val c = current ?: return
        if (imageBusy || busy) {
            toast = "稍等一下再画"
            return
        }
        viewModelScope.launch {
            imageBusy = true
            imageProgress = "总结场景…"
            try {
                val prompt = runCatching { summarizeScene(c) }.getOrNull()
                    ?: buildFallbackPrompt(c)
                generateAndAttach(prompt, settings.sdNegative, alreadyBusy = true)
            } finally {
                imageBusy = false
            }
        }
    }

    /** 根据最近几轮对话总结出画面 tag；失败返回 null，调用方走兜底提示词。 */
    private suspend fun summarizeScene(card: CharacterCard): String? {
        val recent = messages.takeLast(6).mapNotNull { m ->
            m.text.takeIf { it.isNotBlank() }?.let {
                if (m.role == "user") "${settings.userName}：$it" else "${card.name}：$it"
            }
        }
        if (recent.isEmpty()) return null
        val user = buildString {
            val anchors = characterMeta?.tags.orEmpty()
            if (anchors.isNotBlank()) append("固定外观：").append(anchors).append('\n')
            append("最近对话：\n").append(recent.joinToString("\n").take(2000))
        }
        return askOnce(SCENE_SYSTEM_PROMPT, user, 200, 0.4f)
            ?.let { SdPrompts.cleanTagLine(it) }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 生成图片并插入会话：自动配图与手动生图共用。
     * 注意 healthy() 是阻塞调用，必须放在 IO 线程，否则主线程会抛 NetworkOnMainThreadException。
     */
    private suspend fun generateAndAttach(prompt: String, negative: String, alreadyBusy: Boolean = false) {
        if (imageBusy && !alreadyBusy) return
        if (busy) {
            toast = "正在生成回复，稍等一下"
            return
        }
        val c = current ?: return
        imageBusy = true
        imageProgress = "准备中…"
        try {
            // 中文描述 CLIP 不认，且 SD1.5 只吃 77 token，先让对话模型整理成英文 tag
            imageProgress = "整理提示词…"
            val finalPrompt = runCatching { prepareImagePrompt(prompt) }.getOrDefault(prompt)
            android.util.Log.i("TD-SD", "prompt=$finalPrompt")
            // 生图与本地对话都很吃内存，先卸载本地模型
            withContext(Dispatchers.IO) { LocalLlmEngine.release() }
            val model = SdModelManager.models(getApplication<Application>())
                .firstOrNull { it.id == settings.sdModelId }
            if (model == null || !SdModelManager.isDownloaded(getApplication<Application>(), model)) {
                throw IllegalStateException("生图模型未下载，请到设置页下载")
            }
            // SDXL 预加载就吃 5GB+：内存不够时直接拒绝，避免把整机拖死（已踩过两次）
            if (model.isSdxl) {
                val availMb = withContext(Dispatchers.IO) { availableMemoryMb() }
                if (availMb in 1 until 3000) {
                    throw IllegalStateException("可用内存仅 ${availMb}MB，SDXL 至少需要 3GB，请先清理后台再试")
                }
            }
            if (!withContext(Dispatchers.IO) { sdClient.healthy() }) {
                SdBackendService.start(getApplication(), model.id)
                // SDXL 模型 4.2GB，首次加载（MNN CLIP + 2.6GB QNN UNET）比 SD1.5 慢得多
                val waitSec = if (model.isSdxl) 240 else 90
                var waited = 0
                while (waited < waitSec) {
                    delay(1000)
                    waited++
                    if (withContext(Dispatchers.IO) { sdClient.healthy() }) break
                    val st = sdState
                    if (st is SdState.Error) throw IllegalStateException(st.message)
                }
                if (!withContext(Dispatchers.IO) { sdClient.healthy() }) {
                    throw IllegalStateException("生图后端启动超时")
                }
            }

            val size = model.nativeSize
            android.util.Log.i("TD-SD", "sd model=${model.id} size=$size scheduler=${model.scheduler}")
            val img = withContext(Dispatchers.IO) {
                sdClient.generate(
                    prompt = finalPrompt,
                    negative = negative,
                    steps = settings.sdSteps,
                    cfg = settings.sdCfg,
                    width = size,
                    height = size,
                    seed = imageSeed(),
                    scheduler = model.scheduler,
                ) { step, total, _ ->
                    imageProgress = "生成中 $step/$total"
                }
            }
            imageProgress = if (settings.sdUpscale && img.width <= 640 &&
                UpscalerManager.isDownloaded(getApplication())
            ) "超分中…" else "保存中…"
            val file = withContext(Dispatchers.IO) { renderFinal(img) }
            val list = current?.let { messages.toMutableList() } ?: return
            list.add(ChatMessage("assistant", "", file.absolutePath))
            messages = list
            persist(c.id, list)
            imageProgress = ""
        } catch (t: Throwable) {
            toast = "生图失败：${t.message}"
            imageProgress = ""
        } finally {
            imageBusy = false
        }
    }

    /**
     * 按设置裁切目标比例，可选 4x 超分，最后编成 JPEG 落盘。
     *
     * 模型本身固定 512×512，比例靠居中裁切实现；超分由后端 /upscale 完成，
     * 失败时退回裁切后的原尺寸，不影响出图。
     */
    private fun renderFinal(img: SdImage): File {
        val (tw, th) = targetSize(img.width, img.height, settings.sdRatio)
        val cropped = cropRgb(img, tw, th)
        val base = rgbToBitmap(cropped)
        // SD2.1 这类 768 模型已经够大，再 4x 会到 3072×2304，没必要也吃内存
        val useUp = settings.sdUpscale && cropped.width <= 640 &&
            UpscalerManager.isDownloaded(getApplication())
        val out = if (useUp) {
            runCatching {
                sdClient.upscale(
                    rgb = cropped.bytes,
                    width = cropped.width,
                    height = cropped.height,
                    upscalerPath = UpscalerManager.file(getApplication()).absolutePath,
                )
            }.getOrElse {
                android.util.Log.w("TD-SD", "超分失败，退回原尺寸：${it.message}")
                base
            }
        } else {
            base
        }
        if (out !== base) base.recycle()
        val dir = File(getApplication<Application>().filesDir, "generated").apply { mkdirs() }
        val f = File(dir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        return f
    }

    /**
     * 生图提示词整形：中文先用对话模型翻成英文 tag（CLIP 不认中文），
     * 再统一压到 77 token 的安全范围内。
     */
    private suspend fun prepareImagePrompt(raw: String): String {
        var text = raw
        val localReady = llmState is LocalLlmEngine.State.Ready
        if (SdPrompts.hasChinese(text) && (settings.chatBackend != "local" || localReady)) {
            rewriteToTags(text)?.let { text = it }
        }
        // 角色名会被 SD 当成题材（角色叫 Cricket → 画出板球运动场），一律剔除
        text = SdPrompts.stripName(text, current?.name)
        val anchors = if (settings.lockAppearance) characterMeta?.tags.orEmpty() else ""
        return SdPrompts.compose(anchors, settings.styleTags, text)
    }

    /** 读取 /proc/meminfo 的 MemAvailable（MB）；读不到返回 -1。 */
    private fun availableMemoryMb(): Long = runCatching {
        java.io.File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?.div(1024) ?: -1L
    }.getOrDefault(-1L)

    /** 固定种子时同一角色每张构图接近（脸更稳）；否则每张随机。 */
    private fun imageSeed(): Long {
        val id = current?.id
        return if (settings.fixedSeed && id != null) {
            id.hashCode().toLong() and 0x7FFFFFFFL
        } else {
            System.currentTimeMillis() % 1_000_000
        }
    }

    /** 让对话模型把描述改写成英文 SD tag；失败就返回 null，走原提示词。 */
    private suspend fun rewriteToTags(text: String): String? = runCatching {
        val msgs = listOf(
            LlmMessage("system", SdPrompts.REWRITE_SYSTEM_PROMPT),
            LlmMessage("user", text),
        )
        val sb = StringBuilder()
        if (settings.chatBackend == "local") {
            withContext(Dispatchers.IO) {
                LocalLlmEngine.generate(msgs, 120) { d ->
                    sb.append(d)
                    true
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                chatApi.stream(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    messages = msgs,
                    temperature = 0.3f,
                    maxTokens = 120,
                    disableThinking = settings.disableThinking,
                ) { d -> sb.append(d) }
            }
        }
        sb.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 目标宽高：4:3 保宽裁高，3:4 保高裁宽，1:1 取中间正方形。 */
    private fun targetSize(w: Int, h: Int, ratio: String): Pair<Int, Int> = when (ratio) {
        "4:3" -> w to (w * 3 / 4)
        "3:4" -> (h * 3 / 4) to h
        else -> minOf(w, h) to minOf(w, h)
    }

    /** 居中裁切原始 RGB 像素（后端给的是裸像素，不是图片文件）。 */
    private fun cropRgb(img: SdImage, tw: Int, th: Int): SdImage {
        if (tw == img.width && th == img.height) return img
        val ch = img.channels.coerceAtLeast(3)
        val ox = (img.width - tw) / 2
        val oy = (img.height - th) / 2
        val out = ByteArray(tw * th * ch)
        val src = img.bytes
        var di = 0
        for (y in 0 until th) {
            val so = ((y + oy) * img.width + ox) * ch
            System.arraycopy(src, so, out, di, tw * ch)
            di += tw * ch
        }
        return SdImage(tw, th, ch, out)
    }

    private fun rgbToBitmap(img: SdImage): Bitmap {
        val w = img.width
        val h = img.height
        val ch = img.channels.coerceAtLeast(3)
        val src = img.bytes
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * ch
            val a = if (ch >= 4) src[o + 3].toInt() and 0xFF else 0xFF
            val r = src[o].toInt() and 0xFF
            val g = src[o + 1].toInt() and 0xFF
            val b = src[o + 2].toInt() and 0xFF
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    fun importImageToChat(uri: Uri) {
        val c = current ?: return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return@launch
            val dir = File(getApplication<Application>().filesDir, "generated").apply { mkdirs() }
            val f = File(dir, "${UUID.randomUUID()}.png")
            withContext(Dispatchers.IO) { f.writeBytes(bytes) }
            val list = messages.toMutableList()
            list.add(ChatMessage("user", "", f.absolutePath))
            messages = list
            persist(c.id, list)
        }
    }
}
