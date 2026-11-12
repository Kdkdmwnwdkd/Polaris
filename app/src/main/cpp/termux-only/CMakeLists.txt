# 只编「终端 JNI」的最小构建脚本——**仅供 x86_64 模拟器验证容器链路**。
#
# 为什么单独一份目录：AGP 强制要求 externalNativeBuild 的 path 文件名必须是
# CMakeLists.txt（写别的名字直接 [CXX1400] 报错），所以只能用子目录区分。
#
# 为什么需要它：主 CMakeLists 的第一件正事是 add_subdirectory(llama.cpp)，
# 而 llama.cpp 源码不进 git（由 scripts/fetch-llama.sh 拉）、且只支持 arm64。
# 在 x86_64 模拟器上只想验「proot 会话能不能起来」，不需要 llama，
# 于是这里只声明 agentkit-termux 一个目标，绕开整个 llama 配置阶段。
#
# 由 app/build.gradle.kts 在 `-PnoNative=true -Px86Rootfs=true` 时选用。
cmake_minimum_required(VERSION 3.22.1)
project(agentkit-termux-only)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# forkpty/openpty 由 bionic（libc）直接提供，无需额外链接库。
add_library(agentkit-termux SHARED ../termux-jni/termux-jni.cpp)
target_link_libraries(agentkit-termux android log)
