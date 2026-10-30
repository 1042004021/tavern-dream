# 任务 - Task06

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 任务编号 | Task06 |
| 关联需求 | 老大 2026-10-30 追加：英文角色卡导入时翻译成中文；设置页分组；文本可长按复制 |
| 负责人 | 蒋磊 |
| 创建日期 | 2026-10-30 |
| 当前状态 | 代码完成，构建通过；待真机验证（设备被带走） |

## 2. 任务信息

### 2.1 任务名称

导入英文角色卡自动翻译为中文；设置页按功能分组；全应用文本可长按选择复制；SD 2.1 参数修正与下载健壮性修复。

### 2.2 任务描述

1. 导入角色卡时，若字段是英文，自动用当前对话引擎翻译成简体中文并保存。
2. 设置页原本七张卡片交叉排列（外观 → 对话引擎 → 自动配图 → 生图输出 → 对话模型 → 本地对话模型 → 本地生图），改为三组：外观 / 对话 / 生图。
3. 设置页与聊天气泡的文字支持长按选择复制（系统原生选择菜单）。
4. 修复真机验证中发现的三个问题：
   - SD 2.1 出噪声图（`--use_v_pred` 与分辨率参数不对）。
   - 模型下载中途被服务端掐断后整个失败（无断点续传）。
   - 覆盖安装后旧后端进程仍占用 8081，新后端"假启动"。

### 2.3 拆分方式

| 序号 | 子任务 | 说明 |
|---|---|---|
| 1 | 英文卡翻译 | 字段级英文检测 + 逐字段翻译 + 覆盖保存 |
| 2 | 设置页分组 | 卡片重排 + 分组标题 |
| 3 | 文本可复制 | `SelectionContainer` |
| 4 | SD 2.1 参数 | 实测矩阵后修正 |
| 5 | 下载断点续传 | Range + 自动重试 |
| 6 | 残留后端清理 | 启动前 pkill + 存活校验 |

## 3. 技术方案

### 3.1 英文卡翻译

- 新增 `data/CardTranslator.kt`：
  - `isEnglish()`：不含中日韩字符且字母数 ≥ 8 才认为需要翻译（中英混排的卡逐字段判断）。
  - `englishFields()`：收集需要翻的字段，key 为内部标识（`description`/`personality`/`scenario`/`first_mes`/`mes_example`/`alt_<i>`/`system_prompt`/`post_history_instructions`/`creator_notes`）。
  - `name` 与 `tags` **不翻译**（老大指定）。
  - `chunks()` 按段落切块（API 1500 字/块，本地模型 700 字/块）。
  - `clean()` 去掉模型爱加的代码块、引号、「译文：」前缀。
  - `SYSTEM_PROMPT` 要求保留星号动作、引号、换行、`{{char}}`/`{{user}}`、`<START>` 标记，人名不音译。
- `AppViewModel.translateCardIfNeeded()`：导入成功后串行翻译，字段级失败保留原文；全部失败则提示并保留英文。
- 本地模型未加载时不翻译，避免为导入一张卡加载 3GB 模型。
- `CharacterStore.save()` 新增：覆盖写 `card.json`（头像不动）。

### 3.2 设置页分组

| 分组 | 卡片 |
|---|---|
| 外观 | 主题（跟随系统 / 浅色 / 深色） |
| 对话 | 对话引擎、对话模型（OpenAI 兼容）、本地对话模型（MNN） |
| 生图 | 对话中自动配图、生图输出、本地生图（模型与引擎） |

分组标题用 `SectionHeader()`（主色、加粗、小字号）。

### 3.3 文本可复制

- 聊天气泡正文包 `SelectionContainer`，长按出现系统选择菜单（可整段复制或选一部分）。
- 设置页整块内容包 `SelectionContainer`。
- 图片气泡保持「点击/长按 → 看大图」。

### 3.4 SD 2.1 参数（真机实测矩阵）

512×512、8 步、seed 42，同一提示词：

| 文本嵌入 | v-pred | 结果 |
|---|---|---|
| 1024 | 开 | 纯色糊图 ❌ |
| **1024** | **关** | **正常出图（盔甲骑士 + 红裙女孩 + 酒馆窗）** ✅ |
| 768 | 开 | 纯色糊图 ❌ |
| 768 | 关 | 红蓝错乱 ❌ |

