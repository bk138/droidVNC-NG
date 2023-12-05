
cmake_minimum_required(VERSION 3.4.1)

project (droidVNC-NG C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared Libs" FORCE)

# Requred to enable SIMD support
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=aarch64-linux-android${ANDROID_VERSION}")
elseif (CMAKE_ANDROID_ARCH_ABI MATCHES "^armeabi.*")  # armeabi-v7a || armeabi-v6 || armeabi
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=arm-linux-androideabi${ANDROID_VERSION}")
endif ()

# build libJPEG
message("------libjpeg-turbo-----")
set(libjpeg_src_DIR ${CMAKE_SOURCE_DIR}/../../../../libjpeg-turbo)
set(libjpeg_build_DIR ${CMAKE_BINARY_DIR}/libjpeg)
# adds a source subdir with specifying a build output dir
add_subdirectory(${libjpeg_src_DIR} ${libjpeg_build_DIR})
# set these variables so FindJPEG can find the library
set(JPEG_LIBRARY ${libjpeg_build_DIR}/libturbojpeg.a CACHE FILEPATH "")
set(JPEG_INCLUDE_DIR ${libjpeg_src_DIR} CACHE PATH "")
# set include directories so dependent code can find the headers
include_directories(
        ${libjpeg_src_DIR}
        ${libjpeg_build_DIR}
)

# build LibVNCServer
message("------LibVNCServer-----")
set(libvnc_src_DIR ${CMAKE_SOURCE_DIR}/../../../../libvncserver)
set(libvnc_build_DIR ${CMAKE_BINARY_DIR}/libvnc)
# adds a source subdir with specifying a build output dir
add_subdirectory(${libvnc_src_DIR} ${libvnc_build_DIR})
# need to add the build dir to include dirs as well because of generated rfbconfig.h
include_directories(
        ${libvnc_src_DIR}/include
        ${libvnc_build_DIR}/include
)

# build libdroidvnc-ng
add_library(droidvnc-ng SHARED droidvnc-ng.c)
target_link_libraries(droidvnc-ng
    log
    vncserver)

