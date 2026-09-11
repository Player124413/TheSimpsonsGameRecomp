cmake_minimum_required(VERSION 3.25)
project(simpsons_android LANGUAGES C CXX ASM)

# --- Paths handed over from Gradle (with sane fallbacks for direct CMake use) --
set(REX_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." CACHE PATH
    "TheSimpsonsGameRecomp repository root")
set(REXSDK_DIR "${REX_PROJECT_ROOT}/tools/rexglue-sdk" CACHE PATH
    "ReXGlue SDK tree (vendored in this repository)")
set(REX_APP_NAME "simpsons" CACHE STRING "Recompiled app identifier")

if(NOT EXISTS "${REXSDK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "ReXGlue SDK not found at ${REXSDK_DIR}\n"
        "The SDK is vendored in the repository tree (tools/rexglue-sdk).")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The profiler client ships as a shared library that has no place in an APK.
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# Vulkan is the only graphics backend on Android.
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)

#--- Link-time optimization (ThinLTO) ------------------------------------------
# The recompiled guest code is a monolith of small functions calling into the
# runtime's memory access helpers on every guest load/store; cross-module
# inlining of those helpers (plus dead-code stripping of the huge SDK surface)
# is worth 5-15% on recompilation workloads. NDK r27 links with lld, which
# consumes ThinLTO bitcode natively. Cost: slower builds and more link RAM on
# CI. Applied to every target defined after this point (SDK, recompiled code,
# app) for Release builds only.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
  set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
endif()

#--- SDK (vendored tree, with the Android platform support) --------------------
add_subdirectory("${REXSDK_DIR}" rexglue-sdk)

#--- Generated recompiled code --------------------------------------------------
set(REX_GEN_DIR "${REX_PROJECT_ROOT}/simpsons/generated/default")
if(NOT EXISTS "${REX_GEN_DIR}/sources.cmake")
    message(FATAL_ERROR
        "generated/default/sources.cmake is missing.\n"
        "The repository ships pre-generated code (simpsons/generated).")
endif()
include("${REX_GEN_DIR}/sources.cmake")

# rexglue bakes absolute paths into sources.cmake (codegen-time
# CMAKE_CURRENT_LIST_DIR); normalize in case a relative form ever appears.
set(REX_GEN_SOURCES "")
foreach(src ${GENERATED_SOURCES})
    if(IS_ABSOLUTE "${src}")
        list(APPEND REX_GEN_SOURCES "${src}")
    else()
        list(APPEND REX_GEN_SOURCES "${REX_GEN_DIR}/${src}")
    endif()
endforeach()

add_library(recomp STATIC ${REX_GEN_SOURCES}
    "${REX_PROJECT_ROOT}/simpsons/src/draw_telemetry.cpp"
)
target_include_directories(recomp PRIVATE
    "${REX_PROJECT_ROOT}/simpsons"
    "${REX_PROJECT_ROOT}/simpsons/src"
    "${REX_GEN_DIR}"
)
target_compile_options(recomp PRIVATE
    $<$<CXX_COMPILER_ID:Clang,AppleClang>:-gline-tables-only>
    -Wno-unused-variable
)
# The generated translation units are where nearly all guest CPU time goes.
# CMake's stock RelWithDebInfo compiles at -O2; -O3 lands after the per-config
# flags on the command line and wins. armv8-a baseline (set by the SDK for
# Android targets) runs on every 64-bit device.
target_compile_options(recomp PRIVATE
    $<$<CONFIG:RelWithDebInfo,Release>:-O3>
)
target_link_libraries(recomp PUBLIC rex::runtime)

#--- Android app library (loaded by SDLActivity via getLibraries()) ------------
add_library(main SHARED
    android_gamepad.cpp
    android_main.cpp
    "${REX_PROJECT_ROOT}/simpsons/src/main.cpp"
    "${REXSDK_DIR}/src/ui/rex_app.cpp"
)
target_include_directories(main PRIVATE
    "${REX_PROJECT_ROOT}/simpsons"
    "${REX_PROJECT_ROOT}/simpsons/src"
    "${REX_GEN_DIR}"
    "${REXSDK_DIR}/include"
    "${REXSDK_DIR}/src/ui"
)
target_compile_options(main PRIVATE -Wno-unused-variable)
# Same spelling/scope as the SDK root CMakeLists (add_compile_definitions is
# directory-scoped and does NOT reach targets created by this CMakeLists).
# rex_app.cpp expands REXGLUE_BUILD_TITLE -> REXGLUE_BUILD_CONFIG; without
# this the macro expands to a bare identifier and fails to parse.
target_compile_definitions(main PRIVATE REXGLUE_BUILD_CONFIG="$<CONFIG>")
# rex_app.cpp includes <imgui.h> (console overlay). imgui is a thirdparty
# OBJECT library whose interface include dir isn't propagated through
# rex::runtime, so add it explicitly (mirrors rexglue_configure_target).
# rexui/windowing code stays ONLY inside librexruntime.so (the SDK's kernel
# CMake links the rexui objects into it) - exactly like the desktop build,
# where the host executable resolves those symbols from the runtime .so: no
# duplicated C++ objects, no cross-.so RTTI hazards.
if(TARGET imgui)
    target_include_directories(main PRIVATE
        $<TARGET_PROPERTY:imgui,INTERFACE_INCLUDE_DIRECTORIES>)
endif()
target_link_libraries(main PRIVATE recomp rex::runtime fmt::fmt)
# The runtime resolves PPCImageConfig / PPCFuncMappings and the app creator
# registration from this library (nativeRunMain dlopens it RTLD_GLOBAL).
target_link_options(main PRIVATE -Wl,--export-dynamic)

# Keep the output names stable for the runtime library loader (lib prefix
# already added by CMake for SHARED libs).
set_target_properties(main PROPERTIES
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)

#--- On-device ISO installer -----------------------------------------------------
# extract-xiso (the desktop launcher's install backend) compiled for Android
# with its CLI entry point renamed, plus a JNI wrapper (SetupActivity's
# "Install from ISO" flow). A separate tiny .so: it shares nothing with the
# game runtime and stays out of libmain.so's link graph.
set(XISO_C "${REX_PROJECT_ROOT}/tools/extract-xiso/extract-xiso.c")
if(NOT EXISTS "${XISO_C}")
    message(FATAL_ERROR "extract-xiso source not found: ${XISO_C}")
endif()
add_library(xiso SHARED
    xiso_jni.cpp
    "${XISO_C}"
)
# Rename ONLY the C file's main(): the preprocessor token replace must not
# touch the C++ wrapper. __LINUX__ selects extract-xiso's Linux branch: the
# tool's own CMakeLists derives it from CMAKE_SYSTEM_NAME (which would give
# the nonexistent __ANDROID__ branch -> #error), and bionic provides every
# symbol that branch needs (lseek64/stat64/off64_t/O_LARGEFILE/asprintf via
# the file's own _GNU_SOURCE). __ANDROID__ comes from the NDK automatically.
set_source_files_properties("${XISO_C}" PROPERTIES
    COMPILE_DEFINITIONS "main=extract_xiso_main;__LINUX__"
)
# Mixed C + C++ sources: CMake picks the C++ linker, so the JNI wrapper gets
# libc++_shared like every other target. extract-xiso itself needs only libc.