结论：`textEmbeddingSize = 1024` 必须保留，`useVpred` 必须关掉；768×576 分辨率实测仍出噪声，暂定 `nativeSize = 512`，靠 4:3 裁切出图。

### 3.5 下载断点续传

`SdModelManager.fetchResumable()`：以 `.part` 文件长度发 `Range: bytes=N-`，最多重试 20 次；服务端返回 200（不支持 Range）时从头再来一次；`unet.bin.part` 在用户重新点下载时继续续传。

实测：aifasthub 连接 20~160MB 会被掐断，改后 1.3GB 四文件全部下载完成（880,662,656 / 384,350,816 / 61,322,896 / 3,642,207 字节，与服务器一致）。

### 3.6 残留后端清理

- 启动前 `pkill -f libstable_diffusion_core.so`（仅 root 可用），并删掉旧 pid 文件。
- `/health` 通但自己拉起的进程已死 → 报「端口被占用的旧后端响应，请重试启动」，不再假报"运行中"。

## 4. 涉及文件清单

| 文件 | 改动 |
|---|---|
| `data/CardTranslator.kt` | 新增：英文检测、字段收集、切块、清洗、翻译提示词 |
| `data/CharacterStore.kt` | 新增 `save()` |
| `AppViewModel.kt` | `translatingLabel`、`translateCardIfNeeded()`、`translateText()` |
| `ui/CharacterListScreen.kt` | 标题副行显示翻译进度 |
| `ui/SettingsScreen.kt` | 三组重排 + `SectionHeader` + `SelectionContainer` |
| `ui/ChatScreen.kt` | 气泡正文 `SelectionContainer` |
| `sd/SdModelManager.kt` | SD2.1 参数修正；`fetchResumable()` 断点续传 |
| `sd/SdBackendService.kt` | 启动前清残留、健康检查校验自身进程 |

## 5. 验收标准

| 序号 | 验收项 |
|---|---|
| 1 | 导入英文卡后角色列表副标题出现「正在翻译…」，完成后描述/开场白等变中文 |
| 2 | 名字与标签保持英文原样 |
| 3 | 设置页分三组，顺序为 外观 / 对话 / 生图 |
| 4 | 聊天气泡长按出现系统选择菜单，可复制 |
| 5 | 设置页文字长按可选中复制 |
| 6 | SD 2.1 出正常画面（1024 嵌入、无 v-pred） |
| 7 | 模型下载中断后重新点下载能续传 |

## 6. 待验证 / 风险

| 项 | 说明 |
|---|---|
| SD 2.1 分辨率 | 768×576 实测两次均为噪声，暂用 512；是否只能 512×512 待进一步确认（若只能方图，横图会更糊） |
| 翻译耗时 | 一张卡 5~7 个字段，在线 API 约 10~20 秒，期间可正常使用 App，不阻塞 |
| 本地模型翻译 | Qwen3 1.7B 译文质量未知，未实测 |
| 真机验证 | 设备被老大带走，以上 7 项均待实测 |

## 9. 实现索引

### 9.1 最终实现方案

- **英文卡翻译**：`CardTranslator` 做字段级英文判定（无中日韩字符且字母数 ≥ 8），只翻名字与标签以外的字段；`AppViewModel.translateCardIfNeeded()` 串行翻译，字段级失败保留原文，全部失败提示并保留英文。翻译单块调用失败时 `translateChunkSafe()` 二分重试，`splitPoint()` 保证在段落/句末切分。
- **设置页分组**：卡片重排为「外观 / 对话 / 生图」三组，组标题由 `SectionHeader` 渲染；整块内容包 `SelectionContainer`，文字可长按选中复制。
- **聊天复制**：`MessageBubble` 的正文用 `SelectionContainer` 包裹（系统原生选择菜单），图片气泡保持点击/长按看大图。
- **SD 2.1**：`SdModel` 扩展 `nativeSize` / `textEmbeddingSize` / `useVpred` / `remoteFiles`；SD 2.1 用 512 原生、1024 文本嵌入、关闭 v-pred；散文件逐个下载。
- **下载健壮性**：`SdModelManager.fetchResumable()` 用 `Range` 断点续传，最多重试 20 次，服务端不支持 Range 时从头重来一次。
- **关闭思考模式**：`ChatApi.stream()` 在请求体里带 `enable_thinking: false`（实测 24.2s → 5.7s，思考内容 2862 字 → 0）；遇到 400 自动退回带思考重试一次；设置项「关闭思考模式」默认开启，对对话、翻译、提示词改写三处都生效。
- **残留后端**：`SdBackendService.killStaleBackends()` 启动前清理同可执行文件进程；`waitHealthy()` 增加自身进程存活校验，避免"假运行中"。

