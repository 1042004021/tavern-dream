# 任务 - Task01

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| 任务ID | Task01 |
| 关联需求 | PRD-v0.1 |
| 创建时间 | 2026-10-29 |
| 负责人 | 蒋磊 |
| 预计工时 | 3 人天 |
| 优先级 | P0 |

## 2. 任务信息

### 2.1 任务名称

Android 原生酒馆客户端 + 内置本地生图（首个可用版本）

### 2.2 任务描述

用 Kotlin + Compose 重写一个精简版酒馆：支持导入 SillyTavern 角色卡（PNG V2/V3、JSON、CharX），与 OpenAI 兼容接口流式对话，并把 local-dream 2.4.3 的端侧 Stable Diffusion 内核内置进同一 APK，在聊天中按需生图。只做主线功能，其余酒馆能力不做。

### 2.3 拆分方式

按功能点：角色卡解析与存储 / 对话与提示词 / 生图后端与模型 / 界面 / 打包验证。

### 2.4 依赖关系

| 前置任务 | 依赖说明 |
|----------|----------|
| 无 | 全新项目 |

### 2.5 缺失资料

| 编号 | 缺失项 | 说明 | 影响范围 |
|------|--------|------|----------|
| M001 | local-dream 2.4.3 官方 APK | 需要其中的 `libstable_diffusion_core.so` 与 `assets/qnnlibs/*`；本机访问 GitHub Release 极慢，采用分片并行下载 | 生图功能能否运行 |
| M002 | 端侧模型权重 | 模型体积约 1.1GB，不随 APK 分发，改为 App 内下载（HuggingFace / hf-mirror） | 首次生图前必须下载 |
| M003 | 目标机型 SoC 实机验证 | 本机无骁龙设备，NPU 路径只能静态核对，需在真机验证 | 生图性能与兼容性 |

## 3. 技术方案

### 3.1 实现思路

1. **角色卡**：直接读取 PNG 的 `tEXt` 块（关键字 `chara` / `ccv3`），base64 解码得到 JSON；兼容 V1（字段在根）、V2/V3（字段在 `data`）；CharX 按 zip 读取 `card.json` 与首张图片。落盘 `filesDir/characters/<uuid>/{card.json, avatar.png}`。
2. **对话**：OkHttp 调 `{baseUrl}/chat/completions`，`stream=true`，逐行解析 `data:`，回调增量文本；提示词按「角色设定 + 性格 + 场景 + 对话示例 + 补充要求」组装 system，历史按字符预算裁剪。
3. **生图**：把 local-dream 的 native 可执行文件作为 `jniLibs/arm64-v8a/libstable_diffusion_core.so` 打包，`qnnlibs` 放 assets；`SdBackendService`（前台服务）首次启动解压 QNN 运行库到 `filesDir/runtime_libs`，用 `ProcessBuilder` 启动，暴露 `127.0.0.1:8081`；`SdClient` 以 SSE 读取 `progress` / `complete`（base64 PNG）。
4. **模型**：单个 zip（`xororz/sd-qnn` 或 `xororz/sd-mnn`），下载后按文件名扁平解压到 `filesDir/models/<id>/`；下载源可切换 `huggingface.co` / `hf-mirror.com`。
5. **界面**：底部两个 Tab（角色 / 设置），聊天为全屏页面；生图用弹窗输入提示词，进度以线性进度条呈现。

### 3.2 计划涉及文件清单

| 文件路径 | 操作类型 | 说明 |
|----------|----------|------|
| `app/build.gradle.kts` | 新增 | 模块配置、依赖、ABI、useLegacyPackaging |
| `app/src/main/AndroidManifest.xml` | 新增 | 权限、前台服务声明 |
| `app/src/main/java/com/taverndream/data/CharacterCard.kt` | 新增 | 角色卡解析（PNG/JSON/CharX） |
| `app/src/main/java/com/taverndream/data/CharacterStore.kt` | 新增 | 角色卡落盘与读取 |
| `app/src/main/java/com/taverndream/data/ChatStore.kt` | 新增 | 会话持久化 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 新增 | 配置项 |
| `app/src/main/java/com/taverndream/llm/ChatApi.kt` | 新增 | OpenAI 兼容流式客户端 |
| `app/src/main/java/com/taverndream/llm/PromptBuilder.kt` | 新增 | 提示词组装 |
| `app/src/main/java/com/taverndream/sd/SdBackendService.kt` | 新增 | 内置生图后端前台服务 |
| `app/src/main/java/com/taverndream/sd/SdClient.kt` | 新增 | `/generate` SSE 客户端 |
| `app/src/main/java/com/taverndream/sd/SdModelManager.kt` | 新增 | 模型定义、下载、解压 |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 新增 | 业务编排与界面状态 |
| `app/src/main/java/com/taverndream/ui/*.kt` | 新增 | 角色列表 / 聊天 / 设置 / 主题 |
| `app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so` | 新增 | 从 local-dream 2.4.3 APK 提取 |
| `app/src/main/assets/qnnlibs/*` | 新增 | QNN 运行库，从同一 APK 提取 |

