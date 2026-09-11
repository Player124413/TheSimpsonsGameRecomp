# Android port (arm64-v8a)

This directory builds **The Simpsons Game (recomp) for Android** — a playable
 APK containing the recompiled Xbox 360 game code, a Vulkan (Xenos) renderer
 and a full on-screen gamepad with a layout editor.

The APK never contains the game *data* — only the recompiled *code*. On first
launch the app asks for the folder with your own extracted game files
(`default.xex` + data), the same set the desktop launcher produces.

## Requirements

* A 64-bit Android phone (**arm64-v8a**), Android **9 (API 28)** or newer.
* A GPU with a full Vulkan 1.1 implementation (`android.hardware.vulkan.version`
  0x00400003). Practically: Snapdragon 835/Adreno 540 class or newer, or a
  comparable Mali/PowerVR part. Mid-range devices from ~2018 onward run the
  game; older chipsets may lack the required Vulkan features.
* ~4 GB of free space for the game files themselves.

## Building

```bash
# once: install the Android SDK bits (see scripts/build-android.sh header)
./scripts/build-android.sh            # debug APK
./scripts/build-android.sh --release  # release (unsigned) APK
./scripts/build-android.sh --install  # build + adb install
```

CI: `.github/workflows/build-android.yml` builds a debug APK on every push/PR
and uploads it as an artifact — no game dump needed, everything compiles from
the repository (vendored SDK + committed generated code).

Toolchain pinned by Gradle: NDK `27.2.12479018`, CMake `3.31.1`, AGP `8.7.3`,
JDK 17, `minSdk 28`, `targetSdk 35`, `arm64-v8a` only.

## First launch (onboarding)

1. Copy the extracted game folder to the phone (e.g.
   `Download/SimpsonsGame` with `default.xex` inside).
2. Launch the app → **Choose game folder** → pick that folder.
3. Grant "All files access" when asked (Android 11+): the app reads the folder
   *in place* — nothing is copied or modified. On exotic storage providers
   where no real path can be resolved, the app offers to copy the files into
   its private storage instead.
4. Press **Play**.

Logs are written to `/storage/emulated/0/SimpsonsGame/logs/simpsons.log`
(shared storage, when the all-files grant is in place) or to the app's
external files dir, and mirrored to logcat (tag `simpsons`).

## Touch controls

The on-screen gamepad is an SDL3 *virtual gamepad* — the game sees a real
Xbox 360 controller, so every screen (menus, QTEs, minigames) works.

* **⚙ button** (bottom-center) — touch control settings:
  * **On-screen gamepad enabled** — master switch. Turn it off to play with
    a physical Bluetooth/USB controller only; the overlay disappears
    completely and never intercepts touches.
  * **Button size** — global scale of every control.
  * **Opacity** — overlay transparency.
  * **Edit layout** — the layout editor (see below).
  * **Reset layout** — restore the default layout.
* Physical controllers work simultaneously (each is a separate SDL gamepad).

### Layout editor (Edit mode)

Inside the editor:

* **Move**: drag any button/stick/D-pad to a new position.
* **Resize**: select a control, then **+/−** (toolbar) or pinch with two
  fingers on it. Range 50–200 %.
* **Visibility**: tap the **eye badge** on any control (or the **eye** toolbar
  button for the selected one) to hide/show it. Hidden controls are drawn
  tinted in the editor and never render or accept touches in-game.
* **Done** — exit the editor.

All changes save immediately (survive restarts) and are stored **normalized**
(0–1 of the screen), so your layout keeps its proportions in landscape
rotations and on other devices.

The editor toolbar sits top-center: `Done | − | + | eye`, with a hint line
under it.

## Architecture notes

```
android/
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt      # app build: SDK + recomp + libmain.so
│   │   ├── android_main.cpp    # SDL_main -> ReXGlue app bootstrap
│   │   └── android_gamepad.cpp # JNI -> SDL3 virtual gamepad
│   ├── java/com/yestermester/simpsons/
│   │   ├── MainActivity.java   # SDLActivity + overlay + content:// bridge
│   │   ├── SetupActivity.java  # onboarding (SAF picker, permission flows)
│   │   ├── GameFiles.java      # game_root.txt handling
│   │   └── gamepad/
│   │       ├── VirtualPadView.java  # multi-touch overlay + layout editor
│   │       ├── PadGeometry.java     # normalized layout engine (pure math)
│   │       ├── PadSettings.java     # settings + JSON persistence
│   │       ├── PadSettingsDialog.java
│   │       ├── PadInputBridge.java  # JNI declarations
│   │       └── PadTheme.java        # paints/palette (no allocations in onDraw)
│   ├── java/org/libsdl/app/    # SDL3 Java glue (copied from the vendored SDL3)
│   └── res/                    # strings (en + ru), icons, styles
└── tools/make_icon.py          # regenerates the launcher icons
```

Key facts for maintainers:

* **Libraries**: `libmain.so` (app entry, recompiled game code, app creator
  registration) + `librexruntime.so` (ReXGlue runtime incl. the Vulkan Xenos
  backend) + `libc++_shared.so`. Windowing is SDL3 (statically linked into
  the runtime), presenting through the activity's `ANativeWindow` via
  `VK_KHR_android_surface`.
* **SDK Android support** lives in the vendored tree
  (`tools/rexglue-sdk`): JNI glue (`src/core/android_runtime.cpp`), bionic
  ucontext (`src/core/ucontext_android.cpp`), logcat sink, SDL window
  backend, `surface_android`, bionic-compat guards, plus the mobile perf
  fixes (interrupt dispatch lock scope, `/proc/self/maps` fault-path cache,
  log flush policy).
* **Performance defaults**: the app launches with
  `--clear_memory_page_state=false` (the write-watch mechanism keeps
  CPU/GPU coherency; re-invalidating every page per frame collapses mobile
  frame rates to ~1 FPS).
* **Memory ordering**: PPC `sync/lwsync/eieio` now emit real host fences in
  the codegen builders, so *regenerated* code is correctly ordered on
  weakly-ordered ARM64 hosts. The *committed* generated code predates that
  fix (barriers were emitted as no-ops — safe on x86-TSO only). The paths
  that actually need cross-thread ordering in this game are still safe on
  ARM64 without them: guest atomics (`lwarx/stwcx`) compile to
  `__sync_bool_compare_and_swap` (full barrier on AArch64), kernel
  synchronization primitives use host `std::mutex`/condvars, and the GPU
  ring write-pointer update goes through the MMIO path under the global
  lock. The residual exposure is limited to game-internal plain-memory flag
  handshakes without atomics — the same exposure every pre-fence recomp
  port shipped with. Regenerating the code from your `default.xex` with the
  updated SDK removes even that.
* **REX_PHYS_HOST_OFFSET** resolves through a runtime variable on Android
  so 16 KB-page devices (Pixel 8+ with 16 KB kernels) get the same 0x1000
  skew Windows uses; desktop builds keep their compile-time constants.
* **Gradle**: `useLegacyPackaging = true` (the loader resolves
  `libmain.so` by absolute path from `nativeLibraryDir`).

## Troubleshooting

* **Black screen / instant exit** — check logcat (`adb logcat -s simpsons`)
  or the log file; the most common cause is a Vulkan driver missing required
  features (the manifest already filters those devices out of Play
  distribution, but sideloading bypasses that).
* **"default.xex not found"** — the picked folder must directly contain
  `default.xex`; if you copied the ISO itself, extract it first (the desktop
  launcher's Install tab does this).
* **Stuck buttons after leaving the app** — the overlay releases all input
  on pause; if you see a stuck button, tap the affected control once.
