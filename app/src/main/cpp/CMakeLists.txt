
cmake_minimum_required(VERSION 3.4.1)

add_link_options("LINKER:--build-id=none")

project (droidVNC-NG C)

include(ExternalProject)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared Libs" FORCE)

# Required to enable SIMD support on ARM
if (CMAKE_ANDROID_ARCH STREQUAL "arm64")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=aarch64-linux-android${ANDROID_NATIVE_API_LEVEL}")
elseif (CMAKE_ANDROID_ARCH STREQUAL "arm")
    set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} --target=arm-linux-androideabi${ANDROID_NATIVE_API_LEVEL}")
endif ()

# libjpeg-turbo
ExternalProject_Add(ep-libjpeg-turbo
        SOURCE_DIR ${CMAKE_SOURCE_DIR}/../../../../libjpeg-turbo
        BINARY_DIR ${CMAKE_BINARY_DIR}/libjpeg
        INSTALL_DIR ${CMAKE_BINARY_DIR}/libjpeg-install
        BUILD_BYPRODUCTS <INSTALL_DIR>/lib/libturbojpeg.a
        CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_POLICY_DEFAULT_CMP0057=NEW # needed for IN_LIST
        -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
        -DCMAKE_ASM_FLAGS=${CMAKE_ASM_FLAGS} # as per https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/BUILDING.md#building-libjpeg-turbo-for-android
        -DENABLE_SHARED=OFF
)
# set these variables so FindJPEG can find the library
ExternalProject_Get_property(ep-libjpeg-turbo INSTALL_DIR)
set(JPEG_LIBRARY "${INSTALL_DIR}/lib/libturbojpeg.a" CACHE FILEPATH "")
# LibVNC also does a test for libjpeg-turbo by looking at headers, in the configure phase these
# are only available in its source dir.
ExternalProject_Get_property(ep-libjpeg-turbo SOURCE_DIR)
set(JPEG_INCLUDE_DIR ${SOURCE_DIR}  CACHE PATH "")
# But: there are also generated headers needed in the build phase.
include_directories(${INSTALL_DIR}/include)

# build LibVNCServer
message("------LibVNCServer-----")
set(libvnc_src_DIR ${CMAKE_SOURCE_DIR}/../../../../libvncserver)
set(libvnc_build_DIR ${CMAKE_BINARY_DIR}/libvnc)
# adds a source subdir with specifying a build output dir
add_subdirectory(${libvnc_src_DIR} ${libvnc_build_DIR})
add_dependencies(vncserver ep-libjpeg-turbo)
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

