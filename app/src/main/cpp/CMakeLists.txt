cmake_minimum_required(VERSION 3.22.1)
project(mnnllm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mnnllm SHARED llm_jni.cpp)

target_include_directories(mnnllm PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/mnn/include)

# 预编译好的 libMNN.so（含 LLM 引擎），随 jniLibs 打进 APK
set(JNI_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION ${JNI_LIBS}/libMNN.so)

find_library(log-lib log)

target_link_libraries(mnnllm MNN ${log-lib})
target_link_options(mnnllm PRIVATE "-Wl,-z,max-page-size=16384")