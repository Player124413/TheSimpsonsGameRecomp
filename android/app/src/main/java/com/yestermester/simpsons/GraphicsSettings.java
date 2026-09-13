package com.yestermester.simpsons;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphics / GPU settings, translated into runtime cvar command-line tokens
 * at launch.
 *
 * The native entry point (android_main.cpp) reads {@code graphics_args.txt}
 * from the app's external files dir and appends every line to its argv before
 * rex::cvar::Init() runs, so each token here is a plain cvar override
 * ({@code --name=value}). Unknown or malformed tokens are ignored by the
 * cvar parser, which keeps this file forward/backward compatible with
 * runtime versions that may not know every cvar.
 *
 * Most of these are "restart to apply" on desktop too (they change swapchain
 * or GPU pipeline state), so applying them at process start - rather than
 * through live cvar writes - matches the desktop launcher's behaviour and
 * avoids hot-swap edge cases entirely.
 */
public final class GraphicsSettings {

    private static final String PREFS = "graphics";

    // GPU driver selection.
    public static final String DRIVER_SYSTEM = "system";
    public static final String DRIVER_TURNIP = "turnip";
    /** Bundled Turnip driver library name (packaged via jniLibs by CI/build). */
    public static final String TURNIP_LIB = "libvulkan.turnip.so";

    // Post-processing anti-aliasing.
    public static final String POST_NONE = "none";
    public static final String POST_FXAA = "fxaa";
    public static final String POST_FXAA_EXTREME = "fxaa_extreme";

    private final SharedPreferences prefs;

    private GraphicsSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static GraphicsSettings get(Context context) {
        return new GraphicsSettings(context);
    }

    // --- Values -----------------------------------------------------------------

    /** Selected GPU driver: {@link #DRIVER_SYSTEM} or {@link #DRIVER_TURNIP}. */
    public String driver() {
        return DRIVER_TURNIP.equals(prefs.getString("driver", DRIVER_SYSTEM))
                ? DRIVER_TURNIP : DRIVER_SYSTEM;
    }

    public void setDriver(String driver) {
        prefs.edit().putString("driver", DRIVER_TURNIP.equals(driver)
                ? DRIVER_TURNIP : DRIVER_SYSTEM).apply();
    }

