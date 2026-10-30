# BUG - BUG-Task06-001

## 1. 基本信息

| 项 | 内容 |
|---|---|
| Bug 编号 | BUG-Task06-001 |
| 关联任务 | Task06（v0.4.1 导入翻译与设置分组） |
| 严重程度 | 中（功能部分失效，不影响稳定性） |
| 状态 | 已验证通过 |
| 发现日期 | 2026-10-30 |
| 修复日期 | 2026-10-30 |

## 2. Bug 描述

### 2.1 现象

导入英文角色卡后，短字段（开场白 377 字、作者备注 350 字）翻译成功，长字段（角色描述 3191 字、对话示例 3558 字）仍保留英文。

### 2.2 复现步骤

1. 设置里选「外部 API」，模型 `qwen3.8-flash`。
2. 导入一张英文角色卡（Cricket，描述 3191 字）。
3. 等翻译完成，查看 `card.json`。

### 2.3 预期 / 实际

- 预期：所有英文字段翻成中文。
- 实际：`description`、`mes_example` 仍是英文；`first_mes`、`creator_notes` 已翻译。

### 2.4 环境

魅族 24129PN74C / SM8750 / Android 16，App v0.4.1（versionCode 6），API `https://tokenrhythm.studio/v1`，模型 `qwen3.8-flash`。

## 3. 影响分析

- 长字段（角色描述、对话示例）是角色卡里最关键的内容，没翻译等于主要功能失效。
- 不影响 App 稳定性，导入本身成功。

## 4. 根因分析

逐条复现 API 调用后定位：

| 输入长度 | max_tokens | finish_reason | 正文字数 |
|---|---|---|---|
| 400 字 | 2048 | stop | 147 |
| 1500 字 | 2048 | **length** | **0** |
| 1500 字 | 4096 | stop | 555 |

`qwen3.8-flash` 会先输出「思考」（`reasoning_content`）再给正文。长文本时思考把 `max_tokens` 预算耗尽，正文返回空字符串，`finish_reason=length`。

原实现把空正文当作失败抛出异常，整段字段因此被丢弃（保留英文原文）。

补充实测：即使给到 8192，仍有约 1/5 概率出现同样的空返回（思考跑飞），所以单靠加大 token 不够。

## 5. 修复方案

1. `maxTokens` 2048 → 8192，给思考留余量。
2. 新增 `translateChunkSafe()`：单块翻译失败（正文为空）时按长度二分，递归重试，直到 ≤ 200 字仍失败才放弃。
3. 新增 `CardTranslator.splitPoint()`：切分点优先落在换行、句号、问号、感叹号处，避免把一句话劈成两半。

## 6. 修复记录

| 文件 | 操作 | 关键改动 |
|---|---|---|
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | `translateText()` 改为逐块循环拼接；新增 `translateChunkSafe()`、`translateChunk()`；API 调用 `maxTokens` 改 8192 |
| `app/src/main/java/com/taverndream/data/CardTranslator.kt` | 修改 | 新增 `splitPoint()` |

关联 Task06 实现索引：见 `doc/蒋磊/任务/Task06-v0.4.1-导入翻译与设置分组.md` 第 9 节。

## 7. 验证记录

| 验证项 | 方法 | 结果 |
|---|---|---|
| 短文本 | 400 字直连 API | ✅ 正常返回 147 字 |
| 长文本（1500 字 / 8192 token） | 直连 API | 偶发空返回（复现了原问题） |
| 二分重试 | 对空返回的 1430 字块按 `splitPoint` 切分重试 | ✅ 两块均返回，合计 462 字，译文正确（「类型：主角 种族：人类 外貌：棕色短发，灰色眼睛…」） |
| 全字段模拟 | 按新逻辑跑 description（3 块）+ mes_example（3 块） | ✅ 6 块全部有正文，合计 599 / 1304 字 |
| 构建 | `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL，产物 v0.4.2（versionCode 7） |
| 真机导入 | 重新导入同一张英文卡（Cricket，描述 3191 字 / 对话示例 3558 字） | ✅ 描述 3191→1151 字中文、对话示例 3538→1317 字中文，App v0.4.3（versionCode 8） |
| 关闭思考后的翻译 | 同上（`enable_thinking: false` 同时生效） | ✅ 单块翻译 5.7s，无空返回 |

## 8. 备注

- 修复前导入的卡（`0c9b159c…`）描述和对话示例仍是英文，已用新卡（`2fd86eae…`）复测通过。
- 修好后：v0.4.2 二分重试保底，v0.4.3 又关掉思考，长文本翻译基本不会再空。
- 若需要「对已导入的卡重新翻译」，可考虑在角色卡长按菜单里加一项，暂未实现。
