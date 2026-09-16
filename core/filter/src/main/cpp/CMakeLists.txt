# FilterCamera - Native库CMake配置
# 编译美颜算法和位图处理Native库
# 以及GPUImage YUV解码器（替代预编译版本，支持16KB对齐）

cmake_minimum_required(VERSION 3.22.1)

project("filter")

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ========== libfilter.so - 美颜滤镜库 ==========
add_library(
    filter
    SHARED
    # JNI接口
    MagicJni.cpp
    # 美颜算法
    beautify/MagicBeautify.cpp
    # 位图处理
    bitmap/BitmapOperation.cpp
    bitmap/Conversion.cpp
)

# ========== libyuv-decoder.so - GPUImage YUV解码器 ==========
# 替代gpuimage预编译的libyuv-decoder.so，使用16KB页面对齐
#
# 注意：gpuimage/yuv-decoder.c 不在版本库里（历史提交中也从未存在过），
# 因此这里按"文件存在与否"决定是否编译替代版本：
# - 文件存在（放回原作者的源码）-> 编译 16KB 对齐的替代库，保持原设计
# - 文件不存在               -> 跳过，改用 gpuimage AAR 自带的预编译库
#                              （见 core/filter/build.gradle.kts 中 gpuimage 依赖处的说明）
# 两处必须成对切换，否则会出现两个 libyuv-decoder.so 争抢同一个文件名。
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/gpuimage/yuv-decoder.c")
    add_library(
        yuv-decoder
        SHARED
        gpuimage/yuv-decoder.c
    )
    set(YUV_DECODER_TARGET_BUILT TRUE)
else()
    set(YUV_DECODER_TARGET_BUILT FALSE)
    message(WARNING
        "gpuimage/yuv-decoder.c 不存在，跳过 16KB 对齐的 libyuv-decoder.so 编译；"
        "将使用 gpuimage AAR 自带的预编译库（4KB 对齐）。")
endif()

# 查找NDK系统库
find_library(
    log-lib
    log
)

find_library(
    jnigraphics-lib
    jnigraphics
)

find_library(
    android-lib
    android
)

find_library(
    GLESv2-lib
    GLESv2
)

# 链接库 - filter
target_link_libraries(
    filter
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

# 链接库 - yuv-decoder
if(YUV_DECODER_TARGET_BUILT)
    target_link_libraries(
        yuv-decoder
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
        ${GLESv2-lib}
    )
endif()

# 编译选项 - filter
target_compile_options(
    filter
    PRIVATE
    -Wall
    -O3
    -ffast-math
)

# 编译选项 - yuv-decoder
if(YUV_DECODER_TARGET_BUILT)
    target_compile_options(
        yuv-decoder
        PRIVATE
        -Wall
        -O3
    )
endif()

# 链接选项 - Android 15 (API 35+) 16KB页面大小对齐
# 确保Native库在16KB页面设备上正常运行
target_link_options(
    filter
    PRIVATE
    -Wl,-z,max-page-size=16384
)

if(YUV_DECODER_TARGET_BUILT)
    target_link_options(
        yuv-decoder
        PRIVATE
        -Wl,-z,max-page-size=16384
    )
endif()
