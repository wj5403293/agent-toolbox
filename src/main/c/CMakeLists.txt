cmake_minimum_required(VERSION 3.22)

# Python 桥接库
# libpython3.14.so 已在 jniLibs/arm64-v8a/ 中，由 Android 自动加载
# 此库通过 JNI 提供 Python 执行能力

# Python 头文件（编译时需要）
# 从 assets 解压的 stdlib 不含 include/，需要单独获取头文件
# 如果 prefix/include/python3.14/ 存在则使用，否则需要手动指定
set(PYTHON_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../assets/python/include/python3.14"
    CACHE PATH "Python include directory")

# 桥接库
add_library(python_bridge SHARED
    python_bridge.c
)

# 链接：libpython3.14 + Android log
target_link_libraries(python_bridge
    python3.14    # 由 jniLibs 提供
    log           # Android logcat
)

target_include_directories(python_bridge PRIVATE
    ${PYTHON_INCLUDE_DIR}
)

set_target_properties(python_bridge PROPERTIES
    C_STANDARD 11
)
