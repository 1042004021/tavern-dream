package com.taverndream.llm

/**
 * MNN-LLM 原生入口（libmnnllm.so → libMNN.so）。
 * 所有方法都是阻塞调用，必须在后台线程执行。
 */
object MnnLlm {

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("MNN")
        System.loadLibrary("mnnllm")
    }

    /** 返回会话句柄，0 表示加载失败。 */
    fun create(configPath: String): Long = nativeCreate(configPath)

    fun release(handle: Long) = nativeRelease(handle)

    /** 请求中止当前生成（可从其他线程调用）。 */
    fun stop(handle: Long) = nativeStop(handle)

    /** 清空上下文缓存（换角色 / 清空会话时用）。 */
    fun reset(handle: Long) = nativeReset(handle)

    /**
     * 流式生成。
     * @return 生成的 token 数；负数表示出错（-4 = 被中止）
     */
    fun generate(
        handle: Long,
        messages: List<LlmMessage>,
        maxTokens: Int,
        onToken: (String) -> Boolean,
    ): Int {
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        return nativeGenerate(handle, roles, contents, maxTokens, TokenSink(onToken))
    }

    private class TokenSink(private val fn: (String) -> Boolean) {
        @Suppress("unused")
        fun onToken(bytes: ByteArray): Boolean {
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) return true
            return fn(text)
        }
    }

    private external fun nativeCreate(configPath: String): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        listener: Any,
    ): Int
}