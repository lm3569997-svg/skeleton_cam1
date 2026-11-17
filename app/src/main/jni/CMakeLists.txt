cmake_minimum_required(VERSION 3.18)
project(poseguard LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(poseguard SHARED poseguard_jni.cpp)

# ncnn 预编译包随仓库放在 app/src/main/jni/ncnn/ 下
set(NCNN_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI})

target_include_directories(poseguard PRIVATE ${CMAKE_SOURCE_DIR}/ncnn/include/ncnn)

target_link_libraries(poseguard
    ${NCNN_DIR}/libncnn.so
    android
    jnigraphics
    log)
