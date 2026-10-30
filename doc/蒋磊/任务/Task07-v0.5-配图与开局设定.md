# Task07 - v0.5 配图智能化与开局设定

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 任务编号 | Task07 |
| 关联需求 | `doc/需求/PRD-v0.5-配图与开局设定.md` |
| 负责人 | 蒋磊 |
| 状态 | 已完成 |
| 开始日期 | 2026-10-30 |
| 目标版本 | v0.5.1（versionCode 10） |

## 2. 任务信息

### 2.1 任务描述

把「自动配图」从"靠模型自觉"改成"有判断、有人设、有按钮"，并补上进入角色时的开局设定说明。

### 2.2 拆分方式

| 子任务 | 内容 |
|---|---|
| T07-1 | 角色辅助信息（外观 tag + 开局设定）生成与缓存 |
| T07-2 | 外观锚定接入出图链路 + 剔除角色名 |
| T07-3 | 配图约定改写：场景变化判断 |
| T07-4 | 输入框旁生图按钮（总结当前场景） |
| T07-5 | 聊天页开局设定卡 |
| T07-6 | 设置项：锁定角色外观、固定随机种子 |

### 2.3 依赖

- 依赖 Task06（翻译、设置分组）与 Task05（生图链路）已完成。
- 依赖对话 API 可用（在线模式）；本地模式需模型已加载。

### 2.5 缺失资料

无。角色卡格式与 SD 调用参数均已在既有任务中确认。

## 3. 技术方案

### 3.1 实现思路

1. **角色辅助信息**：新增 `CharacterMeta(tags, intro)`，落盘 `characters/<id>/meta.json`。首次进入角色时两次短问答生成——一次产出英文外观 tag（≤30 词，不含角色名），一次产出中文三行设定（世界观 / 你扮演 / 当前场景）。失败不阻塞，下次进入重试。
2. **外观锚定**：`SdPrompts.compose(anchors, scene)` 把外观 tag 放在最前面，截断到 77 token 时优先保住脸；`SdPrompts.stripName()` 删除角色名，避免"名字变题材"。
3. **场景判断**：改写 `PromptBuilder.systemPrompt()` 的配图约定，给出明确判据（换地点、换时间、新角色、大转折），并要求不写角色名、先人物后环境。依旧沿用 `<imagine>` 协议，正文不展示不保存。
4. **生图按钮**：`AppViewModel.generateSceneImage()` 取最近 6 条消息 + 固定外观 tag，让模型输出一行英文 tag，再走既有 `generateAndAttach()`。
5. **开局设定卡**：聊天列表首项渲染可折叠卡片；`AppViewModel.ensureCharacterMeta()` 负责生成，`regenerateMeta()` 支持菜单重生成。
6. **固定种子**：`Settings.fixedSeed` 开启时用角色 id 哈希作 seed，否则沿用随机。

### 3.2 计划文件清单

| 文件 | 操作 |
|---|---|
| `app/src/main/java/com/taverndream/data/CharacterMeta.kt` | 新增 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 |
| `app/src/main/java/com/taverndream/sd/SdPrompts.kt` | 修改 |
| `app/src/main/java/com/taverndream/llm/PromptBuilder.kt` | 修改 |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 |
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 |
| `app/build.gradle.kts` | 修改（版本 0.5.0） |

## 4. 涉及技能

| 技能 | 用途 |
|---|---|
| `funi-dev-process` | 任务与验证流程管理 |

> 本项目为 Kotlin/Jetpack Compose 自用 App，公司 Java 后端技能不适用。

## 5. 验收标准

| 编号 | 验收项 | 判定 |
|---|---|---|
| A1 | 首次进入角色，顶部出现开局设定卡（世界观 / 你扮演 / 当前场景） | 截图确认 |
| A2 | 第二次进入同一角色直接读缓存，不再生成 | 观察响应与 meta.json |
| A3 | 点输入框旁 🎨 按钮能出一张图并插入对话 | 真机出图 |
| A4 | 出图提示词含固定外观 tag 且不含角色名 | 日志确认 |
| A5 | 连续闲聊不出图，换场景时模型输出 `<imagine>` | 对话观察 |
| A6 | 设置页出现「锁定角色外观」「固定随机种子」两个开关并可保存 | 截图确认 |

## 6. 待验证 / 风险

| 项 | 说明 |
|---|---|
| 人脸一致性上限 | 当前内核无 IP-Adapter / ControlNet，运行时 LoRA 也无入口；只能靠「外观 tag + 固定种子」近似一致，做不到同一张脸 |
| img2img | **已核实不可行（2026-10-30）**：`xororz/SD21` 仓库只有 unet / clip / vae_decoder / tokenizer，**没有 vae_encoder**；内核缺它会直接禁用 img2img。要做需先用 mnnconvert 在 PC 上转换 VAE encoder，成本高且 NPU 支持未知 |
| 场景判断稳定性 | 依赖模型遵守提示词，可能漏出或重复出图；关键词兜底仍在 |
| 出图耗时 | 引擎冷启动约 45 秒，常驻时 5~10 秒 |

## 9. 实现索引

### 9.1 最终实现方案

