# 任务 - Task05

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 任务编号 | Task05 |
| 关联需求 | PRD-v0.4-交互增强与生图模型（2026-10-30 老大追加） |
| 负责人 | 蒋磊 |
| 创建日期 | 2026-10-30 |
| 当前状态 | 代码完成，待真机验证（设备被带走） |

## 2. 任务信息

### 2.1 任务名称

对话可复制、图片可放大查看、浅色/深色主题、SD2.1 生图模型与提示词优化。

### 2.2 任务描述

1. 对话文本支持复制（长按气泡复制到剪贴板）。
2. 对话里的图片支持点击全屏查看，双指缩放、拖动平移。
3. App 支持浅色 / 深色 / 跟随系统三种主题，切换立即生效。
4. 生图模型增加 SD 2.1（提示词理解明显优于 SD1.5），并优化提示词链路。
5. 记录 SDXL 端侧评估结论（内存不足，暂不采用）。

### 2.3 拆分方式

| 序号 | 子任务 | 说明 |
|---|---|---|
| 1 | 消息复制 | 长按气泡写入剪贴板并提示 |
| 2 | 图片查看 | 全屏 Dialog + 变换手势 |
| 3 | 主题切换 | 浅色配色 + 状态栏图标 + 设置项 |
| 4 | SD2.1 接入 | 散文件下载、768 原生尺寸、v-pred、1024 维文本嵌入 |
| 5 | 提示词优化 | 中文 → 英文 tag、77 token 截断、去重、质量词 |
| 6 | SDXL 评估 | 端侧内存实测，结论归档 |

### 2.4 依赖关系

依赖 Task01（生图后端）、Task03（自动配图）、Task04（输出规格与超分）。

### 2.5 缺失资料

无。SD2.1 文件清单以 `xororz/SD21` 实际仓库为准（8gen1/2/3/4 分档，SM8750 对应 8gen4）。

## 3. 技术方案

### 3.1 实现思路

- **复制**：`MessageBubble` 用 `combinedClickable` 的 `onLongClick` 直接写 `LocalClipboardManager`，再通过 `vm.toast` 提示「已复制」。
- **看图**：点击图片打开全屏 `Dialog`（`usePlatformDefaultWidth = false`），`graphicsLayer` + `detectTransformGestures` 实现 1~6 倍缩放与拖动；点空白或右上角关闭。
- **主题**：`TavernTheme` 增加 `mode` 参数，内置浅色/深色两套 `ColorScheme`；`AppViewModel.themeMode` 是 Compose 状态，设置页切换即时重组；`SideEffect` 里设置状态栏/导航栏图标明暗。
- **SD2.1**：模型仓库是散文件而非 zip，`SdModel.remoteFiles` 支持多文件下载；按芯片选择 8gen4 变体；`nativeSize = 768`、`textEmbeddingSize = 1024`、`useVpred = true`。
- **提示词**：`SdPrompts` 负责去重/截断/补质量词；中文先用对话模型（API 或已加载的本地模型）改写成英文 tag，再压缩到 77 token 的安全长度（SD1.5 CLIP 硬上限）。

### 3.2 涉及文件清单

| 文件 | 改动 |
|---|---|
| `ui/ChatScreen.kt` | 长按复制、图片全屏缩放查看 |
| `ui/Theme.kt` | 浅色/深色两套配色、状态栏图标 |
| `ui/SettingsScreen.kt` | 新增「外观」卡片、主题即时生效 |
| `MainActivity.kt` | 主题模式由 ViewModel 提供 |
| `AppViewModel.kt` | `themeMode`、`applyTheme`、`prepareImagePrompt`、`rewriteToTags`、按模型原生尺寸出图 |
| `data/Settings.kt` | 新增 `themeMode` |
| `sd/SdModelManager.kt` | `SdModel` 扩展字段、SD2.1 条目、散文件下载、`sd21Suffix()` |
| `sd/SdBackendService.kt` | 透传 `--text_embedding_size`、`--use_v_pred` |
| `sd/SdPrompts.kt` | 新增：提示词整形 |

## 4. 验收标准

| 序号 | 验收项 |
|---|---|
| 1 | 长按消息气泡弹出「已复制」，剪贴板内容正确 |
| 2 | 点图片全屏显示，双指可放大、可拖动，点空白关闭 |
| 3 | 设置里切浅色/深色立刻生效，状态栏图标颜色正确 |
| 4 | 设置里能看到「SD 2.1（提示词理解更好）」，可下载（1.3GB） |
| 5 | 中文提示词能生成语义相符的图，不再出现「完全不搭」的画面 |
| 6 | SD2.1 出图为 768 级（4:3 即 768×576），内存不失控 |

## 5. 接口设计

不适用（沿用 `/generate`、`/upscale`）。

## 6. 涉及技能

| 技能 | 用途 |
|---|---|
| funi-dev-process | 任务/进度管理 |

## 7. 交付物

- 源码改动（见 3.2）
- `dist/tavern-dream-v0.4.0-debug.apk`
- 本文档

## 8. 测试要点

