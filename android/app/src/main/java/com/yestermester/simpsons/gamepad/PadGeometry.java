package com.yestermester.simpsons.gamepad;

import android.graphics.RectF;

import org.json.JSONObject;

/**
 * Pure layout engine for the virtual gamepad. No Android UI types beyond
 * RectF; all inputs/outputs are plain numbers so the geometry is trivially
 * unit-testable and rotation-safe.
 *
 * Units: positions are normalized (0..1) against the view size; radii are
 * normalized against {@code min(width, height)} so controls stay circular and
 * proportional in every aspect ratio.
 *
 * Every control has a default anchor; PadSettings overrides carry the user's
 * edits (position, scale, visibility) and are persisted as JSON.
 */
public final class PadGeometry {

    private PadGeometry() {
    }

    /** Control identifiers, in SDL gamepad button order for the digital ones. */
    public static final String STICK_L = "stick_l";
    public static final String STICK_R = "stick_r";
    public static final String DPAD = "dpad";
    public static final String BTN_A = "btn_a";
    public static final String BTN_B = "btn_b";
    public static final String BTN_X = "btn_x";
    public static final String BTN_Y = "btn_y";
    public static final String BTN_LB = "btn_lb";
    public static final String BTN_RB = "btn_rb";
    public static final String TRIG_L = "trig_l";
    public static final String TRIG_R = "trig_r";
    public static final String BTN_START = "btn_start";
    public static final String BTN_BACK = "btn_back";

    /** Analog sticks. */
    public static final String[] STICKS = {STICK_L, STICK_R};
    /** Analog triggers. */
    public static final String[] TRIGGERS = {TRIG_L, TRIG_R};
    /** All touchable pad controls (the gear/settings button is not part of
     * the remappable layout; it lives in the view chrome). */
    public static final String[] CONTROLS = {
            STICK_L, STICK_R, DPAD,
            BTN_A, BTN_B, BTN_X, BTN_Y,
            BTN_LB, BTN_RB, TRIG_L, TRIG_R,
            BTN_START, BTN_BACK,
    };

    /** Kind of a control (drives rendering + touch behavior). */
    public static final int KIND_STICK = 0;
    public static final int KIND_DPAD = 1;
    public static final int KIND_BUTTON = 2;
    public static final int KIND_TRIGGER = 3;

    /** A control's default geometry in normalized units. */
    public static final class Default {
        public final String id;
        public final int kind;
        /** Center X, normalized to view width (0..1). */
        public final float cx;
        /** Center Y, normalized to view height (0..1). */
        public final float cy;
        /** Radius, normalized to min(viewWidth, viewHeight). */
        public final float r;

        Default(String id, int kind, float cx, float cy, float r) {
            this.id = id;
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }
    }

    /**
     * Default layout, tuned for the Xbox 360 control scheme The Simpsons Game
     * uses (left stick = movement, right stick = camera, D-pad = team
     * commands, face cluster bottom-right, shoulders top-right, pause buttons
     * top-center). Anchors are chosen to stay clear of center-screen action.
     */
    private static final Default[] DEFAULTS = {
            new Default(STICK_L, KIND_STICK, 0.155f, 0.730f, 0.105f),
            new Default(STICK_R, KIND_STICK, 0.855f, 0.660f, 0.095f),
            new Default(DPAD, KIND_DPAD, 0.115f, 0.435f, 0.088f),
            new Default(BTN_A, KIND_BUTTON, 0.895f, 0.795f, 0.044f),
            new Default(BTN_B, KIND_BUTTON, 0.960f, 0.715f, 0.044f),
            new Default(BTN_X, KIND_BUTTON, 0.825f, 0.715f, 0.044f),
            new Default(BTN_Y, KIND_BUTTON, 0.895f, 0.630f, 0.044f),
            new Default(BTN_LB, KIND_BUTTON, 0.710f, 0.085f, 0.037f),
            new Default(BTN_RB, KIND_BUTTON, 0.880f, 0.085f, 0.037f),
            new Default(TRIG_L, KIND_TRIGGER, 0.710f, 0.200f, 0.045f),
            new Default(TRIG_R, KIND_TRIGGER, 0.880f, 0.200f, 0.045f),
            new Default(BTN_START, KIND_BUTTON, 0.585f, 0.065f, 0.030f),
            new Default(BTN_BACK, KIND_BUTTON, 0.435f, 0.065f, 0.030f),
    };

    public static Default defaultFor(String id) {
        for (Default d : DEFAULTS) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown control " + id);
    }

    /** Per-control user overrides (already merged with defaults by
     * PadSettings): position/scale/visibility actually in effect. */
    public static final class Placement {
        public final String id;
        public final int kind;
        /** Center in view pixels. */
        public final float cx;
        public final float cy;
        /** Radius in view pixels (already includes every scale factor). */
        public final float r;
        public final boolean visible;
        /** Hit-test radius: buttons get a generous margin, sticks their ring. */
        public final float hitR;

