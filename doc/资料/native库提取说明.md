# native 库提取说明

生图内核来自 local-dream 2.4.3（CC BY-NC 4.0，个人自用）。仓库不存放二进制，按下面步骤从官方 APK 提取。

> 以下仅描述本地构建操作，不授予任何组件的再分发权。
> QNN 等运行库应独立核对其具体版本条款，不能因上游 APK 包含这些文件就推定允许再次分发。
> 发布 APK 前请先查看仓库根目录 `THIRD_PARTY_NOTICES.md`。

## 0. 本地已备好的原包

工程内已存一份，不必重新下载：`素材/local-dream-2.4.3.apk`（54MB，`素材/` 已在 `.gitignore` 中忽略）。
MNN 源码同样放在 `素材/MNN-src.zip`（已解压的 `素材/MNN-master/` 也在）。

## 1. 下载官方 APK

```
https://github.com/xororz/local-dream/releases/download/v2.4.3/LocalDream_armv8a_2.4.3.apk
```

国内可用代理：在原始地址前加 `https://gh-proxy.com/`。

## 2. 提取文件

```bash
unzip -o LocalDream_armv8a_2.4.3.apk 'lib/arm64-v8a/libstable_diffusion_core.so' -d /tmp/ld
unzip -o LocalDream_armv8a_2.4.3.apk 'assets/qnnlibs/*' -d /tmp/ld

mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/assets
cp /tmp/ld/lib/arm64-v8a/libstable_diffusion_core.so app/src/main/jniLibs/arm64-v8a/
cp -r /tmp/ld/assets/qnnlibs app/src/main/assets/
```

## 3. 校验

```bash
file app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so
# 期望：ELF 64-bit LSB ... ARM aarch64
readelf -l app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so | grep LOAD
# 若 Android 15+ 设备，需 16KB 对齐（Align 0x4000）
```

## 4. 注意

- `useLegacyPackaging = true` 会让 .so 以未压缩形式打包，`applicationInfo.nativeLibraryDir` 下可直接执行。
- 若把 .so 放在 `jniLibs` 之外（例如 assets），Android 10+ 不允许执行私有目录可执行文件。
