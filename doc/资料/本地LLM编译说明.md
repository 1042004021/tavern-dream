# 本地对话内核（MNN-LLM）编译说明

App 的本地对话能力来自阿里 MNN 的 LLM 引擎。`libMNN.so` 体积几 MB，仓库不存二进制，
需要按本文重新编译一次，产物放到 `app/src/main/jniLibs/arm64-v8a/libMNN.so`。

## 1. 版本与来源

| 项 | 值 |
|---|---|
| 源码 | `https://codeload.github.com/alibaba/MNN/zip/refs/heads/master`（实测 11MB/s，比 GitHub 网页下载快得多） |
| NDK | `27.2.12479018`（与官方 MNN Chat 0.8.3 一致） |
| CMake | `3.22.6`（**不能用本机 4.x**，见第 4 节） |
| 目标 ABI | `arm64-v8a`，API 28 |

## 2. 编译命令

```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/27.2.12479018
mkdir -p MNN/project/android/build_llm && cd MNN/project/android/build_llm

cmake ../../.. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_STL=c++_shared \
  -DANDROID_NATIVE_API_LEVEL=android-28 \
  -DNATIVE_LIBRARY_OUTPUT=. -DNATIVE_INCLUDE_OUTPUT=. \
  -DMNN_BUILD_FOR_ANDROID_COMMAND=true \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DMNN_BUILD_SHARED_LIBS=ON \
  -DMNN_BUILD_LLM=ON \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_ARM82=ON \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_USE_LOGCAT=ON \
  -DMNN_OPENCL=ON \
  -DMNN_SEP_BUILD=OFF \
  -DMNN_BUILD_TEST=OFF \
  -DMNN_BUILD_BENCHMARK=OFF \
  -DMNN_BUILD_TOOLS=OFF \
  -DCMAKE_INSTALL_PREFIX=install

make -j12
# 产物：build_llm/libMNN.so
```

## 3. 放进工程

```bash
cp MNN/project/android/build_llm/libMNN.so app/src/main/jniLibs/arm64-v8a/
cp $ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
   app/src/main/jniLibs/arm64-v8a/
# 头文件（编译自己的 JNI 封装用）
cp -r MNN/include/MNN app/src/main/cpp/mnn/include/
cp MNN/transformers/llm/engine/include/llm/*.hpp app/src/main/cpp/mnn/include/llm/
```

`libc++_shared.so` 必须一起打包：`libMNN.so` 的 `DT_NEEDED` 里有它。

## 4. 两个必踩的坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `Target "llm" is an OBJECT library that may not have PRE_BUILD, PRE_LINK, or POST_BUILD commands` | `transformers/llm/engine/CMakeLists.txt` 在非 `MNN_BUILD_FOR_ANDROID_COMMAND` 时给 OBJECT 库挂 `add_custom_command`，CMake ≥3.20 禁止 | 加 `-DMNN_BUILD_FOR_ANDROID_COMMAND=true`（走 `INSTALL` 分支） |
| 同样报错 | CMake 4.x 更严格 | 用 CMake 3.22.x；本机可 `pip install --target /tmp/cmake322 cmake==3.22.6` 后带 `PYTHONPATH` 调用 |

## 5. 校验

```bash
llvm-nm -D --defined-only libMNN.so | grep -c Transformer   # 85 个左右，说明 LLM 编进去了
llvm-readelf -d libMNN.so | grep NEEDED                     # 应含 libc++_shared.so
```

## 6. 自有 JNI 封装

`app/src/main/cpp/llm_jni.cpp` 只包了四个动作：创建/加载、流式生成、中止、释放。
生成结果按**完整 UTF-8 字符**切成 `byte[]` 回传 Kotlin（`NewStringUTF` 不支持 4 字节 UTF-8，
emoji 会挂），并顺手过滤掉 MNN 的 `<eop>` 结束标记。