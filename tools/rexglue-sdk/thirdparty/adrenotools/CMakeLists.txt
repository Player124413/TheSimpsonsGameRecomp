# Vendored libadrenotools (Billy Laws, BSD-2-Clause) + liblinkernsbypass.
# Upstream: https://github.com/bylaws/libadrenotools @ 8fae8ce254dfc1344527e05301e43f37dea2df80
#
# Rootless custom GPU driver loading for Adreno GPUs (user-installed Turnip
# builds). The app-side flow: a driver ZIP is unpacked into the app's INTERNAL
# files dir by the Java launcher, the runtime passes the .so path through the
# `vulkan_driver_path` cvar, and VulkanInstance::Create opens it via
# adrenotools_open_libvulkan (see src/ui/vulkan/vulkan_instance.cpp).
#
# What gets built here:
#   - linkernsbypass (static): linker-namespace escape + ELF soname patcher
#   - adrenotools (static): driver loading entry points
#   - libmain_hook.so / libhook_impl.so / libfile_redirect_hook.so /
#     libgsl_alloc_hook.so: tiny hook libraries that must be PACKAGED in the
#     APK (they land in nativeLibraryDir, which is what hookLibDir must point
#     at) and require `useLegacyPackaging = true` on the app.
#
# Only added on ANDROID builds (see ../CMakeLists.txt); arm64-v8a only.

cmake_minimum_required(VERSION 3.14)

if(NOT "${CMAKE_ANDROID_ARCH_ABI}" STREQUAL "arm64-v8a")
	message(FATAL_ERROR "Unsupported target architecture: ${CMAKE_ANDROID_ARCH_ABI}. Please make an issue on the repo!")
endif()

project(adrenotools LANGUAGES CXX C)

set(GEN_INSTALL_TARGET OFF CACHE BOOL "")

add_subdirectory(lib/linkernsbypass)

set(LIB_SOURCES src/bcenabler.cpp
                src/driver.cpp
                include/adrenotools/bcenabler.h
                include/adrenotools/driver.h
                include/adrenotools/priv.h)

add_library(adrenotools ${LIB_SOURCES})

target_include_directories(adrenotools PUBLIC include)
target_include_directories(adrenotools PRIVATE .)
target_compile_options(adrenotools PRIVATE -Wall -Wextra)
target_link_libraries(adrenotools android linkernsbypass)

add_subdirectory(src/hook)
if(BUILD_SHARED_LIBS)
    target_link_options(adrenotools PRIVATE "-Wl,-s,--exclude-libs,liblinkernsbypass.a")
endif()
