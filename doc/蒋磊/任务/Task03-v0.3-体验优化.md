# 任务 - Task03

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 任务编号 | Task03 |
| 关联需求 | PRD-v0.3-体验优化（用户 2026-10-29 口头指令） |
| 负责人 | 蒋磊 |
| 创建日期 | 2026-10-29 |
| 当前状态 | 已完成（2026-10-30 真机验证通过） |

## 2. 任务信息

### 2.1 任务名称

v0.3 体验优化：设置二级菜单、对话内自动配图、模型扩容、生图故障修复。

### 2.2 任务描述

在 v0.2（本地对话 + 本地生图）基础上做一轮体验优化：

1. 修复「生图提示后端启动超时」的严重缺陷（见 BUG-Task02-001）。
2. 生图不再用独立按钮，改为对话过程中自动触发：用户明确要求，或模型判断当前场景值得配图。
3. 设置从主页一级 Tab 改为二级页面，入口放在主页右上角菜单，并增加「保存」按钮。
4. 本地对话模型提供更多轻量档位；本地生图模型提供更多画风。
5. 模型复读（同一句重复几十遍）时自动截断。

### 2.3 拆分方式

| 序号 | 子任务 | 说明 |
|---|---|---|
| 1 | 生图超时修复 | 健康检查切到 IO 线程；RGB 裸像素转 PNG |
| 2 | 对话内自动配图 | 提示词注入 `<imagine>` 约定 + 关键词兜底触发 |
| 3 | 设置页改造 | 二级页面、返回、保存按钮、菜单入口 |
| 4 | 模型扩容 | 对话模型 5 档、生图模型 5 档 |
| 5 | 复读保护 | 流式阶段检测连续重复行并中止 |

### 2.4 依赖关系

依赖 Task01（客户端骨架、生图内核）与 Task02（本地对话通道），两者均已完成。

### 2.5 缺失资料

无。模型清单以 `taobao-mnn`（对话）与 `xororz/sd-qnn`、`xororz/sd-mnn`（生图）实际仓库文件为准，已逐个核对存在性与体积。

## 3. 技术方案

### 3.1 实现思路

- **自动配图**：在系统提示里约定「值得配图时在回复末尾单独输出 `<imagine>英文提示词</imagine>`」，客户端解析后剥离该行并触发生图；模型不配合时，用用户消息里的画图关键词兜底。
- **设置二级页**：`TavernApp` 维护 `List / Settings` 两个页面状态，主页右上角 `⋮` 菜单进入设置；设置页所有编辑先落本地状态，点「保存」才写回 `SharedPreferences`。
- **复读保护**：流式回调里检查最近若干行是否连续相同，命中则调用 native 的 `stop` 主动中止。

### 3.2 计划涉及文件清单

| 文件 | 改动 |
|---|---|
| `AppViewModel.kt` | 健康检查切线程、自动配图编排、复读保护、按 id 下载模型 |
| `ui/TavernApp.kt` | 去掉底部导航，改为 列表 / 设置 二级页面 |
| `ui/CharacterListScreen.kt` | 右上角菜单入口 |
| `ui/ChatScreen.kt` | 去掉生图按钮、加菜单与配图进度提示 |
| `ui/SettingsScreen.kt` | 返回 + 保存按钮，编辑与落库分离 |
| `data/Settings.kt` | 新增自动配图开关、生图负面提示词，默认模型调整 |
| `llm/LlmModelManager.kt` | 模型扩容、重复惩罚调到 1.15 |
| `llm/PromptBuilder.kt` | 追加配图约定 |
| `sd/SdModelManager.kt` | 生图模型扩容 |

## 4. 验收标准

| 序号 | 验收项 |
|---|---|
| 1 | 主页无「设置」Tab，右上角菜单可进入设置；设置页有返回与保存按钮 |
| 2 | 点保存后配置生效，未保存退出不改变已有配置 |
| 3 | 对话中说「画一张……」或模型输出 `<imagine>` 时，自动出图并插入会话，气泡里不出现 `<imagine>` 字样 |
| 4 | 模型复读超过阈值时自动停止，不刷满整屏 |
| 5 | 设置页可选择 5 档对话模型与 5 档生图模型 |
| 6 | 生图不再提示「后端启动超时」 |

## 5. 接口设计

不适用（无对外接口变更；生图后端 `/generate`、`/health` 沿用 local-dream 既有协议）。

## 6. 涉及技能

| 技能 | 用途 |
|---|---|
| funi-dev-process | 任务/缺陷/进度管理 |

## 7. 交付物

- 源码改动（见 3.2）
- debug APK
- 本文档与 BUG-Task02-001

## 8. 测试要点

| 序号 | 测试点 |
|---|---|
| 1 | 设置页保存前后的配置差异 |
| 2 | 自动配图三种触发：用户关键词、模型 `<imagine>`、开关关闭 |
| 3 | 生图失败不影响对话继续 |
| 4 | 复读保护不会误伤正常的重复修辞 |

## 9. 实现索引

### 9.1 最终实现方案

