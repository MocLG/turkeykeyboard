cmake_minimum_required(VERSION 3.22)
project(tercuman_host LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(GGML_NATIVE ON CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp app)

add_executable(translate-cli main.cpp ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/translator_engine.cpp)
target_include_directories(translate-cli PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
target_link_libraries(translate-cli PRIVATE llama ggml)