    /** True when a Turnip driver library is actually packaged in this APK. */
    public static boolean turnipAvailable(Context context) {
        try {
            File dir = new File(context.getApplicationInfo().nativeLibraryDir);
            return new File(dir, TURNIP_LIB).isFile();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean vsync() {
        return prefs.getBoolean("vsync", true);
    }

    public void setVsync(boolean on) {
        prefs.edit().putBoolean("vsync", on).apply();
    }

    /** Internal render resolution scale multiplier (1 = native 1x). */
    public int resolutionScale() {
        int v = prefs.getInt("resolution_scale", 1);
        return (v >= 1 && v <= 3) ? v : 1;
    }

    public void setResolutionScale(int scale) {
        prefs.edit().putInt("resolution_scale", (scale >= 1 && scale <= 3) ? scale : 1).apply();
    }

    /** Post-processing anti-aliasing effect applied on present. */
    public String postEffect() {
        String v = prefs.getString("post_effect", POST_NONE);
        return POST_FXAA.equals(v) || POST_FXAA_EXTREME.equals(v) ? v : POST_NONE;
    }

    public void setPostEffect(String effect) {
        prefs.edit().putString("post_effect",
                POST_FXAA.equals(effect) || POST_FXAA_EXTREME.equals(effect)
                        ? effect : POST_NONE).apply();
    }

    /**
     * Target guest refresh rate (frame limiter): 60 (default) or 30 for
     * battery saving / low-end devices.
     */
    public int refreshRate() {
        return prefs.getInt("refresh_rate", 60) == 30 ? 30 : 60;
    }

    public void setRefreshRate(int hz) {
        prefs.edit().putInt("refresh_rate", hz == 30 ? 30 : 60).apply();
    }

    /** Keep the 16:9 picture letterboxed (true) instead of stretching to fill. */
    public boolean letterbox() {
        return prefs.getBoolean("letterbox", true);
    }

    public void setLetterbox(boolean on) {
        prefs.edit().putBoolean("letterbox", on).apply();
    }

    /**
     * Anisotropic filtering override. 0 = leave the runtime default; 1..16
     * forces a specific level (higher = sharper textures at grazing angles,
     * slightly more GPU work).
     */
    public int anisotropic() {
        int v = prefs.getInt("anisotropic", 0);
        return (v >= 0 && v <= 16) ? v : 0;
    }

    public void setAnisotropic(int v) {
        prefs.edit().putInt("anisotropic", (v >= 0 && v <= 16) ? v : 0).apply();
    }

    // --- Launch arguments ---------------------------------------------------------

    /**
     * Writes {@code graphics_args.txt} (one {@code --cvar=value} token per
     * line) for the native entry point to splice into argv. Only non-default
     * values emit tokens, so the default launch is argv-identical to the
     * pre-settings build. Must run before the SDL activity starts the native
     * thread (called from MainActivity.onCreate / SetupActivity.launchGame).
     */
    public static void writeLaunchArgs(Context context) {
        GraphicsSettings s = get(context);
        List<String> args = new ArrayList<>();

        // GPU driver: the player-installed Turnip driver (dropped into the app
        // as a ZIP, unpacked to internal storage) is loaded by the runtime
        // through libadrenotools; a driver BUNDLED in the APK (nativeLibraryDir
        // is dlopen-able directly) uses the plain loader path.
        if (DRIVER_TURNIP.equals(s.driver())) {
            File installed = TurnipDriver.installedSoPath(context);
            if (installed != null) {
                args.add("--vulkan_driver_path=" + installed.getAbsolutePath());
            } else if (turnipAvailable(context)) {
                File turnip =
                        new File(context.getApplicationInfo().nativeLibraryDir, TURNIP_LIB);
                args.add("--vulkan_loader_path=" + turnip.getAbsolutePath());
            }
        }

        // VSync. Disabling it also allows the tearing-capable present modes
        // (mirrors the desktop launcher's derived keys), or the swapchain
        // would stay FIFO anyway and the toggle would do nothing.
        if (!s.vsync()) {
            args.add("--vsync=false");
            args.add("--vulkan_allow_present_mode_immediate=true");
            args.add("--vulkan_allow_present_mode_fifo_relaxed=true");
        }

        if (s.resolutionScale() != 1) {
            args.add("--resolution_scale=" + s.resolutionScale());
        }

        if (!POST_NONE.equals(s.postEffect())) {
            args.add("--swap_post_effect=" + s.postEffect());
        }

        if (s.refreshRate() != 60) {
            args.add("--video_mode_refresh_rate=" + s.refreshRate());
        }

        if (!s.letterbox()) {
            args.add("--present_letterbox=false");
        }

        if (s.anisotropic() != 0) {
            args.add("--anisotropic_override=" + s.anisotropic());
        }

        if (s.showFps()) {
            args.add("--show_debug_overlay=true");
        }

        // Compatibility toggles for known game-specific rendering issues
        // (see the comments on the getters).
        if (s.compatMissingGeometry()) {
            args.add("--gpu_allow_null_optional_streams=true");
        }
        if (s.compatFlicker()) {
            args.add("--use_fuzzy_alpha_epsilon=true");
        }

        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                return;
            }
            File out = new File(external, "graphics_args.txt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                for (String arg : args) {
                    fos.write(arg.getBytes("UTF-8"));
                    fos.write('\n');
                }
            }
        } catch (Exception ignored) {
            // The native side treats a missing/unreadable file as "no
            // overrides" - defaults are safe.
        }
    }
}