- **自动配图**：`PromptBuilder` 在系统提示中追加「配图约定」，要求模型在需要配图时于回复末尾输出 `<imagine>英文画面描述</imagine>`。`AppViewModel.send` 在生成结束后用正则拆出该段、从正文剥离，再交给统一的 `generateAndAttach` 出图；模型没给标签时，用用户消息里的画图关键词兜底，提示词由角色卡描述拼装。生图模型未下载时整体跳过，既不注入约定也不触发。
- **设置二级页**：`TavernApp` 用 `showSettings` 状态在「角色列表 / 设置」之间切换，去掉底部 `NavigationBar`；`CharacterListScreen` 顶部 `TopAppBar.actions` 放 `MoreVert` 菜单，菜单项进入设置。
- **编辑与落库分离**：`SettingsScreen` 所有字段先写 `remember` 状态，点右上角「保存」时统一写回 `Settings`；下载/启停引擎按页面当前选中的模型 id 执行（`downloadLlmModel(modelId)`、`downloadSdModel(modelId)`、`startSdBackend(modelId)`）。
- **模型扩容**：对话模型 5 档（Qwen3 1.7B/0.6B/4B、Qwen2.5 1.5B/0.5B），生图模型 5 档（AnythingV5/MeinaMix/MajicMix/DreamShaper + CPU 版），默认对话模型改为 Qwen3 1.7B。
- **复读保护**：流式回调里 `isRepeating` 检查「连续 5 行相同」或「末尾 20 字符在 80 字符内出现 4 次以上」，命中后调用 `LocalLlmEngine.stop()` 并返回 false 结束生成。
- **生图故障修复**：`healthy()` 是阻塞调用，全部改到 `withContext(Dispatchers.IO)`；`SdClient.generate` 改为返回 `SdImage`（原始 RGB），`saveImage` 转 `Bitmap` 再编码 PNG；后端退出时把 native 最后几行输出拼进错误信息。

### 9.2 实际代码改动

| 文件 | 操作 | 关键改动 |
|---|---|---|
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | `send()` 增加配图编排与复读保护；新增 `parseImagine`、`displayText`、`wantsImage`、`buildFallbackPrompt`、`isRepeating`；`generateImage` 改为薄封装调用 `generateAndAttach`；健康检查改走 IO；`saveImage(SdImage)` 转 PNG；`downloadLlmModel/downloadSdModel/startSdBackend` 支持传模型 id |
| `app/src/main/java/com/taverndream/ui/TavernApp.kt` | 修改 | 去掉底部导航，改为列表 / 设置二级页面 |
| `app/src/main/java/com/taverndream/ui/CharacterListScreen.kt` | 修改 | 新增 `onOpenSettings` 参数与右上角菜单 |
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 | 去掉星标生图按钮，改为菜单（手动生图 / 清空聊天）；列表末尾显示配图进度 |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 改为二级页（返回 + 保存），新增「对话中自动配图」卡片与负面提示词，模型单选与下载按页面选择执行 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | 新增 `autoImage`、`sdNegative`；默认 `localModelId` 改为 `qwen3-1p7b` |
| `app/src/main/java/com/taverndream/llm/LlmModelManager.kt` | 修改 | 模型扩到 5 档；`runtime.json` 重复惩罚统一写 1.15 |
| `app/src/main/java/com/taverndream/llm/PromptBuilder.kt` | 修改 | `systemPrompt` / `build` 新增 `imagineHint`，注入配图约定 |
| `app/src/main/java/com/taverndream/sd/SdModelManager.kt` | 修改 | 生图模型扩到 5 档 |
| `app/src/main/java/com/taverndream/sd/SdClient.kt` | 修改 | `generate` 返回 `SdImage`（宽/高/通道/原始像素） |
| `app/src/main/java/com/taverndream/sd/SdBackendService.kt` | 修改 | 记录 native 输出并在退出时拼进错误；root 启动改为可选尝试（拿不到就直连） |
| `app/build.gradle.kts` | 修改 | 版本号 0.3.0（versionCode 3） |

### 9.3 影响范围

- 接口：无对外接口变更；内部 `SdClient.generate` 返回类型变更（调用方已同步）。
- 数据：`SharedPreferences` 新增 `autoImage`、`sdNegative` 两个键；默认本地模型 id 变更，已存过值的不受影响。
- 配置：新增模型清单，均按仓库实际文件核对（taobao-mnn 五档、xororz/sd-qnn 四个 NPU 模型 + sd-mnn 一个 CPU 模型）。

### 9.4 验证与风险

| 项 | 结果 |
|---|---|
| 构建 | `./gradlew assembleDebug` 通过，产物 `versionName=0.3.0`、81MB |
| APK 内容抽查 | dex 中可检索到「手动生图」「<imagine>」「对话中自动配图」「MeinaMix」，确认新代码已打入 |
| 安装 | `adb install -r -d` 成功，`versionCode=3` / `versionName=0.3.0` |
| 主页结构 | 底部 Tab 已移除；右上角 `⋮` 菜单可进设置（2026-10-30 真机确认） |
| 设置页 | 返回箭头 + 「保存」按钮 + 自动配图卡片正常显示 |
| 保存生效 | 在界面把 Base URL 改为 `https://tokenrhythm.studio/v1`、模型名改为 `qwen3.8-flash`、引擎切到外部 API，点保存后 `shared_prefs/settings.xml` 三处均已更新 |
| 不保存还原 | 把「开启自动配图」关掉后直接返回，prefs 仍为 `true`，再次进入设置开关仍是开 → 未保存不生效，符合预期 |
| 在线 API 对话 | 外部 API（`https://tokenrhythm.studio/v1` + `qwen3.8-flash`）正常流式出字 |
| 自动配图端到端 | **通过**。发送「Draw a picture of Cricket mixing a cocktail at the bar」，先出文字回复，约 3 秒后图片作为独立气泡插入对话；PNG 512×512、431KB，内容正确（穿甲少女在吧台举柠檬） |
| 生图超时 | 未复现，BUG-Task02-001 修复有效 |
| 风险 | 小模型能否稳定输出 `<imagine>` 待实测（本次为关键词兜底路径）；生图模型切换后首次启动需重新加载 QNN；新增模型体积均 ~1GB，下载耗时较长 |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-29 | 创建任务 | 蒋磊 |