## 4. 验收标准

- [x] 能导入 PNG V2/V3 角色卡并正确显示名称、头像（代码路径完成，待真机点验）
- [x] 能导入 JSON / CharX 角色卡（同上）
- [ ] 对话可流式输出，退出重进消息仍在（待真机 + 有效 API Key）
- [ ] 生图引擎能被 App 启动并通过 `/health`（待真机）
- [ ] 生成图片可插入会话并显示（待真机）
- [ ] 模型可在 App 内下载并解压（下载源已校验可访问，端上流程待真机）
- [x] `assembleDebug` 构建通过，产物可安装（78MB debug APK）
- [ ] 自测通过（异常提示、无网络、模型缺失等边界）

## 5. 接口设计

| 接口 | 方法 | 说明 |
|---|---|---|
| `{baseUrl}/chat/completions` | POST | OpenAI 兼容，`stream=true` |
| `http://127.0.0.1:8081/health` | GET | 生图后端就绪检测 |
| `http://127.0.0.1:8081/generate` | POST | SSE：`progress` / `complete` / `error` |

## 6. 涉及技能

`funi-dev-process`（流程管理）。本项目为 Android/Kotlin，Java 后端相关技能不适用。

## 7. 交付物

| 交付物 | 路径 |
|---|---|
| 工程源码 | `/home/elder/IdeaProjects/自用测试/tavern-dream`（2026-10-30 迁移） |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 使用说明 | `doc/项目说明.md` |

## 8. 测试要点

| 编号 | 场景 | 预期 |
|---|---|---|
| T01 | 导入无 tEXt 的普通 PNG | 提示无法识别，不崩溃 |
| T02 | API Key 为空点发送 | 提示先配置，不发起请求 |
| T03 | 对话接口 401 | 提示 HTTP 401，已输入内容保留 |
| T04 | 未下载模型点生图 | 提示先下载模型 |
| T05 | 生图后端启动失败 | 弹窗显示引擎异常信息 |
| T06 | 长会话超出预算 | 自动裁剪最旧消息，不报错 |

## 9. 实现索引

| 功能 | 文件 |
|---|---|
| 角色卡解析（PNG tEXt / JSON / CharX） | `app/src/main/java/com/taverndream/data/CharacterCard.kt` |
| 角色卡存储 | `app/src/main/java/com/taverndream/data/CharacterStore.kt` |
| 会话存储 | `app/src/main/java/com/taverndream/data/ChatStore.kt` |
| 配置项 | `app/src/main/java/com/taverndream/data/Settings.kt` |
| 流式对话 | `app/src/main/java/com/taverndream/llm/ChatApi.kt` |
| 提示词组装 | `app/src/main/java/com/taverndream/llm/PromptBuilder.kt` |
| 生图后端服务 | `app/src/main/java/com/taverndream/sd/SdBackendService.kt` |
| 生图请求 | `app/src/main/java/com/taverndream/sd/SdClient.kt` |
| 模型下载解压 | `app/src/main/java/com/taverndream/sd/SdModelManager.kt` |
| 业务编排 | `app/src/main/java/com/taverndream/AppViewModel.kt` |
| 角色列表界面 | `app/src/main/java/com/taverndream/ui/CharacterListScreen.kt` |
| 聊天界面 | `app/src/main/java/com/taverndream/ui/ChatScreen.kt` |
| 设置界面 | `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` |

## 9.1 关键实现说明（事实核对）

| 项 | 结论 | 依据 |
|---|---|---|
| native 可执行文件 | `libstable_diffusion_core.so` 11.4MB，ELF aarch64 PIE，NDK r28 编译，已 stripped | `file` / `readelf` |
| 16KB 页对齐 | 三个 LOAD 段 Align 均为 `0x4000` | `readelf -lW` |
| QNN 运行库 | 20 个文件，解压后 132MB（V68/V69/V73/V75/V79/V81 + QnnHtp + QnnSystem） | APK 内容清单 |
| NPU clip 文件 | 2.4.3 的 `main.cpp` 在收到 `--clip clip.mnn` 时会自动升级到同目录 `clip_v2.mnn` 并加载 `pos_emb.bin`/`token_emb.bin`；当前 HF 模型包正是 clip_v2 布局 | `main.cpp` 第 690-715 行 |
| 模型包内容 | `unet.bin`、`vae_decoder.bin`、`vae_encoder.bin`、`clip_v2.mnn`、`pos_emb.bin`、`token_emb.bin`、`tokenizer.json`、各尺寸 patch | 读取 zip 中央目录 |
| 模型下载源 | `huggingface.co` 本机不可达；`hf-mirror.com` 可下载（8gen1 / 8gen2 / min / CPU 四个包均 206） | 实测 |
| 打包方式 | `useLegacyPackaging=true` → `extractNativeLibs=true`，安装后从 `nativeLibraryDir` 直接执行 | APK manifest 核对 |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-29 | 创建任务并完成首版实现 | 蒋磊 |
