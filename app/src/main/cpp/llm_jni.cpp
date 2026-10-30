// MNN-LLM 的极简 JNI 封装。
// 只做三件事：加载模型、按消息列表流式生成、中止/释放。
// 生成结果通过 onToken(ByteArray) 回传（UTF-8 字节，已按完整字符切分），
// 用字节数组而不是 String 是为了绕开 JNI NewStringUTF 不支持 4 字节 UTF-8 的问题。

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <ostream>
#include <streambuf>
#include <string>
#include <vector>

#include <llm/llm.hpp>

#define LOG_TAG "TD-LLM"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {

struct Session {
    std::shared_ptr<Llm> llm;
    std::mutex mu;
    std::atomic<bool> abort{false};
};

std::string toStd(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

// 一段字节里有多少是完整的 UTF-8 字符
size_t completeUtf8Prefix(const std::string& s) {
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        int len = 0;
        if ((c & 0x80) == 0) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { ++i; continue; }
        if (i + len > s.size()) break;
        i += len;
    }
    return i;
}

class CallbackBuf : public std::streambuf {
public:
    using Fn = std::function<void(const char*, size_t)>;
    explicit CallbackBuf(Fn fn) : fn_(std::move(fn)) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (fn_ && n > 0) fn_(s, static_cast<size_t>(n));
        return n;
    }
    int_type overflow(int_type c) override {
        if (!traits_type::eq_int_type(c, traits_type::eof())) {
            char ch = traits_type::to_char_type(c);
            if (fn_) fn_(&ch, 1);
        }
        return traits_type::not_eof(c);
    }

private:
    Fn fn_;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_taverndream_llm_MnnLlm_nativeCreate(JNIEnv* env, jclass, jstring configPath) {
    const std::string path = toStd(env, configPath);
    if (path.empty()) return 0;
    LOGI("createLLM: %s", path.c_str());
    std::shared_ptr<Llm> llm(Llm::createLLM(path));
    if (!llm) {
        LOGE("createLLM returned null");
        return 0;
    }
    const bool ok = llm->load();
    LOGI("load() -> %d", static_cast<int>(ok));
    if (!ok) return 0;
    auto* s = new Session();
    s->llm = std::move(llm);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_taverndream_llm_MnnLlm_nativeRelease(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->mu);
        s->abort = true;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_com_taverndream_llm_MnnLlm_nativeStop(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s != nullptr) s->abort = true;
}

JNIEXPORT void JNICALL
Java_com_taverndream_llm_MnnLlm_nativeReset(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || !s->llm) return;
    std::lock_guard<std::mutex> lock(s->mu);
    s->llm->reset();
}

/**
 * 流式生成。阻塞调用，必须放在后台线程。
 * @param roles     角色数组（system/user/assistant）
 * @param contents  与 roles 对应的正文
 * @param listener  需实现 onToken(byte[]): Boolean，返回 false 表示中止
 * @return 生成的 token 数；负数表示失败
 */
JNIEXPORT jint JNICALL
Java_com_taverndream_llm_MnnLlm_nativeGenerate(JNIEnv* env, jclass, jlong handle,
                                               jobjectArray roles, jobjectArray contents,
                                               jint maxTokens, jobject listener) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || !s->llm) return -1;
    std::lock_guard<std::mutex> lock(s->mu);
    s->abort = false;

    const jsize n = roles ? env->GetArrayLength(roles) : 0;
    ChatMessages msgs;
    msgs.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto* r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto* c = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        msgs.emplace_back(toStd(env, r), toStd(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    if (msgs.empty()) return -2;

    jmethodID onToken = nullptr;
    jclass listenerCls = nullptr;
    if (listener != nullptr) {
        listenerCls = env->GetObjectClass(listener);
        onToken = env->GetMethodID(listenerCls, "onToken", "([B)Z");
        if (onToken == nullptr) {
            LOGE("listener.onToken([B)Z not found");
            return -3;
        }
    }

    std::string pending;
    bool aborted = false;

    auto emit = [&](const char* p, size_t len) {
        if (onToken == nullptr || len == 0) return;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len),
                                reinterpret_cast<const jbyte*>(p));
        jboolean keep = env->CallBooleanMethod(listener, onToken, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            aborted = true;
            return;
        }
        if (keep != JNI_TRUE) aborted = true;
    };

    // 过滤结束标记 <eop>，并保证只把完整 UTF-8 字符交给 Kotlin
    const std::string kEop = "<eop>";
    CallbackBuf buf([&](const char* p, size_t len) {
        if (aborted) return;
        pending.append(p, len);
        size_t pos;
        while ((pos = pending.find(kEop)) != std::string::npos) {
            pending.erase(pos, kEop.size());
        }
        size_t hold = 0;
        for (size_t k = kEop.size() - 1; k >= 1; --k) {
            if (pending.size() >= k &&
                pending.compare(pending.size() - k, k, kEop, 0, k) == 0) {
                hold = k;
                break;
            }
        }
        size_t usable = pending.size() - hold;
        if (usable > 0) {
            std::string head = pending.substr(0, usable);
            size_t cut = completeUtf8Prefix(head);
            if (cut > 0) {
                emit(head.data(), cut);
                pending.erase(0, cut);
            }
        }
        if (aborted) s->abort = true;
    });

    auto* ctx = const_cast<MNN::Transformer::LlmContext*>(s->llm->getContext());
    auto resetFinished = [ctx]() {
        if (ctx == nullptr) return;
        if (ctx->status == LlmStatus::NORMAL_FINISHED ||
            ctx->status == LlmStatus::MAX_TOKENS_FINISHED) {
            ctx->status = LlmStatus::RUNNING;
        }
    };

    std::ostream os(&buf);
    const int limit = maxTokens > 0 ? maxTokens : 512;

    resetFinished();
    s->llm->response(msgs, &os, kEop.c_str(), 0);  // 只做 prefill
    resetFinished();

    int generated = 0;
    while (generated < limit) {
        if (s->abort.load() || aborted) {
            if (ctx != nullptr) ctx->status = LlmStatus::USER_CANCEL;
            break;
        }
        if (s->llm->stoped()) break;
        s->llm->generate(1);
        ++generated;
    }

    // 收尾：把剩余完整字符吐出去
    if (!pending.empty() && !aborted) {
        size_t cut = completeUtf8Prefix(pending);
        if (cut > 0) emit(pending.data(), cut);
    }

    if (listenerCls != nullptr) env->DeleteLocalRef(listenerCls);
    return aborted ? -4 : generated;
}

}  // extern "C"