cmake_minimum_required(VERSION 3.18.1)

project("localtube-native")

# Include Node.js C++ headers
include_directories(${CMAKE_SOURCE_DIR}/../../../libnode/include/node/)

# Import prebuilt libnode.so shared library
add_library(libnode SHARED IMPORTED)
set_target_properties(
    libnode
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so
)

# Build JNI bridge shared library
add_library(
    native-lib
    SHARED
    native-lib.cpp
    rn-bridge.cpp
)

find_library(log-lib log)

# Link native-lib with libnode and Android log library
target_link_libraries(
    native-lib
    libnode
    ${log-lib}
)
