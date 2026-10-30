# BUG-Task02-001 生图提示「后端启动超时」

## 1. 基本信息

| 项 | 内容 |
|---|---|
| Bug 编号 | BUG-Task02-001 |
| 关联任务 | Task02-v0.2-本地对话 |
| 发现日期 | 2026-10-29 |
| 发现人 | 蒋磊 |
| 严重程度 | 严重（功能完全不可用） |
| 当前状态 | 已修复 |

## 2. Bug 描述

### 现象
点击生图并确认后，等待约两分钟，界面提示「生图失败：生图后端启动超时」，始终不出图。

### 复现步骤
1. 设置页确认生图模型已下载（Anything V5 NPU）。
2. 进入角色对话，点生成图片，点「生成」。
3. 等待约 120 秒。

### 预期结果
数秒内出图并插入会话。

### 实际结果
约 120 秒后提示「生图后端启动超时」。

### 环境
魅族 24129PN74C / SM8750 / 12GB / Android 16，APK v0.2.0 debug。

## 3. 影响分析

- 影响范围：所有走本地生图的操作。
- 数据影响：无。
- 用户影响：生图功能完全不可用。

## 4. 根因分析

`AppViewModel.generateImage()` 在 `viewModelScope.launch`（主线程调度器）里调用阻塞式的
`SdClient.healthy()`，内部使用 OkHttp 同步 execute。Android 主线程发起网络请求会抛
`NetworkOnMainThreadException`，而 `healthy()` 用 `runCatching{}.getOrDefault(false)` 把它吞成
`false`，于是：

- 进不去「后端已就绪」分支；
- 等待循环里的 `healthy()` 也永远返回 `false`；
- 循环跑满 120 次后抛出「生图后端启动超时」。

实测后端本身是正常的：同一时刻 `127.0.0.1:8081` 在监听，`/health` 返回 200，
手动 POST `/generate` 约 1.6 秒出图。

## 5. 修复方案

1. 所有 `healthy()` 调用改到 `Dispatchers.IO` 执行。
2. 同时修正「后端返回原始 RGB 像素、却被直接当 PNG 落盘」的问题（否则即使不超时，图片也是坏的）。
3. 后端错误信息带上 native 最后几行输出，便于以后直接看出原因。

## 6. 修复记录

| 日期 | 文件 | 改动 |
|---|---|---|
| 2026-10-29 | app/src/main/java/com/taverndream/AppViewModel.kt | `generateImage` 中健康检查统一走 `withContext(Dispatchers.IO)`；`saveImage` 改为 RGB 裸数据转 Bitmap 再编码 PNG |
| 2026-10-29 | app/src/main/java/com/taverndream/sd/SdClient.kt | `generate` 返回 `SdImage`（宽、高、通道、原始像素），不再返回字符串 |
| 2026-10-29 | app/src/main/java/com/taverndream/sd/SdBackendService.kt | 记录 native 最后输出，退出时拼接错误详情 |

## 7. 验证记录

| 项 | 结果 |
|---|---|
| 手动调用后端 `/generate` | 512×512、10 步、1.64 秒出图，图像正常 |
| App 内端到端出图（2026-10-30 真机） | **通过**。对话里说「Draw a picture of Cricket mixing a cocktail at the bar」，约 3 秒后出图并插入会话；落盘 `files/generated/c7d95d18-….png`，431KB，PNG 头合法，512×512，画面内容与提示相符 |
| 后端启动 | 不再报「后端启动超时」；后端进程正常拉起（本次为 root 模式），未出现 unsigned PD 报错 |

## 8. 备注

`healthy()` 现在的实现仍然是「吞异常返回 false」，这本身没问题，但调用方必须在后台线程。
后续新增的调用点同样要遵守这个约定。