| 序号 | 测试点 |
|---|---|
| 1 | 复制纯文本消息、复制带图消息（无文本时不误触） |
| 2 | 大图（2048×1536）放大 6 倍后的清晰度与卡顿 |
| 3 | 浅色主题下各页面（列表 / 聊天 / 设置）配色与可读性 |
| 4 | SD2.1 首次启动时间、出图耗时、PSS 占用 |
| 5 | 中文提示词重写后的 actual prompt（看日志/界面提示） |
| 6 | 超分对 SD2.1 自动跳过（768 不再 4x） |

## 9. 实现索引

### 9.1 最终实现方案

- **消息复制**：`ChatScreen.MessageBubble` 增加 `onCopy` 回调，长按写 `LocalClipboardManager` 并置 `vm.toast = "已复制"`；图片气泡长按等同点击（无文本可复制时）。
- **图片查看**：`ImageZoomDialog` 全屏黑底，`AsyncImage` + `graphicsLayer(scale/translation)`，手势用 `detectTransformGestures`，缩放范围 1~6 倍，点空白或右上角 `Close` 退出。
- **主题**：`TavernTheme(mode)` 维护 `DarkPalette` / `LightPalette`，`mode` 取 `system/light/dark`；`AppViewModel.themeMode` 为 Compose 状态，`applyTheme()` 同时写库与刷新；设置页「外观」卡片切换即生效，不必点保存。
- **SD2.1 接入**：`SdModel.remoteFiles` 描述「远端路径 → 本地文件名」，`downloadFiles()` 逐个下载并按文件数加权报进度；`nativeSize=768`、`textEmbeddingSize=1024`、`useVpred=true`；`SdBackendService` 把后两者透传给 CLI。
- **提示词优化**：`SdPrompts.optimize()` 去重后按 70 token 预算截断并补 `masterpiece, best quality, highly detailed`；`AppViewModel.prepareImagePrompt()` 在含中文时先用对话模型改写（`REWRITE_SYSTEM_PROMPT`），本地模型未加载则跳过。
- **尺寸策略**：出图尺寸改为 `model.nativeSize`；`renderFinal()` 只对宽度 ≤ 640 的结果做 4x 超分，SD2.1 的 768 图不再放大。

### 9.2 实际代码改动

| 文件 | 操作 | 关键改动 |
|---|---|---|
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 | `MessageBubble` 增加 `onImageClick`/`onCopy` 与长按复制；新增 `ImageZoomDialog` |
| `app/src/main/java/com/taverndream/ui/Theme.kt` | 修改 | 新增浅色配色、`mode` 参数、状态栏图标明暗 |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 新增「外观」卡片（跟随系统/浅色/深色） |
| `app/src/main/java/com/taverndream/MainActivity.kt` | 修改 | `TavernTheme(mode = vm.themeMode)` |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | `themeMode`/`applyTheme`、`prepareImagePrompt`/`rewriteToTags`、按 `nativeSize` 出图、超分条件收紧 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | 新增 `themeMode` |
| `app/src/main/java/com/taverndream/sd/SdModelManager.kt` | 修改 | `SdModel` 扩展 `nativeSize/textEmbeddingSize/useVpred/remoteFiles`；新增 SD2.1 条目与 `sd21Suffix()`；新增 `downloadFiles()` |
| `app/src/main/java/com/taverndream/sd/SdBackendService.kt` | 修改 | `--text_embedding_size` 按模型传值；`--use_v_pred` 支持模型标记 |
| `app/src/main/java/com/taverndream/sd/SdPrompts.kt` | 新增 | 中文判定、token 估算、tag 去重截断、改写系统提示词 |
| `app/build.gradle.kts` | 修改 | 版本号 0.4.0（versionCode 5） |

### 9.3 SDXL 端侧评估结论（2026-10-30 实测）

| 项 | 结果 |
|---|---|
| 模型来源 | `xororz/sdxl-qnn`，46 个模型全部只提供 **8gen3** 变体，单个 3.3~3.6GB |
| 实测机型 | 魅族 24129PN74C / SM8750 / 12GB |
| 后端 PSS | **5,495,867 KB（约 5.5GB）**，另有旧 SD1.5 后端 616MB |
| 后果 | 内存不足触发 LMK（先杀输入法），随后系统重启 |
| 判断 | 12GB 机型不可用；已有 6GB zram 仍无法避免（QNN 张量需物理常驻，换出即失效） |
| 结论 | **暂不接入 SDXL**，改用 SD2.1（约 1.3GB，768 原生，v-pred）作为「更大模型」方案 |

### 9.4 验证与风险

| 项 | 说明 |
|---|---|
| 构建 | `assembleDebug` 通过，`versionName=0.4.0`（versionCode 5），dex 内可检索到「已复制」「双指缩放」「SD 2.1」「整理提示词」「跟随系统」 |
| 真机 | **未验证**（老大带走设备）；复制、看图、主题、SD2.1、提示词改写均待实测 |
| 风险 | ① SD2.1 的 `--text_embedding_size 1024` 与 `--use_v_pred` 需真机确认，若不匹配会出现噪声图；② 8gen4 变体能否在 SM8750 上正常加载待验证；③ 中文改写的额外耗时（约 2~4 秒）尚未实测 |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-30 | 创建任务并完成实现，构建通过；真机验证待设备返回 | 蒋磊 |