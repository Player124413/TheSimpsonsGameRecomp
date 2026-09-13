package com.yestermester.simpsons.gamepad;

import android.app.AlertDialog;
import android.content.Context;

import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.yestermester.simpsons.R;

/**
 * Touch-controls settings: master enable/disable, global button size,
 * overlay opacity, layout editor entry, layout reset. Programmatic UI (no
 * layout XML) so the dialog stays a single self-contained unit; every change
 * applies live through PadSettings (VirtualPadView picks up the generation
 * bump on its next draw pass).
 */
public final class PadSettingsDialog {

    private PadSettingsDialog() {
    }

    /** Shows the dialog on top of the game. */
    public static void show(Context context, final VirtualPadView pad) {
        final PadSettings settings = PadSettings.get(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padPx = (int) (20 * context.getResources().getDisplayMetrics().density);
        root.setPadding(padPx, padPx / 2, padPx, padPx / 2);

        // --- Enable / disable the whole on-screen gamepad ---------------------
        final CheckBox enabled = new CheckBox(context);
        enabled.setText(R.string.pad_enabled);
        enabled.setTextSize(16);
        enabled.setChecked(settings.enabled());
        root.addView(enabled);
        TextView hint = new TextView(context);
        hint.setText(R.string.pad_enabled_hint);
        hint.setTextSize(12);
        hint.setPadding((int) (34 * context.getResources().getDisplayMetrics().density), 0, 0, 0);
        root.addView(hint);

        // --- Global size -------------------------------------------------------
        root.addView(label(context, R.string.pad_size));
        final SeekBar size = seek(context, 60, 160, Math.round(settings.globalScale() * 100f));
        root.addView(size);
        final TextView sizeValue = value(context, Math.round(settings.globalScale() * 100f) + "%");
        root.addView(sizeValue);
        size.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = Math.max(60, progress);
                sizeValue.setText(pct + "%");
                settings.setGlobalScale(pct / 100f);
            }
        });

        // --- Opacity -----------------------------------------------------------
        root.addView(label(context, R.string.pad_opacity));
        final SeekBar opacity = seek(context, 30, 100, Math.round(settings.opacity() * 100f));
        root.addView(opacity);
        final TextView opacityValue = value(context, Math.round(settings.opacity() * 100f) + "%");
        root.addView(opacityValue);
        opacity.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = Math.max(30, progress);
                opacityValue.setText(pct + "%");
                settings.setOpacity(pct / 100f);
            }
        });

        // --- Actions -----------------------------------------------------------
        android.widget.Button edit = actionButton(context, R.string.pad_edit);
        root.addView(edit);
        android.widget.Button reset = actionButton(context, R.string.pad_reset);
        root.addView(reset);
        android.widget.Button graphics = actionButton(context, R.string.pad_open_graphics);
        root.addView(graphics);

        final AlertDialog[] dialog = new AlertDialog[1];

        enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setEnabled(isChecked);
            if (pad != null) {
                pad.applyEnabledState();
            }
        });

        edit.setOnClickListener(v -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            if (!settings.enabled()) {
                // Editing an invisible pad is confusing; enable it first.
                settings.setEnabled(true);
                enabled.setChecked(true);
                if (pad != null) {
                    pad.applyEnabledState();
                }
            }
            if (pad != null) {
                pad.enterEditMode();
            }
        });

        reset.setOnClickListener(v -> new AlertDialog.Builder(context)
                .setTitle(R.string.pad_reset)
                .setMessage(context.getString(R.string.setup_confirm_reset_layout))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    settings.resetLayout();
                    Toast.makeText(context, R.string.pad_reset, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        graphics.setOnClickListener(v -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            com.yestermester.simpsons.GraphicsSettingsDialog.show(context);
        });

        dialog[0] = new AlertDialog.Builder(context)
                .setTitle(R.string.pad_settings_title)
                .setView(root)
                .setPositiveButton(R.string.pad_edit_done, (d, w) -> {
                    if (pad != null && pad.isInEditMode()) {
                        pad.exitEditMode();
                    }
                })
                .setOnDismissListener(d -> {
                    if (pad != null && pad.isInEditMode()) {
                        pad.exitEditMode();
                    }
                })
                .show();
    }

    // --- Small programmatic widgets ---------------------------------------------

    private static TextView label(Context c, int textRes) {
        TextView tv = new TextView(c);
        tv.setText(textRes);
        tv.setTextSize(14);
        tv.setPadding(0, (int) (12 * c.getResources().getDisplayMetrics().density), 0, 0);
        return tv;
    }

    private static TextView value(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        return tv;
    }

    private static SeekBar seek(Context c, int min, int max, int value) {
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        return sb;
    }

    private static android.widget.Button actionButton(Context c, int textRes) {
        android.widget.Button b = new android.widget.Button(c);
        b.setText(textRes);
        return b;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