- **角色辅助信息**：`CharacterMeta(tags, intro)` 落盘 `characters/<id>/meta.json`。`AppViewModel.ensureCharacterMeta()` 在首次进入角色时调用两次短问答（`buildAppearanceTags()` 出英文外观 tag、`buildSceneIntro()` 出三行中文设定），成功后写缓存；失败不写，下次进入自动重试。菜单「重新整理设定与外观」调用 `regenerateMeta()` 强制重算。
- **外观锚定**：`SdPrompts.compose()` 按 token 分组分配——外貌 ≤30、画风 ≤12、场景吃剩余预算、最后补质量词；跨组去重。`SdPrompts.stripName()` 剔除角色名（整词匹配，避免 Cricket → 板球）。
- **画风统一**：新增 `Settings.styleTags`，设置页可编辑，默认 `anime style, clean lineart, soft lighting`，每次出图都带上。
- **场景变化判断**：`PromptBuilder.systemPrompt()` 的配图约定改为给判据（换地点 / 换时间 / 新角色 / 大转折），并要求不写角色名、不重复外貌。模型没给 `<imagine>` 时，`looksLikeSceneChange()` 用地点和场景动词兜底，且最近 4 条消息内已有图就不再出图。
- **生图按钮**：`AppViewModel.generateSceneImage()` 取最近 6 条消息 + 固定外观 tag，经 `summarizeScene()` 让模型输出一行英文 tag，再走 `generateAndAttach(..., alreadyBusy = true)`。
- **开局设定卡**：`ChatScreen` 的 `IntroCard()` 渲染在消息列表首项，可点击折叠；数据来自 `vm.characterMeta.intro`，生成中显示进度圈。
- **固定种子**：`Settings.fixedSeed` 开启时 `imageSeed()` 用角色 id 哈希，否则沿用时间戳随机。
- **提示词日志**：出图前打印 `TD-SD prompt=...`，便于核对实际送入 SD 的提示词。

### 9.2 实际代码改动

| 文件 | 操作 | 关键类/方法/配置键 |
|---|---|---|
| `app/src/main/java/com/taverndream/data/CharacterMeta.kt` | 新增 | `CharacterMeta`、`CharacterMetaStore.read/save` |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | `lockAppearance`、`fixedSeed`、`styleTags` |
| `app/src/main/java/com/taverndream/sd/SdPrompts.kt` | 修改 | `stripName`、`compose`、`takeTags`、`cleanTagLine` |
| `app/src/main/java/com/taverndream/llm/PromptBuilder.kt` | 修改 | `systemPrompt()` 配图约定（场景变化判据、不写角色名、不重复外貌） |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | `characterMeta`/`metaLoading` 状态、`ensureCharacterMeta()`、`regenerateMeta()`、`askOnce()`、`cardBrief()`、`buildAppearanceTags()`、`buildSceneIntro()`、`generateSceneImage()`、`summarizeScene()`、`looksLikeSceneChange()`、`imageSeed()`、`prepareImagePrompt()`；`generateAndAttach()` 新增 `alreadyBusy` 参数 |
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 | `IntroCard()`、输入行 🎨 按钮、菜单「重新整理设定与外观」 |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 「锁定角色外观」「固定随机种子」「画风提示词」三个设置项 |
| `app/build.gradle.kts` | 修改 | 版本 0.5.1（versionCode 10） |

### 9.3 影响范围

| 项 | 影响 |
|---|---|
| 接口 | 无新增外部接口；继续沿用 OpenAI 兼容的 chat completions |
| 数据 | 新增 `characters/<id>/meta.json`；不改 `card.json` 与聊天记录 |
| 配置 | 新增 3 个配置键：`lockAppearance`、`fixedSeed`、`styleTags` |
| 外部服务 | 首次进入角色多 2 次短请求（约 6~10 秒）；之后走缓存 |

### 9.4 验证与风险

| 项 | 结论 |
|---|---|
| 构建 | ✅ `./gradlew assembleDebug` 通过，产物 v0.5.1（versionCode 10） |
| 外观 tag 生成 | ✅ API 层验证：输出 `1girl, young woman, short brown hair, grey eyes, expressive face, athletic build, leather armor...`，不含角色名 |
| 开局设定生成 | ✅ API 层验证：三行齐备，含世界观 / 你扮演 Elder / 当前场景 |
| 场景变化判断 | ✅ API 层验证：5 轮对话只有「出门去码头」触发一次 `<imagine>`，其余闲聊不出图 |
| 提示词预算 | ✅ 模拟验证：外貌 30 + 画风 12 + 场景 + 质量词 = 67/70 token，场景不再被挤掉 |
| 真机·设定卡 | ✅ 首次进入自动生成，卡片显示「世界观 / 你扮演 / 当前场景」，`{{user}}` 已替换为 Elder |
| 真机·设定缓存 | ✅ 重新进入直接读缓存，不再重复生成 |
| 真机·生图按钮 | ✅ 点 🎨 出图并插入对话；日志 `TD-SD prompt=` 显示「外观 → 画风 → 场景 → 质量词」且不含角色名 |
| 真机·设置项 | ✅ 「锁定角色外观」「固定随机种子」「画风提示词（默认 anime style, clean lineart, soft lighting）」均正常显示 |
| 真机·场景判断 | ✅ 同场景追问「Tell me about your guild」没有重复出图 |
| 真机·滚动 | ✅ 修复输出中上滑被拉回的问题（BUG-Task07-001） |
| 风险 | 见第 6 节；人物脸只能近似一致，img2img 已确认不可行 |


## 10. 修改记录

| 日期 | 内容 | 作者 |
|---|---|---|
| 2026-10-30 | 创建任务，完成代码实现与构建（v0.5.0 / versionCode 9） | 蒋磊 |
| 2026-10-30 | 真机验证通过；修复 `{{user}}` 未替换、输出中滑动被拉回（BUG-Task07-001），版本升到 v0.5.1 | 蒋磊 |
