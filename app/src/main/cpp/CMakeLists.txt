cmake_minimum_required(VERSION 3.22.1)
project(recall C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build llama.cpp (pinned git submodule) with the options passed from app/build.gradle.kts.
set(GGML_SYSTEM_ARCH "ARM")
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(recall SHARED recall_jni.cpp)

target_include_directories(recall PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(recall
        llama
        llama-common
        android
        log)
