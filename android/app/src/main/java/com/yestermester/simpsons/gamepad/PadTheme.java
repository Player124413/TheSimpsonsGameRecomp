package com.yestermester.simpsons.gamepad;

import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PathEffect;

/**
 * Visual language of the on-screen gamepad: dark glass circles, white
 * strokes, per-button accent colors, press glow. All Paint objects are
 * pre-allocated here — VirtualPadView.onDraw never allocates.
 */
public final class PadTheme {

    private PadTheme() {
    }

    // Palette -----------------------------------------------------------------

    /** Base glass fill. */
    public static final int GLASS = 0x14202430;
    /** Stronger glass for sticks/d-pad bases. */
    public static final int GLASS_STRONG = 0x24202430;
    /** Default stroke. */
    public static final int STROKE = 0x66FFFFFF;
    /** Stroke for pressed state. */
    public static final int STROKE_ACTIVE = 0xEEFFFFFF;
    /** Press glow fill. */
    public static final int GLOW = 0x59FFFFFF;
    /** Hidden-control fill (edit mode). */
    public static final int HIDDEN_TINT = 0x66FF5252;
    /** Edit-mode selection ring. */
    public static final int EDIT_SELECT = 0xFFFFC107;
    /** Edit chrome (toolbar). */
    public static final int EDIT_CHROME = 0xF0181C24;
    public static final int EDIT_TEXT = 0xFFF5F5F5;
    public static final int EDIT_HINT = 0xB3F5F5F5;

    /** Per-button accent colors (label glyph color). */
    public static final int ACCENT_A = 0xFF4CAF50;   // green
    public static final int ACCENT_B = 0xFFEF5350;   // red
    public static final int ACCENT_X = 0xFF42A5F5;   // blue
    public static final int ACCENT_Y = 0xFFFFEE58;   // yellow
    public static final int ACCENT_NEUTRAL = 0xFFB0BEC5;
    public static final int ACCENT_TRIGGER = 0xFFFFAB40;

    // Paints ------------------------------------------------------------------

    /** Circle fill. Callers set alpha/color per state via setAlpha-free
     * helpers (Paint objects are per-view, mutated in onDraw is fine as long
     * as no allocation happens). */
    public static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    public static Paint stroke(float widthPx, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(widthPx);
        p.setColor(color);
        return p;
    }

    public static Paint text(float sizePx, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(sizePx);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        return p;
    }

    /** Dashed edit-mode outline. */
    public static Paint dashed(float widthPx, int color) {
        Paint p = stroke(widthPx, color);
        PathEffect fx = new DashPathEffect(new float[]{12f, 10f}, 0f);
        p.setPathEffect(fx);
        return p;
    }

    /** Applies an overall opacity (0.3..1) to a base alpha channel value. */
    public static int withOpacity(int color, float opacity) {
        int a = Color.alpha(color);
        int scaled = Math.round(a * opacity);
        return (color & 0x00FFFFFF) | (scaled << 24);
    }
}