        Placement(String id, int kind, float cx, float cy, float r, boolean visible) {
            this.id = id;
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.visible = visible;
            // Buttons/shoulders accept slightly-off taps; sticks and the
            // D-pad use their base ring (already large).
            this.hitR = (kind == KIND_BUTTON || kind == KIND_TRIGGER) ? r * 1.35f : r * 1.15f;
        }

        public boolean contains(float x, float y) {
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= hitR * hitR;
        }

        public float distance2(float x, float y) {
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy;
        }
    }

    /**
     * Computes the effective placement of every control for the given view
     * size, global scale factor and per-control override set.
     *
     * @param w            view width in pixels (> 0)
     * @param h            view height in pixels (> 0)
     * @param globalScale  master size multiplier (e.g. 1.0)
     * @param overrides    per-control {"x":0..1,"y":0..1,"scale":float,
     *                     "visible":bool} — missing entries fall back to
     *                     defaults; NaN-safe (bad values fall back too)
     */
    public static Placement[] layout(int w, int h, float globalScale, JSONObject overrides) {
        float unit = Math.min(w, h);
        Placement[] out = new Placement[DEFAULTS.length];
        for (int i = 0; i < DEFAULTS.length; i++) {
            Default d = DEFAULTS[i];
            float cx = d.cx, cy = d.cy, scale = 1f;
            boolean visible = true;
            if (overrides != null && overrides.has(d.id)) {
                JSONObject o = overrides.optJSONObject(d.id);
                if (o != null) {
                    float ox = (float) o.optDouble("x", Double.NaN);
                    float oy = (float) o.optDouble("y", Double.NaN);
                    float os = (float) o.optDouble("scale", Double.NaN);
                    if (!Float.isNaN(ox) && !Float.isNaN(oy)) {
                        cx = clamp(ox, 0.02f, 0.98f);
                        cy = clamp(oy, 0.02f, 0.98f);
                    }
                    if (!Float.isNaN(os)) {
                        scale = clamp(os, 0.5f, 2.0f);
                    }
                    visible = o.optBoolean("visible", true);
                }
            }
            out[i] = new Placement(d.id, d.kind, cx * w, cy * h,
                    d.r * unit * scale * globalScale, visible);
        }
        return out;
    }

    /**
     * Resolves which visible control a touch at (x, y) belongs to.
     * Nearest-wins: the control whose center is closest to the touch among
     * all controls whose hit circle contains it. This makes tight layouts
     * unambiguous without stealing touches from neighboring controls.
     */
    public static Placement resolveTouch(float x, float y, Placement[] placements) {
        Placement best = null;
        float bestD = Float.MAX_VALUE;
        for (Placement p : placements) {
            if (!p.visible || !p.contains(x, y)) {
                continue;
            }
            float d = p.distance2(x, y);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Union bounds of the given placements (for edit-mode clamping). */
    public static RectF bounds(Placement[] placements) {
        RectF r = new RectF(Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Placement p : placements) {
            r.union(p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r);
        }
        return r.isEmpty() ? new RectF(0, 0, 0, 0) : r;
    }

    /** D-pad direction masks. */
    public static final int DPAD_UP = 1;
    public static final int DPAD_RIGHT = 2;
    public static final int DPAD_DOWN = 4;
    public static final int DPAD_LEFT = 8;

    /** Per-octant mask: index = octant (0=right, going clockwise: DR, D, DL,
     * L, UL, U, UR), value = the mask of directions pressed. */
    private static final int[] OCTANT_MASKS = {
            DPAD_RIGHT,                     // 0: right
            DPAD_RIGHT | DPAD_DOWN,         // 1: down-right
            DPAD_DOWN,                      // 2: down
            DPAD_DOWN | DPAD_LEFT,          // 3: down-left
            DPAD_LEFT,                      // 4: left
            DPAD_LEFT | DPAD_UP,            // 5: up-left
            DPAD_UP,                        // 6: up
            DPAD_UP | DPAD_RIGHT,           // 7: up-right
    };

    /**
     * D-pad direction from a touch point relative to the pad center.
     * Full 8-way support: cardinals and diagonals, with a center dead zone
     * (releasing the D-pad by drifting to the middle is intentional and
     * matches physical-pad behavior).
     */
    public static int dpadMask(float dx, float dy, float radius) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < radius * 0.30f) {
            return 0; // center dead zone
        }
        double a = Math.atan2(dy, dx); // 0 = right, +90 = down (screen Y-down)
        double deg = Math.toDegrees(a);
        // Octant index: nearest multiple of 45 degrees, normalized to 0..7.
        int octant = ((int) Math.round(deg / 45.0) + 8) % 8;
        return OCTANT_MASKS[octant];
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
