package com.taverndream.llm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地模型会话的持有者。模型常驻内存，切换角色/清空会话只重置上下文，不重新加载。
 */
object LocalLlmEngine {

    sealed class State {
        data object Unloaded : State()
        data object Loading : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state

    private var handle: Long = 0L
    private var loadedModelId: String? = null

    /** 加载模型（阻塞，需在后台线程调用）。已加载则直接返回 true。 */
    @Synchronized
    fun ensureLoaded(context: Context, model: LlmModel, threads: Int): Boolean {
        if (handle != 0L && loadedModelId == model.id) return true
        release()
        if (!LlmModelManager.isDownloaded(context, model)) {
            _state.value = State.Failed("模型还没下载")
            return false
        }
        _state.value = State.Loading
        val cfg = LlmModelManager.runtimeConfigPath(context, model.id)
        val h = MnnLlm.create(cfg.absolutePath)
        if (h == 0L) {
            _state.value = State.Failed("模型加载失败（内存不足或文件不完整）")
            return false
        }
        handle = h
        loadedModelId = model.id
        _state.value = State.Ready
        return true
    }

    /** 阻塞式流式生成，需在后台线程调用。 */
    fun generate(messages: List<LlmMessage>, maxTokens: Int, onToken: (String) -> Boolean): Int {
        val h = handle
        if (h == 0L) return -1
        return MnnLlm.generate(h, messages, maxTokens, onToken)
    }

    fun stop() {
        val h = handle
        if (h != 0L) MnnLlm.stop(h)
    }

    fun resetContext() {
        val h = handle
        if (h != 0L) MnnLlm.reset(h)
    }

    @Synchronized
    fun release() {
        val h = handle
        handle = 0L
        loadedModelId = null
        if (h != 0L) {
            runCatching { MnnLlm.release(h) }
            _state.value = State.Unloaded
        }
    }
}