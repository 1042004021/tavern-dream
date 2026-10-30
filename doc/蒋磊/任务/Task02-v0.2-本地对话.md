# 任务 - Task02

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 需求版本 | PRD-v0.2 |
| 任务编号 | Task02 |
| 创建日期 | 2026-10-29 |
| 责任人 | 蒋磊 |

## 2. 任务信息

### 2.1 任务名称

内置本地对话模型（MNN-LLM + Qwen3），与外部 API 双通道切换

### 2.2 任务描述

v0.1 的对话依赖外部 OpenAI 兼容接口。本任务在同一个 APK 内加入离线对话能力：
用户可在设置里选择「外部 API / 本地模型」，本地模型常驻内存，流式输出，生图前自动卸载。

### 2.3 拆分方式

| 序号 | 子项 | 说明 |
|---|---|---|
| 1 | 编译 MNN-LLM 运行库 | 产出 `libMNN.so`（含 LLM 引擎） |
| 2 | JNI 封装 | 创建/加载、流式生成、中止、释放 |
| 3 | 模型管理 | MNN 格式 Qwen3 按文件下载、生成运行时配置 |
| 4 | 业务接入 | 双通道切换、状态流、卸载策略 |
| 5 | 界面 | 设置页引擎选择、模型下载、状态显示 |

### 2.4 依赖关系

| 前置任务 | 依赖说明 |
|----------|----------|
| Task01 | 复用角色卡、会话、提示词组装、生图流程 |

### 2.5 缺失资料

| 编号 | 缺失项 | 说明 | 影响范围 |
|------|--------|------|----------|
| M004 | 骁龙真机 | 本机无 Android 设备，本地推理只能编译级验证 | 首次加载耗时、实际 token/s、内存峰值均待真机确认 |
| M005 | MNN 官方 AAR | MNN 只提供源码与示例 App，未发布 LLM 的 Gradle 依赖 | 必须自行交叉编译 `libMNN.so` |

## 3. 技术方案

### 3.1 实现思路

1. **运行库**：从 `codeload.github.com` 拉 MNN master 源码，用 NDK 27.2 + CMake 3.22 交叉编译
   `arm64-v8a` 的 `libMNN.so`（`MNN_BUILD_LLM=ON`、`MNN_SEP_BUILD=OFF`、16KB 对齐）。
   详见 `doc/资料/本地LLM编译说明.md`。
2. **JNI**：不照搬 MNN Chat 的整套 Kotlin（依赖 Gson/Jinja/ModelConfig 等），只写一个 ~200 行的
   `llm_jni.cpp`，直接调用 `MNN::Transformer::Llm`：先 `response(msgs, os, "<eop>", 0)` 做 prefill，
   再 `generate(1)` 逐 token 步进，这样用户点停止能真正打断。
3. **流式文本**：native 侧把字节流按完整 UTF-8 字符切分后以 `byte[]` 回调（避开 `NewStringUTF`
   对 4 字节 UTF-8 的限制），同时过滤 `<eop>`。
4. **模型**：`taobao-mnn/Qwen3-4B-MNN` / `Qwen3-1.7B-MNN`，按文件下载（不打 zip），已存在且大小一致
   的文件跳过。下载完成后合并模型自带的 `config.json` 生成 `runtime.json`（追加 `thread_num`、
   `prompt_cache`、`use_mmap`、`jinja.context.enable_thinking=false`）。
5. **接入**：`LocalLlmEngine` 持有 native 句柄，模型常驻；`AppViewModel.send()` 按 `chatBackend` 分流；
   生图开始时先释放本地模型。

### 3.2 计划涉及文件清单

| 文件路径 | 操作类型 | 说明 |
|----------|----------|------|
| `app/build.gradle.kts` | 修改 | 加 `ndkVersion`、`externalNativeBuild` |
| `app/src/main/cpp/CMakeLists.txt` | 新增 | 编译 `libmnnllm.so`，链接预编译 `libMNN.so` |
| `app/src/main/cpp/llm_jni.cpp` | 新增 | MNN-LLM 的 JNI 封装 |
| `app/src/main/cpp/mnn/include/**` | 新增 | MNN 与 llm 头文件 |
| `app/src/main/jniLibs/arm64-v8a/libMNN.so` | 新增 | 编译产物（仓库不存，按文档重编） |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | 新增 | `libMNN.so` 的运行时依赖 |
| `app/src/main/java/com/taverndream/llm/MnnLlm.kt` | 新增 | native 方法声明与库加载 |
| `app/src/main/java/com/taverndream/llm/LocalLlmEngine.kt` | 新增 | 会话句柄与状态管理 |
| `app/src/main/java/com/taverndream/llm/LlmModelManager.kt` | 新增 | 模型定义、下载、运行时配置生成 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | 增加 `chatBackend` / `localModelId` / `llmThreads` |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | 双通道分流、下载、卸载 |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 引擎选择、模型下载、状态 |
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 | 本地模型加载中的提示文案 |

## 4. 验收标准

1. 断网、不填 API Key 时可正常对话。
2. 切回 API 时行为与 v0.1 一致。
3. 模型下载中断后可继续（已完成的文件跳过）。
4. 加载失败/内存不足有明确提示，不闪退。
5. 生图与本地对话不会同时占用内存。
6. `./gradlew assembleDebug` 通过，APK 内含 `libMNN.so`、`libmnnllm.so`、`libc++_shared.so`。

## 5. 接口设计

native 侧（`com.taverndream.llm.MnnLlm`）：

