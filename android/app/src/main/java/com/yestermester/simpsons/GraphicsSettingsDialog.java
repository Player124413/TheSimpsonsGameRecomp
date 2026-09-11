package com.yestermester.simpsons;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * Graphics settings: GPU driver (system vs Turnip, including installing a
 * Turnip driver from a ZIP the player downloaded), vertical sync, internal
 * resolution scale, post-processing AA, frame limiter, letterboxing and
 * anisotropic filtering.
 *
 * All values translate into runtime cvar overrides written by
 * {@link GraphicsSettings#writeLaunchArgs(Context)} and are applied at the
 * next game start (they configure swapchain/pipeline state that the runtime
 * builds once at startup - the desktop launcher restarts the game for the
 * same options).
 *
 * Programmatic UI (no layout XML), same style as PadSettingsDialog.
 */
public final class GraphicsSettingsDialog {

    private GraphicsSettingsDialog() {
    }

    /**
     * Refresh hook for the driver section, set while a dialog is showing.
     * Activities forward the Turnip picker result here so an install started
     * from the dialog updates it live.
     */
    private static Runnable sDriverRefresh;

    /** Call from an activity's onActivityResult for {@link TurnipDriver#REQUEST_PICK}. */
    public static void handleDriverPickResult(android.app.Activity activity, int resultCode,
                                              Intent data) {
        TurnipDriver.handlePickResult(activity, resultCode, data, sDriverRefresh);
    }

    /** Shows the graphics settings dialog. */
    public static void show(Context context) {
        final GraphicsSettings s = GraphicsSettings.get(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        // --- GPU driver -----------------------------------------------------
        root.addView(label(context, R.string.gfx_driver));
        final RadioGroup driver = new RadioGroup(context);
        final RadioButton system = new RadioButton(context);
        system.setText(R.string.gfx_driver_system);
        system.setId(View.generateViewId());
        driver.addView(system);
        final RadioButton turnip = new RadioButton(context);
        turnip.setText(R.string.gfx_driver_turnip);
        turnip.setId(View.generateViewId());
        driver.addView(turnip);
        root.addView(driver);

        // Live status of the Turnip side: which driver would actually be used.
        final TextView driverStatus = new TextView(context);
        driverStatus.setTextSize(12);
        driverStatus.setPadding((int) (34 * context.getResources().getDisplayMetrics().density),
                0, 0, 0);
        root.addView(driverStatus);

        // Install a driver from a ZIP the player downloaded (e.g. a community
        // Turnip build): unpacks into app-internal storage, no rebuild needed.
        final android.widget.Button installDriver = new android.widget.Button(context);
        installDriver.setText(R.string.gfx_driver_install);
        root.addView(installDriver);

        // Remove the installed driver (falls back to bundled/system).
        final android.widget.Button removeDriver = new android.widget.Button(context);
        removeDriver.setText(R.string.gfx_driver_remove);
        root.addView(removeDriver);

        final Runnable refreshDriverState = () -> {
            String installed = TurnipDriver.installedSoName(context);
            boolean bundled = GraphicsSettings.turnipAvailable(context);
            boolean usable = installed != null || bundled;
            turnip.setEnabled(usable);
            if (installed != null) {
                driverStatus.setText(
                        context.getString(R.string.gfx_driver_status_installed, installed));
            } else if (bundled) {
                driverStatus.setText(R.string.gfx_driver_status_bundled);
            } else {
                driverStatus.setText(R.string.gfx_driver_status_none);
            }
            removeDriver.setVisibility(
                    TurnipDriver.isInstalled(context) ? View.VISIBLE : View.GONE);
            if (!usable) {
                system.setChecked(true);
            }
        };
        refreshDriverState.run();
        if (GraphicsSettings.DRIVER_TURNIP.equals(s.driver()) && turnip.isEnabled()) {
            turnip.setChecked(true);
        } else {
            system.setChecked(true);
        }

        installDriver.setOnClickListener(v -> {
            if (context instanceof android.app.Activity) {
                TurnipDriver.startPicker((android.app.Activity) context);
            }
        });
        removeDriver.setOnClickListener(v -> {
            TurnipDriver.remove(context);
            refreshDriverState.run();
        });

        // --- VSync ------------------------------------------------------------
        final CheckBox vsync = new CheckBox(context);
        vsync.setText(R.string.gfx_vsync);
        vsync.setTextSize(15);
        vsync.setChecked(s.vsync());
        root.addView(vsync);

        // --- Internal resolution ----------------------------------------------
        root.addView(label(context, R.string.gfx_resolution));
        final RadioGroup resolution = radioRow(context);
        final int[] resIds = new int[3];
        String[] resLabels = {"1x", "2x", "3x"};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setText(resLabels[i]);
            resIds[i] = View.generateViewId();
            rb.setId(resIds[i]);
            resolution.addView(rb);
        }
        resolution.check(resIds[s.resolutionScale() - 1]);
        root.addView(resolution);
        root.addView(hint(context, R.string.gfx_resolution_hint));

        // --- Post-processing AA ------------------------------------------------
        root.addView(label(context, R.string.gfx_post));
        final RadioGroup post = radioRow(context);
        final int[] postIds = new int[3];
        String[] postLabels = {
                context.getString(R.string.gfx_post_none),
                "FXAA",
                "FXAA " + context.getString(R.string.gfx_post_strong),
        };
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setText(postLabels[i]);
            postIds[i] = View.generateViewId();
            rb.setId(postIds[i]);
            post.addView(rb);
        }
        String currentPost = s.postEffect();
        post.check(currentPost.equals(GraphicsSettings.POST_FXAA) ? postIds[1]
                : currentPost.equals(GraphicsSettings.POST_FXAA_EXTREME) ? postIds[2]
                : postIds[0]);
        root.addView(post);

        // --- Frame limiter ------------------------------------------------------
        root.addView(label(context, R.string.gfx_fps));
        final RadioGroup fps = radioRow(context);
        final int fps60 = View.generateViewId();
        final int fps30 = View.generateViewId();
        RadioButton rb60 = new RadioButton(context);
        rb60.setText("60");
        rb60.setId(fps60);
        fps.addView(rb60);
        RadioButton rb30 = new RadioButton(context);
        rb30.setText("30");
        rb30.setId(fps30);
        fps.addView(rb30);
        fps.check(s.refreshRate() == 30 ? fps30 : fps60);
        root.addView(fps);
        root.addView(hint(context, R.string.gfx_fps_hint));

        // --- Letterbox -----------------------------------------------------------
        final CheckBox letterbox = new CheckBox(context);
        letterbox.setText(R.string.gfx_letterbox);
        letterbox.setTextSize(15);
        letterbox.setChecked(s.letterbox());
        root.addView(letterbox);

        // --- Anisotropic filtering ----------------------------------------------
        root.addView(label(context, R.string.gfx_aniso));
        final RadioGroup aniso = radioRow(context);
        final int[] anisoValues = {0, 2, 4, 8, 16};
        final int[] anisoIds = new int[anisoValues.length];
        for (int i = 0; i < anisoValues.length; i++) {
            RadioButton rb = new RadioButton(context);
            rb.setText(anisoValues[i] == 0
                    ? context.getString(R.string.gfx_aniso_default)
                    : anisoValues[i] + "x");
            anisoIds[i] = View.generateViewId();
            rb.setId(anisoIds[i]);
            aniso.addView(rb);
        }
        int anisoChecked = 0;
        for (int i = 0; i < anisoValues.length; i++) {
            if (s.anisotropic() == anisoValues[i]) {
                anisoChecked = i;
                break;
            }
        }
        aniso.check(anisoIds[anisoChecked]);
        root.addView(aniso);

        // --- Footer note ----------------------------------------------------------
        root.addView(hint(context, R.string.gfx_restart_note));

        sDriverRefresh = refreshDriverState;
        new AlertDialog.Builder(context)
                .setTitle(R.string.gfx_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (turnip.isEnabled() && turnip.isChecked()) {
                        s.setDriver(GraphicsSettings.DRIVER_TURNIP);
                    } else {
                        s.setDriver(GraphicsSettings.DRIVER_SYSTEM);
                    }
                    s.setVsync(vsync.isChecked());
                    for (int i = 0; i < 3; i++) {
                        if (resolution.getCheckedRadioButtonId() == resIds[i]) {
                            s.setResolutionScale(i + 1);
                        }
                    }
                    if (post.getCheckedRadioButtonId() == postIds[1]) {
                        s.setPostEffect(GraphicsSettings.POST_FXAA);
                    } else if (post.getCheckedRadioButtonId() == postIds[2]) {
                        s.setPostEffect(GraphicsSettings.POST_FXAA_EXTREME);
                    } else {
                        s.setPostEffect(GraphicsSettings.POST_NONE);
                    }
                    s.setRefreshRate(fps.getCheckedRadioButtonId() == fps30 ? 30 : 60);
                    s.setLetterbox(letterbox.isChecked());
                    for (int i = 0; i < anisoValues.length; i++) {
                        if (aniso.getCheckedRadioButtonId() == anisoIds[i]) {
                            s.setAnisotropic(anisoValues[i]);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (sDriverRefresh == refreshDriverState) {
                        sDriverRefresh = null;
                    }
                })
                .show();
    }

    // --- Small programmatic widgets ---------------------------------------------

    private static TextView label(Context c, int textRes) {
        TextView tv = new TextView(c);
        tv.setText(textRes);
        tv.setTextSize(14);
        tv.setPadding(0, (int) (14 * c.getResources().getDisplayMetrics().density), 0,
                (int) (4 * c.getResources().getDisplayMetrics().density));
        return tv;
    }

    private static TextView hint(Context c, int textRes) {
        TextView tv = new TextView(c);
        tv.setText(textRes);
        tv.setTextSize(12);
        tv.setPadding((int) (8 * c.getResources().getDisplayMetrics().density), 0, 0,
                (int) (4 * c.getResources().getDisplayMetrics().density));
        return tv;
    }

    private static RadioGroup radioRow(Context c) {
        RadioGroup rg = new RadioGroup(c);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        return rg;
    }
}
