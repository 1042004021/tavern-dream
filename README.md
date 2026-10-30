# Tavern Dream

自用的 Android 原生角色扮演客户端：**导入角色卡 → 和 AI 对话 → 需要时本地出图**。
生图内核内置了 [local-dream](https://github.com/xororz/local-dream) 2.4.3 的端侧 Stable Diffusion，
对话可选**外部 API**或**本地 MNN-LLM 模型**（一个 APK，不需要再装第二个 App）。

> 仅个人自用、不分发。local-dream 为 CC BY-NC 4.0（禁止商业使用）。

## 功能

- 角色卡导入：PNG（V2/V3）、JSON、CharX；**英文卡自动翻译成简体中文**（名字和标签保留原文）
- 流式对话（双通道）：
  - 外部 API：任何 OpenAI 兼容接口（BaseURL + API Key + 模型名）
  - 本地模型：内置 MNN-LLM + Qwen3，断网可用，设置里一键切换
- 会话本地保存，按角色隔离
- **对话内自动配图**：你说「画一张…」或模型觉得画面值得画时，自动出图并插进对话（也可从右上角菜单手动生图改提示词）
- 对话内容长按选择复制；设置页文字同样可长按复制；图片点击全屏、双指缩放查看
- 浅色 / 深色 / 跟随系统三种主题，切换即时生效
- 本地生图：SD 1.5 内核，内置 4 种画风（Anything V5 / MeinaMix / MajicMix / DreamShaper）+ CPU 兜底；可选 **SD 2.1**（768 原生，提示词理解更好）
- 提示词自动整形：中文先由对话模型翻成英文 tag，再压到 77 token 以内（SD1.5 的 CLIP 上限）
- 输出规格：默认 4:3 横图（2048×1536），带 4x 超分；设置里可切方图 / 竖图、可关超分
- 模型管理：App 内下载与解压，可切换 HuggingFace / aifasthub 源；对话模型 5 档、生图模型 6 档（含 SD 2.1）
- 设置是二级页面（主页右上角菜单进入），分「外观 / 对话 / 生图」三组，改完点「保存」才生效

## 构建

前置：JDK 17、Android SDK（platform 34、build-tools 34.0.0）、NDK `27.2.12479018`、SDK CMake `3.22.1`。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建前必须放两份二进制（仓库不存二进制）：

- 生图内核：见 [`doc/资料/native库提取说明.md`](doc/资料/native库提取说明.md)
- 对话内核 `libMNN.so`：见 [`doc/资料/本地LLM编译说明.md`](doc/资料/本地LLM编译说明.md)

## 安装与使用

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 主页右上角「⋮」→「设置」，填写 BaseURL / API Key / 模型名（例：`https://api.openai.com/v1` + `gpt-4o-mini`），点「保存」
3. 回「角色」页 → 导入角色卡 → 点卡片开始聊天
4. 「设置 → 本地生图」→ 下载任一模型（约 1GB，建议 Wi-Fi）；再下载「超分模型」（8MB）；之后在对话里说「画一张…」就会自动出图
5. 想离线聊天：「设置 → 对话引擎」选「本地模型」→ 下载 Qwen3（默认 1.7B）→ 回聊天即可

## 产物

- `app/build/outputs/apk/debug/app-debug.apk`（约 81MB，含生图内核、QNN 库、MNN-LLM）
- 复制件：`dist/tavern-dream-v0.2.0-debug.apk`

## 说明与限制

| 项 | 说明 |
|---|---|
| 设备 | 仅 arm64-v8a（`minSdk 28`）；NPU 加速需骁龙 8 系，其它设备用 CPU 模型 |
| 存储 | 每个生图模型约 1GB、超分模型 8MB、对话模型 280MB~2.6GB，按需下载，位于 App 私有目录 |
| 本地对话内存 | 实测：1.7B 约 2.0GB PSS / 4B 约 3.2GB PSS，**与生图不能同时跑**（生图前自动卸载）；4B 会挤压后台应用 |
| 本地对话速度 | 实测 SM8750/12GB：1.7B 加载 2.8s、约 38 字符/秒；4B 加载 7.8s、约 19 字符/秒 |
| 保活 | 生图时以前台服务运行，国产 ROM 需允许后台运行 |
| 生图速度 | 实测 SM8750：512×512 / 10 步约 1.6 秒（NPU） |
| 模型源 | huggingface.co 不通；hf-mirror.com 于 2026-10-30 起连接超时，默认改为 `https://aifasthub.com`；对话模型走 taobao-mnn，生图走 xororz/sd-qnn、xororz/sd-mnn，超分走 xororz/upscaler |
| 自动配图 | 依赖模型输出 `<imagine>…</imagine>`，小模型偶尔不配合；不配合时由「画一张」类关键词兜底 |
| 分辨率 | QNN 模型固定 512×512，实测其它尺寸输出纯噪声；4:3 / 3:4 靠居中裁切 + 4x 超分实现，不是原生构图 |
| 未实现 | 世界书、群聊、TTS、正则、扩展、聊天导入导出 |

## 目录

```
app/src/main/java/com/taverndream/
├── data/   角色卡 / 会话 / 设置
├── llm/    OpenAI 兼容流式对话 + 本地 MNN-LLM
├── sd/     内置 SD 后端、模型下载
└── ui/     Compose 界面
doc/        项目说明、PRD、任务、进度
```