| 方法 | 签名 | 说明 |
|---|---|---|
| `nativeCreate` | `(String configPath): Long` | 创建并加载模型，返回句柄，0 = 失败 |
| `nativeGenerate` | `(Long, Array<String> roles, Array<String> contents, Int maxTokens, Any listener): Int` | 流式生成，listener 需实现 `onToken(ByteArray): Boolean` |
| `nativeStop` | `(Long)` | 中止当前生成 |
| `nativeReset` | `(Long)` | 清空上下文 |
| `nativeRelease` | `(Long)` | 释放 |

## 6. 涉及技能

`funi-dev-process`（研发流程）

## 7. 交付物

| 类型 | 内容 |
|---|---|
| 代码 | 上述文件清单 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 文档 | `doc/需求/PRD-v0.2-本地对话.md`、`doc/资料/本地LLM编译说明.md`、本文件 |

## 8. 测试要点

| 编号 | 场景 | 预期 |
|---|---|---|
| T01 | 未下载模型时选本地并发消息 | 提示「模型还没下载」，不崩溃 |
| T02 | 下载 Qwen3-4B 过程中断网 | 状态显示失败原因，重试可续传 |
| T03 | 首次本地对话 | 提示加载中，加载完成后流式出字 |
| T04 | 连续多轮对话 | 上下文连贯，第二轮起明显变快（prompt_cache） |
| T05 | 本地对话中发起生图 | 本地模型先卸载，内存不叠加 |
| T06 | 切回外部 API | 与 v0.1 完全一致 |
| T07 | 本地生成中按返回/清空会话 | 不崩溃 |

## 9. 实现索引

| 功能 | 文件 |
|---|---|
| native 库加载与调用 | `app/src/main/java/com/taverndream/llm/MnnLlm.kt` |
| 会话句柄/状态 | `app/src/main/java/com/taverndream/llm/LocalLlmEngine.kt` |
| 模型下载与运行时配置 | `app/src/main/java/com/taverndream/llm/LlmModelManager.kt` |
| JNI 实现 | `app/src/main/cpp/llm_jni.cpp` |
| native 构建 | `app/src/main/cpp/CMakeLists.txt` |
| 双通道分流 | `app/src/main/java/com/taverndream/AppViewModel.kt`（`send`） |
| 设置界面 | `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` |

## 9.1 关键实现说明（事实核对）

| 项 | 结论 | 依据 |
|---|---|---|
| local-dream 是否支持文本模型 | 不支持。仓库描述/标签只有 SD，`main.cpp` 内 `llm/gguf/llama/qwen/chat` 零命中，作者 HF 12 个仓库全为图像模型 | 仓库 API + 源码检索 |
| MNN 是否支持 Qwen3 | 支持，MNN Chat 0.8.0 起支持 Qwen3.5，0.8.3 支持 Qwen3.5 / Gemma4 | MNN Chat README |
| 官方 MNN 格式权重 | `taobao-mnn/Qwen3-4B-MNN` 2.588GB、`Qwen3-1.7B-MNN` 1.178GB、`Qwen3.5-4B-MNN` 2.714GB（含 188MB visual） | hf-mirror API 文件清单 |
| Qwen3-4B 模型文件 | `config.json`、`llm_config.json`、`llm.mnn`、`llm.mnn.weight`、`tokenizer.txt`，`is_visual=false` | 实际下载 config/llm_config |
| 思考模式开关 | 模型模板走 `jinja.chat_template`；`setChatTemplate()` 读取 `jinja.context`，故注入 `enable_thinking=false` 可关闭 | `llm.cpp` `setChatTemplate` + `llmconfig.hpp` 深合并语义 |
| 编译坑 | CMake ≥3.20 禁止对 OBJECT 库用 `POST_BUILD`，必须 `-DMNN_BUILD_FOR_ANDROID_COMMAND=true` 走 `INSTALL` 分支 | 实测报错与 `transformers/llm/engine/CMakeLists.txt` |
| LLM 是否编入 | `libMNN.so` 导出约 85 个 `MNN::Transformer::*` 符号 | `llvm-nm -D` |
| 与生图共存 | 二者内存峰值相加超机身可用内存，故生图前主动 `release()` | 4B 约 3GB + SD 约 1.5GB |

## 9.2 实机实测数据（2026-10-29）

设备：SoC `SM8750`（骁龙 8 Elite），内存 12GB（MemTotal 11.4GB），Android 16，arm64-v8a。

| 模型 | 首次加载 | 生成速度 | 整机 PSS | 输出质量 |
|---|---|---|---|---|
| Qwen3 1.7B | **2.8 秒** | **约 38 字符/秒（≈10 token/s）** | 1.98GB（native 636MB + mmap 1.21GB） | 可用，偶尔答非所问 |
| Qwen3 4B | **7.8 秒** | **约 19 字符/秒（≈5 token/s）** | 3.21GB（native 371MB + mmap 1.61GB） | 明显更好，稳定在角色内、有动作描写 |

其它结论：

| 项 | 结果 |
|---|---|
| 模型加载 | `load() -> 1`，mmap 生效，1.7B 仅 2.8 秒（远快于预估的 10~30 秒） |
| 卸载 | 点「卸载」后进程 PSS 从 3.2GB 回落到 105MB，native 释放正常 |
| 内存压力 | 4B 运行期间系统 LMK 回收了微信、QQ、GMS（`oom_score_adj` 500~905），说明 12GB 机型跑 4B 会挤压后台 |
| 显存/GPU | 未使用，纯 CPU（6 线程） |
| 断点续传 | 手机端下载中途卡住，恢复后从断点继续（已修） |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-29 | 创建任务并完成首版实现，构建通过 | 蒋磊 |
| 2026-10-29 | SM8750/12GB 真机跑通 1.7B 与 4B 本地对话，补充实测数据与内存风险 | 蒋磊 |
| 2026-10-29 | 生图「后端启动超时」与 RGB 裸数据问题修复，见 BUG-Task02-001（实现在 Task03） | 蒋磊 |