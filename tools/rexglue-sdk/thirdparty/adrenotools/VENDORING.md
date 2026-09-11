# Vendored: libadrenotools

- Upstream: https://github.com/bylaws/libadrenotools
- Pinned commit: 8fae8ce254dfc1344527e05301e43f37dea2df80 (master, 2026)
- License: BSD 2-Clause (libadrenotools and the bundled liblinkernsbypass;
  see LICENSE and lib/linkernsbypass/LICENSE). Upstream README kept as
  README.md for reference.

Local changes (keep minimal, list every one here):
- Root CMakeLists.txt: `if(NOT ${CMAKE_ANDROID_ARCH_ABI} ...)` quoted to
  survive -Wdev policy warnings, and `if(${BUILD_SHARED_LIBS})` becomes
  `if(BUILD_SHARED_LIBS)` for the same reason. Everything else is verbatim
  upstream, including src/hook/ and lib/linkernsbypass/ subdirectories.

Why it is here: rootless loading of user-installed Adreno/Turnip Vulkan
drivers. Android forbids dlopen()ing libraries from app-writable storage;
adrenotools works around that with a linker-namespace bypass + a patched
(memfd) copy of the system libvulkan loader whose driver enumeration is
hooked to the custom driver. Used by VulkanInstance::Create when the
`vulkan_driver_path` cvar points at a driver in the app's internal files
dir (installed from a ZIP by the Android launcher's UI).

Requirements honored by the app build:
- `useLegacyPackaging = true` (hooks must be extracted to nativeLibraryDir)
- the hook libraries (libmain_hook.so etc.) are built as SHARED targets here
  and get packaged into the APK automatically by AGP
- arm64-v8a only (linkernsbypass is aarch64-specific)
