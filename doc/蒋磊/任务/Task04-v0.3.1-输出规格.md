# 任务 - Task04

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 任务编号 | Task04 |
| 关联需求 | PRD-v0.3-体验优化 第 3.6 节（2026-10-30 老大追加） |
| 负责人 | 蒋磊 |
| 创建日期 | 2026-10-30 |
| 当前状态 | 已完成（2026-10-30 真机验证通过） |

## 2. 任务信息

### 2.1 任务名称

生图输出规格：4:3 横图 + 4x 超分。

### 2.2 任务描述

老大反馈希望出图不是 512 方图，要 4:3 横图，并且要清晰。经实测当前 QNN 模型固定 512×512，
无法直出其它比例，因此采用「居中裁切 + 后端 4x 超分」实现：

- 512×512 生成 → 居中裁 512×384（4:3）→ `/upscale` 4x → **2048×1536**。
- 设置页新增「生图输出」卡片：比例（方图 / 横图 / 竖图）与 4x 超分开关（默认开）。
- 超分模型（realesrgan_x4plus_anime_6b，约 8MB）在 App 内下载。

### 2.3 拆分方式

| 序号 | 子任务 | 说明 |
|---|---|---|
| 1 | 规格验证 | 实测后端对各尺寸的响应，确认模型固定 512 |
| 2 | 超分协议对接 | 摸清 `/upscale` 的 header 与请求体格式并验证 |
| 3 | 超分模型管理 | 8MB 单文件下载、状态流 |
| 4 | 出图流水线 | 裁切 → 超分 → JPEG 落盘，失败可降级 |
| 5 | 设置界面 | 比例选择、超分开关、模型下载与重试 |

### 2.4 依赖关系

依赖 Task01（生图后端）、Task03（自动配图流水线）。两者均已完成。

### 2.5 缺失资料

无。超分模型清单以 `xororz/upscaler` 实际文件为准；`/upscale` 协议由二进制字符串与真机请求实测确认。

## 3. 技术方案

### 3.1 实现思路

- **比例**：模型只能出 512×512，比例靠对原始 RGB 像素做居中裁切（4:3 → 512×384，3:4 → 384×512），不重新生成。
- **超分**：后端自带 `/upscale` 路由，请求体是原始 RGB，`X-Upscaler-Path` 指向超分模型 bin，响应为 JPEG，输出尺寸由后端 4x 决定（2048×1536）。
- **降级**：超分未下载或调用失败时，直接保存裁切后的 512 图，不阻断出图。
- **落盘**：统一改存 JPEG(q95)，2048×1536 约 680KB，比 PNG 小一个量级。

### 3.2 涉及文件清单

| 文件 | 改动 |
|---|---|
| `sd/UpscalerManager.kt` | 新增：超分模型下载与状态 |
| `sd/SdClient.kt` | 新增 `upscale()` 调用 `/upscale` |
| `data/Settings.kt` | 新增 `sdRatio`、`sdUpscale`；下载源默认改 aifasthub |
| `AppViewModel.kt` | 出图改为 裁切 → 超分 → JPEG；新增 `downloadUpscaler` |
| `ui/SettingsScreen.kt` | 新增「生图输出」卡片、超分模型下载与重试 |

## 4. 验收标准

| 序号 | 验收项 |
|---|---|
| 1 | 默认出图为 4:3（2048×1536），画面无噪声 |
| 2 | 切换方图 / 竖图后输出比例随之改变 |
| 3 | 关闭超分后输出 512×384 |
| 4 | 超分模型未下载时不出错，自动降级为裁切原图 |
| 5 | 下载失败后可以点「重新下载」 |

## 5. 接口设计

后端 `POST /upscale`（本地 127.0.0.1:8081）：

| 项 | 值 |
|---|---|
| Header `X-Image-Width` / `X-Image-Height` | 输入宽高 |
| Header `X-Upscaler-Path` | 超分模型绝对路径 |
| Body | 原始 RGB 字节（`application/octet-stream`） |
| 响应 | JPEG 字节；`X-Output-Width` / `X-Output-Height` / `X-Duration-Ms` |

## 6. 涉及技能

| 技能 | 用途 |
|---|---|
| funi-dev-process | 任务/进度管理 |