### 9.2 实际代码改动

| 文件 | 操作 | 关键类/方法 |
|---|---|---|
| `app/src/main/java/com/taverndream/data/CardTranslator.kt` | 新增 | `isEnglish`、`englishFields`、`apply`、`chunks`、`splitPoint`、`clean`、`SYSTEM_PROMPT` |
| `app/src/main/java/com/taverndream/data/CharacterStore.kt` | 修改 | 新增 `save()` 覆盖写 `card.json` |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | `translatingLabel`、`translateCardIfNeeded()`、`translateText()`、`translateChunkSafe()`、`translateChunk()` |
| `app/src/main/java/com/taverndream/ui/CharacterListScreen.kt` | 修改 | 标题副行显示 `vm.translatingLabel` |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 三组重排、`SectionHeader()`、`SelectionContainer` 包裹 |
| `app/src/main/java/com/taverndream/ui/ChatScreen.kt` | 修改 | `MessageBubble` 正文 `SelectionContainer` |
| `app/src/main/java/com/taverndream/sd/SdModelManager.kt` | 修改 | SD2.1 条目参数、`sd21Suffix()`、`downloadFiles()`、`fetchResumable()` |
| `app/src/main/java/com/taverndream/sd/SdBackendService.kt` | 修改 | `killStaleBackends()`、`waitHealthy()` 存活校验 |
| `app/src/main/java/com/taverndream/llm/ChatApi.kt` | 修改 | 新增 `disableThinking` 参数与 `streamOnce()`，400 时自动降级 |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | 新增 `disableThinking`（默认 true） |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 对话模型卡片新增「关闭思考模式」开关 |
| `app/build.gradle.kts` | 修改 | 版本 0.4.3（versionCode 8） |

### 9.3 影响范围

| 项 | 影响 |
|---|---|
| 接口 | 无（仅调用既有 OpenAI 兼容接口，`max_tokens` 由 2048 提到 8192） |
| 数据 | 角色卡 `card.json` 会被翻译结果覆盖；聊天记录不动 |
| 配置 | 无新增配置键 |
| 外部服务 | 依赖对话 API 的 `max_tokens` 上限；若某服务限制更低会返回错误，此时保留英文原文 |

### 9.4 验证与风险

| 项 | 结论 |
|---|---|
| 构建 | ✅ `./gradlew assembleDebug` 通过，产物 v0.4.2 |
| 设置分组 | ✅ 真机截图确认三组显示正确 |
| 设置页长按复制 | ✅ 真机出现「复制 / 全选」菜单 |
| 聊天气泡长按复制 | ✅ 真机通过：系统「复制 / 全选」菜单与「已复制」提示同时出现 |
| 英文卡翻译 | ✅ 真机通过：描述 3191→1151 字中文、对话示例 3538→1317 字中文、开场白与作者备注均为中文（BUG-Task06-001 已修复并验证） |
| SD 2.1 出图 | ✅ 真机通过：512 原生 → 4:3 居中裁切 → 4x 超分 → 2048×1536 JPEG（317KB），画面清晰无噪声，点击生图到出图约 45 秒 |
| 关闭思考模式 | ✅ 设置页开关默认开启并生效；真机对话 18 秒内返回完整回复（含流式输出）；API 实测 1430 字翻译 24.2s → 5.7s |
| 风险 | ① 部分服务商 `max_tokens` 上限低于 8192；② 512 分辨率的 SD 2.1 画质待评估；③ 个别服务不认 `enable_thinking`（已做 400 自动降级） |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-30 | 创建任务并完成实现，构建通过（v0.4.1 / versionCode 6） | 蒋磊 |
| 2026-10-30 | 真机验证设置分组与长按复制；修复长文本翻译失败（BUG-Task06-001），版本升到 v0.4.2 | 蒋磊 |