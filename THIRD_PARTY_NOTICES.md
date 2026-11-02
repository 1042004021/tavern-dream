# 第三方来源与许可声明

本清单记录已识别的主要来源，不是全部传递依赖的法律合规认证。
本项目并非以下项目的官方客户端，也不表示得到原作者认可或背书。

## 1. local-dream

- 作者／项目：xororz / local-dream
- 来源：https://github.com/xororz/local-dream
- 本项目使用版本：v2.4.3
- 对应许可证：https://github.com/xororz/local-dream/blob/v2.4.3/LICENSE
- 许可：CC BY-NC 4.0；完整文本见 `LICENSES/CC-BY-NC-4.0-local-dream.txt`。
- 已确认的改编：`app/src/main/java/com/taverndream/sd/SdBackendService.kt`，源于上游 BackendService，适配本项目服务、启动和状态管理。
- 关联集成：`sd/` 中的 HTTP 客户端、模型目录与下载、运行库加载及超分调用与上游内核协议衔接；这些文件的逐段来源仍应在后续审查中核实，不宣称全部独立原创。
- 本项目改动：接入 Compose 角色聊天、后台生成后插图、SDXL 模型参数与低内存启动等。改编不代表原作者认可。
- 生图可执行文件 `libstable_diffusion_core.so` 未纳入 Git。排除二进制并不消除改编源码本身的署名和许可义务。

## 2. MNN

- 作者／项目：Alibaba Group / MNN
- 来源：https://github.com/alibaba/MNN
- 许可：Apache License 2.0；完整文本见 `LICENSES/Apache-2.0-MNN.txt`。
- 已纳入源码：`app/src/main/cpp/mnn/include/` 下的头文件，保留原有版权声明（部分文件同时带 Android Open Source Project 声明）。
- 运行依赖：本地 LLM 使用 MNN；`libMNN.so` 未纳入 Git。
- 当前头文件对应的精确上游提交尚未锁定。后续更新应记录版本，检查对应上游 NOTICE 和第三方依赖，并保留随附声明。

## 3. SillyTavern：功能参考与角色卡格式兼容

- 来源：https://github.com/SillyTavern/SillyTavern
- 上游许可：AGPL-3.0；https://github.com/SillyTavern/SillyTavern/blob/release/LICENSE
- 本项目使用 Kotlin/Compose 实现角色卡解析与对话，源码注释记录了对角色卡格式和 Chat Completion 结构的参考。
- 当前未确认有直接复制的 SillyTavern 实现；不能据此作出“完全没有衍生代码”的法律保证。若后续确认存在复制或改编，应保留原声明并评估 AGPL 与其他许可的兼容性，再决定如何发布。
- 仅兼容数据格式不等于获得角色卡内容和插画的授权。

## 4. Gradle 与 Maven 依赖

- Gradle Wrapper：Apache-2.0，https://github.com/gradle/gradle/blob/master/LICENSE
- AndroidX / Compose、OkHttp、kotlinx.coroutines、kotlinx.serialization、Coil：主要直接依赖采用 Apache-2.0；实际版本以 `app/build.gradle.kts` 为准。
- Kotlin 与工具链：遵守各自发行版许可。
- 此处不替代实际构建产物的依赖清单。公开分发 APK 前，应检查实际解析的依赖、传递依赖及随附 NOTICE。

## 5. 不在上述项目许可范围内的内容

- Qualcomm QNN 运行库：适用 Qualcomm 的具体发行与再分发条款。当前未完成所使用版本的再分发许可核对；不能因其出现在上游 APK 中就推定可再次分发。
- 模型权重（包括 Qwen、SD、Illustrious 和超分模型）：转换文件与基础模型可能有不同条款，需逐个核对；下载地址不是授权证明。
- 角色卡、头像、聊天记录及生成图片：不随本仓库授权；需另行考虑内容权利和隐私。
- Android NDK 的 C++ 运行库等打包组件：适用对应工具链的许可证和第三方声明。

## 6. 源码公开与 APK 分发的边界

本仓库公开源码不表示已经审查通过所有 APK 分发义务。
当前不在 Git 中保存 native 运行库、APK、模型、个人角色卡或日志。
含 QNN 等组件的 APK 暂限本地构建与使用，未完成条款核对前不发布 Release 附件。

核对日期：2026-11-02。本文是项目来源记录，不构成法律意见。