## 7. 交付物

- 源码改动（见 3.2）
- `dist/tavern-dream-v0.3.1-debug.apk`
- 本文档

## 8. 测试要点

| 序号 | 测试点 |
|---|---|
| 1 | 三种比例的裁切是否居中、主体是否被切坏 |
| 2 | 超分耗时与失败降级 |
| 3 | 超分模型下载（8MB）与断网重试 |
| 4 | 聊天列表加载 2048 图不卡顿、不 OOM |

## 9. 实现索引

### 9.1 最终实现方案

- **比例**：`AppViewModel.targetSize()` 按 `sdRatio` 算目标宽高，`cropRgb()` 对后端返回的原始 RGB 做居中裁切，得到 4:3 / 3:4 / 1:1 的裸像素。
- **超分**：`SdClient.upscale()` 把裸像素 POST 到 `/upscale`，带 `X-Image-Width/Height` 与 `X-Upscaler-Path`，响应 JPEG 直接 `BitmapFactory` 解码。
- **模型**：`UpscalerManager` 复用 `SdModelManager.suffix()` 的芯片映射，从 `xororz/upscaler` 下载 `upscaler_<suffix>.bin`（实测 8gen2 与 8gen4 文件相同，均可用），存到 `files/upscaler/upscaler.bin`。
- **降级**：`AppViewModel.renderFinal()` 在超分未下载或抛异常时，回退为裁切后的原尺寸 Bitmap 并记录 `TD-SD` 日志。
- **落盘**：`renderFinal()` 统一编码 JPEG(q95)，文件名为 UUID + `.jpg`。
- **下载源**：`settings.hfMirror` 默认值由 `hf-mirror.com` 改为 `https://aifasthub.com`（hf-mirror 已不可达）。

### 9.2 实际代码改动

| 文件 | 操作 | 关键改动 |
|---|---|---|
| `app/src/main/java/com/taverndream/sd/UpscalerManager.kt` | 新增 | 超分模型路径、`isDownloaded`、`remotePath`、`download` 与状态流 |
| `app/src/main/java/com/taverndream/sd/SdClient.kt` | 修改 | 新增 `upscale()`：POST `/upscale`，返回解码后的 Bitmap |
| `app/src/main/java/com/taverndream/data/Settings.kt` | 修改 | 新增 `sdRatio`（默认 `4:3`）、`sdUpscale`（默认 true）；`hfMirror` 默认改 aifasthub |
| `app/src/main/java/com/taverndream/AppViewModel.kt` | 修改 | 新增 `upscalerState`、`downloadUpscaler()`；`renderFinal/targetSize/cropRgb/rgbToBitmap` 取代原 `saveImage` |
| `app/src/main/java/com/taverndream/ui/SettingsScreen.kt` | 修改 | 新增「生图输出」卡片（比例单选、超分开关、模型下载/重下） |
| `app/build.gradle.kts` | 修改 | 版本号 0.3.1（versionCode 4） |

### 9.3 实测数据（2026-10-30 真机）

| 项 | 结果 |
|---|---|
| 后端对 512×768 / 768×768 的响应 | 返回请求尺寸，但画面为纯噪声（模型固定 512） |
| `/upscale`（512×384 → 4x） | 535~663ms，输出 2048×1536 JPEG |
| 超分模型下载 | 8,353,936 字节，约 10 秒（aifasthub） |
| App 端到端 | 消息 → 文字回复 → 自动配图 → 2048×1536 / 679KB，画面清晰、无噪声 |
| 聊天显示 | 4:3 横图正常铺满气泡 |

### 9.4 验证与风险

| 项 | 说明 |
|---|---|
| 构建 | `assembleDebug` 通过，`versionName=0.3.1` |
| 真机 | 已装 v0.3.1 并完成端到端验证 |
| 风险 | 裁切会切掉上下（4:3）或左右（3:4）的内容，主体过偏时可能被切；`hf-mirror` 不可用时需手动改下载源 |

## 10. 修改记录

| 日期 | 内容 | 修改人 |
|---|---|---|
| 2026-10-30 | 创建任务并完成首版实现，真机验证通过 | 蒋磊 |