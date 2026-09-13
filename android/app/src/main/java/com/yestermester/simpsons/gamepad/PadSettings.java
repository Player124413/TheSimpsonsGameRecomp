package com.yestermester.simpsons.gamepad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Virtual gamepad settings + persistence. Everything lives in one
 * SharedPreferences file; the layout overrides are a JSON blob shaped as
 * {"control_id": {"x":0..1, "y":0..1, "scale":0.5..2, "visible":bool}}.
 *
 * All setters write through immediately (small data, games must not lose an
 * edit on process death) and bump a generation counter so VirtualPadView can
 * cheaply detect external changes (settings dialog edits).
 */
public final class PadSettings {

    private static final String PREFS = "simpsons_pad";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SCALE = "global_scale";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_OVERRIDES = "overrides";
    private static final String KEY_GENERATION = "generation";

    private final SharedPreferences prefs;

    private PadSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static PadSettings get(Context context) {
        return new PadSettings(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    /** Master switch for the whole on-screen gamepad. */
    public boolean enabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_ENABLED, value).putInt(KEY_GENERATION, generation() + 1).apply();
    }

    /** Master size multiplier for every control (0.6 .. 1.6). */
    public float globalScale() {
        float v = prefs.getFloat(KEY_SCALE, 1.0f);
        return (Float.isNaN(v) ? 1.0f : PadGeometry.clamp(v, 0.6f, 1.6f));
    }

    public void setGlobalScale(float value) {
        prefs.edit().putFloat(KEY_SCALE, PadGeometry.clamp(value, 0.6f, 1.6f))
                .putInt(KEY_GENERATION, generation() + 1).apply();
    }

    /** Overlay opacity (0.30 .. 1.0). */
    public float opacity() {
        float v = prefs.getFloat(KEY_OPACITY, 0.85f);
        return (Float.isNaN(v) ? 0.85f : PadGeometry.clamp(v, 0.30f, 1.0f));
    }

    public void setOpacity(float value) {
        prefs.edit().putFloat(KEY_OPACITY, PadGeometry.clamp(value, 0.30f, 1.0f))
                .putInt(KEY_GENERATION, generation() + 1).apply();
    }

    /** Per-control overrides (position/scale/visibility). Never null. */
    public JSONObject overrides() {
        String raw = prefs.getString(KEY_OVERRIDES, "{}");
        try {
            JSONObject o = new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
            return o;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public void setOverrides(JSONObject overrides) {
        prefs.edit().putString(KEY_OVERRIDES, overrides.toString())
                .putInt(KEY_GENERATION, generation() + 1).apply();
    }

    /** Effective placement inputs for one control (defaults + override). */
    public JSONObject placementOf(String controlId) {
        JSONObject all = overrides();
        JSONObject out = new JSONObject();
        PadGeometry.Default d = PadGeometry.defaultFor(controlId);
        try {
            JSONObject o = all.optJSONObject(controlId);
            if (o != null) {
                out.put("x", clampNan((float) o.optDouble("x", d.cx), d.cx, 0.02f, 0.98f));
                out.put("y", clampNan((float) o.optDouble("y", d.cy), d.cy, 0.02f, 0.98f));
                out.put("scale", clampNan((float) o.optDouble("scale", 1.0f), 1.0f, 0.5f, 2.0f));
                out.put("visible", o.optBoolean("visible", true));
            } else {
                out.put("x", d.cx);
                out.put("y", d.cy);
                out.put("scale", 1.0);
                out.put("visible", true);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /**
     * Stores one control's placement inputs. {@code x}/{@code y} are in
     * normalized view units; NaN values are ignored (keep current).
     */
    public void setPlacement(String controlId, float x, float y, Float scale, Boolean visible) {
        JSONObject all = overrides();
        JSONObject o = all.optJSONObject(controlId);
        if (o == null) {
            o = new JSONObject();
        }
        try {
            if (!Float.isNaN(x)) {
                o.put("x", PadGeometry.clamp(x, 0.02f, 0.98f));
            }
            if (!Float.isNaN(y)) {
                o.put("y", PadGeometry.clamp(y, 0.02f, 0.98f));
            }
            if (scale != null) {
                o.put("scale", PadGeometry.clamp(scale, 0.5f, 2.0f));
            }
            if (visible != null) {
                o.put("visible", visible.booleanValue());
            }
            all.put(controlId, o);
            setOverrides(all);
        } catch (JSONException ignored) {
        }
    }

    /** Drops all layout overrides (positions, sizes, visibilities). */
    public void resetLayout() {
        setOverrides(new JSONObject());
    }

    /**
     * True when the control's placement differs from the default in any
     * aspect (used to decide whether a control needs an override entry).
     */
    public boolean hasCustomPlacement(String controlId) {
        return overrides().has(controlId);
    }

    /** Monotonic counter bumped on every change (cheap change detection). */
    public int generation() {
        return prefs.getInt(KEY_GENERATION, 0);
    }

    private static float clampNan(float v, float fallback, float lo, float hi) {
        if (Float.isNaN(v)) {
            return fallback;
        }
        return PadGeometry.clamp(v, lo, hi);
    }
}
