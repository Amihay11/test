cmake_minimum_required(VERSION 3.22.1)
project(drumengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(drumengine SHARED
    DrumEngine.cpp
    OnsetDetector.cpp
    jni_bridge.cpp)

# The analysis runs in real time, so optimize even in debug builds.
target_compile_options(drumengine PRIVATE -O2 -Wall)
target_link_libraries(drumengine oboe::oboe log)
