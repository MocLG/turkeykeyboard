cmake_minimum_required(VERSION 3.22)

project(tercuman LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(LLAMA_CPP_TAG "b11857" CACHE STRING "llama.cpp release tag")

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

if(ANDROID)
    set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(tercuman SHARED
    translator_engine.cpp
    jni_bridge.cpp
)

target_link_libraries(tercuman PRIVATE llama ggml)

if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(tercuman PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(ANDROID)
    target_link_libraries(tercuman PRIVATE log)
    target_link_options(tercuman PRIVATE "-Wl,-z,max-page-size=16384")
endif()
