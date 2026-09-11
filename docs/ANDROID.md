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
./scripts/build-android.sh                     # debug APK
./scripts/build-android.sh --release           # release APK (debug-signed)
./scripts/build-android.sh --install           # build + adb install
./scripts/build-android.sh --xex ~/default.xex # re-run codegen from your XEX
./scripts/build-android.sh --turnip driver.zip # bundle a Turnip GPU driver
```

`--xex` accepts a `default.xex` or a whole game **ISO** (it gets extracted on
the way) and re-runs the ReXGlue codegen locally before the APK build, so the
APK is compiled from *your* disc's freshly generated code — real PPC fences
and page-granularity handling included. Requires the desktop build
prerequisites (clang, ninja, and the dev packages the desktop CI installs).

`--turnip` accepts a Turnip driver ZIP (any `libvulkan*.so` inside) or a bare
`.so`, and packages it into the APK (see [GPU drivers](#gpu-drivers-turnip)).

CI: `.github/workflows/build-android.yml` builds a debug APK on every push/PR
and uploads it as an artifact — no game dump needed, everything compiles from
the repository (vendored SDK + committed generated code).

Manual workflow runs (Actions tab → *Android build* → *Run workflow*) accept:

* **game_url** — a direct URL to your own `default.xex` or the game ISO. The
  workflow extracts the XEX (if needed), builds the ReXGlue codegen CLI and
  regenerates the recompiled code from your binary before building the APK.
  Nothing game-related is published: the xex only ever lives inside that
  private workflow run, and the resulting APK still requires your own game
  data on the device.
* **turnip_url** — a ZIP with an arm64 Turnip driver; it is bundled into the
  APK as `libvulkan.turnip.so`.
* **build_type** — `debug` or `release`.

Toolchain pinned by Gradle: NDK `27.2.12479018`, CMake `3.31.1`, AGP `8.7.3`,
JDK 17, `minSdk 28`, `targetSdk 35`, `arm64-v8a` only.

## First launch (onboarding)

Two ways to install the game data, both from **your own legally-owned copy**:

1. **On the phone (no PC needed):** copy the game ISO anywhere into shared
   storage (e.g. `Download/`), launch the app → **Install from ISO…** → pick
   the ISO. The bundled `extract-xiso` (running natively in `libxiso.so`)
   extracts it to `/storage/emulated/0/SimpsonsGame/gamedata` — a few minutes
   and ~5 GB of free space. **That's the whole "drop the ISO and play" flow.**
2. **Pre-extracted folder:** copy the extracted game folder to the phone
   (e.g. `Download/SimpsonsGame` with `default.xex` inside) → **Choose game
   folder** → pick it.

Either way, grant "All files access" when asked (Android 11+): the app reads
the folder *in place* — nothing is copied or modified. On exotic storage
providers where no real path can be resolved, the folder flow offers to copy
the files into private storage instead.

Press **Play**.

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

## Graphics settings

The gear button → **Graphics…** (or the Graphics button on the setup screen)
opens the graphics dialog:

| Setting | What it does |
|---|---|
| GPU driver | System (vendor) or Turnip — installable from a ZIP right in the app (see below); a driver bundled into the APK works as a fallback. |
| VSync | Off also allows the tearing-capable present modes (`immediate`/`fifo_relaxed`) — uncapped frame rate at the cost of tearing. |
| Internal resolution | `resolution_scale` 1x/2x/3x. 2x/3x render the 360 framebuffer larger and downscale — sharper, but only for flagship GPUs. |
| Anti-aliasing | `swap_post_effect`: off / FXAA / FXAA (strong) — post-process AA on present. |
| Frame limit | `video_mode_refresh_rate` 60 or 30 — 30 saves battery and stabilizes weaker phones. |
| Letterbox | `present_letterbox` — keep 16:9 with bars (on) or stretch to fill the screen (off). |
| Anisotropic filtering | `anisotropic_override` — texture sharpness at grazing angles. |

Settings are translated into runtime cvar command-line tokens
(`graphics_args.txt`, spliced into argv by `android_main.cpp` before
`rex::cvar::Init`) and applied at the **next game start**, exactly like the
desktop launcher (these configure swapchain/pipeline state built once at
startup). Defaults emit no tokens at all — a default launch is
argv-identical to a settings-free build.

## GPU drivers (Turnip)

Many Adreno GPUs run noticeably better (or at all, on older Qualcomm driver
stacks) with **Turnip** — the open-source Mesa Freedreno Vulkan driver.

**Installing a driver needs no rebuild and no PC:** download any community
Turnip driver ZIP (arm64) onto the phone, then in the app: Graphics →
**Install Turnip from ZIP…** → pick the ZIP. The driver is unpacked into the
app's internal storage and becomes selectable in the same dialog (restart the
game to load it). A bare `libvulkan*.so` file works too.

How it works under the hood: Android forbids `dlopen()`ing libraries from
app-writable storage, so the runtime loads the installed driver through
**libadrenotools** (vendored in the SDK, BSD-2-Clause — the same rootless
driver-loading approach used by yuzu/skyline): the `vulkan_driver_path` cvar
points at the unpacked `.so`, and `VulkanInstance::Create` opens it via a
linker-namespace bypass that redirects the system Vulkan loader's driver
enumeration to it. If the driver fails to open, the runtime logs an error and
**falls back to the system driver** instead of dying.

An alternative (mostly for CI/distribution) is bundling a driver into the APK
at build time: `scripts/build-android.sh --turnip <zip-or-so>` or the
`turnip_url` input of the manual CI workflow — it is packaged as
`lib/arm64-v8a/libvulkan.turnip.so` and loaded through `vulkan_loader_path`.
An installed driver always takes precedence over the bundled one.

If the game fails to start with Turnip selected, switch back to *System* — a
Turnip build that predates your GPU may be missing required extensions.
Vendors' system drivers remain fully supported.

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
  backend) + `libc++_shared.so` + `libxiso.so` (extract-xiso for on-device
  ISO install, loaded only by SetupActivity) + the tiny libadrenotools hooks
  (`libmain_hook.so`, `libhook_impl.so`, … — used to load player-installed
  Turnip drivers). Windowing is SDL3 (statically linked into the runtime),
  presenting through the activity's `ANativeWindow` via
  `VK_KHR_android_surface`. A bundled Turnip driver, when built in, ships as
  `libvulkan.turnip.so` in the same `lib/arm64-v8a/`; user-installed drivers
  live in the app's internal `files/drivers/turnip/` instead.
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
