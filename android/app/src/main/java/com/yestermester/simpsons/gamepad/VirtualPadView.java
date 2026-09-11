package com.yestermester.simpsons.gamepad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.yestermester.simpsons.R;

import org.json.JSONObject;
import org.libsdl.app.SDLActivity;

import java.util.HashMap;

/**
 * Multi-touch on-screen gamepad overlay with a full layout editor.
 *
 * Input path: touches -> PadGeometry.resolveTouch (nearest-wins) ->
 * PadInputBridge (JNI) -> SDL3 virtual gamepad (android_gamepad.cpp) -> the
 * SDK's SDL input driver -> the game's XInput API. The overlay consumes the
 * entire gesture stream, so touches never leak into the game as mouse input.
 *
 * Editor (the gear button, bottom-center): every control can be dragged to a
 * new position, resized (+/- or pinch), and hidden/shown (eye badge);
 * positions are normalized (0..1) so the layout survives rotation and device
 * changes. Everything persists through PadSettings immediately.
 *
 * onDraw performs zero allocations: every Paint/RectF is pre-allocated and
 * mutated in place; invalidation happens only on input or settings changes
 * (no animation loops, no per-frame work while idle).
 */
public final class VirtualPadView extends View {

    // --- Install/ lifecycle ---------------------------------------------------

    /**
     * Creates the overlay, adds it on top of the SDL surface and returns it.
     * The view is GONE while the pad is disabled.
     */
    public static VirtualPadView install(Activity activity) {
        VirtualPadView view = new VirtualPadView(activity);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // SDLActivity's content view is a RelativeLayout whose first child is
        // the SDLSurface; adding the pad afterwards stacks it on top. (The
        // overlay must stay a SIBLING of the surface: the surface is a
        // SurfaceView, and views layered above it in the same window draw
        // over the game without forcing an extra compositor layer per frame.)
        View content = SDLActivity.getContentView();
        if (content instanceof ViewGroup) {
            ((ViewGroup) content).addView(view, lp);
        } else {
            // Very defensive: fall back to the activity's content frame.
            ViewGroup host = (ViewGroup) activity.getWindow().getDecorView()
                    .findViewById(android.R.id.content);
            host.addView(view, lp);
        }
        view.applyEnabledState();
        return view;
    }

    // --- Per-pointer state ----------------------------------------------------

    private static final class Pointer {
        /** Owning pointer id, or -1 when the slot is free. */
        int id = -1;
        /** Control being held (null when the slot is free). */
        PadGeometry.Placement control;
        /** True when the pointer owns the control's input. */
        boolean active;
        /** Last absolute position (for move deltas). */
        float lastX, lastY;
        /** Floating-stick anchor: where the thumb first landed. */
        float anchorX, anchorY;
    }

    private static final int MAX_POINTERS = 10;

    // --- Chrome (gear + edit toolbar) geometry, computed per layout pass ------

    private static final class Chrome {
        final RectF gear = new RectF();
        final RectF done = new RectF();
        final RectF minus = new RectF();
        final RectF plus = new RectF();
        final RectF eye = new RectF();
        final RectF hint = new RectF();
        /** Eye badges per control id ("id" -> badge rect). */
        final HashMap<String, RectF> badges = new HashMap<>();
    }

    // --- View state ------------------------------------------------------------

    private final PadSettings settings;
    private final Chrome chrome = new Chrome();
    private final Pointer[] pointers = new Pointer[MAX_POINTERS];

    private PadGeometry.Placement[] placements = new PadGeometry.Placement[0];
    private int lastGeneration = -1;
    private int lastWidth = -1, lastHeight = -1;

    /** Pressed digital buttons: control id -> SDL button index. */
    private final HashMap<String, Integer> pressedButtons = new HashMap<>();
    /** Current D-pad mask (0 when released). */
    private int dpadMask;
    /** Stick deflections: index 0 = left, 1 = right. */
    private final float[] stickX = new float[2];
    private final float[] stickY = new float[2];
    /** Floating-stick anchors (where the thumb first landed), per stick. */
    private final float[] stickAnchorX = new float[2];
    private final float[] stickAnchorY = new float[2];
    private final boolean[] stickHeld = new boolean[2];
    /** Trigger values: index 0 = left, 1 = right. */
    private final float[] triggerValue = new float[2];

    private boolean editMode;
    private String selectedControl;
    /** Drag offset from the control center at drag start. */
    private float dragOffX, dragOffY;
    /** Pinch distance when two pointers are on the selected control. */
    private float pinchStartDistance = -1f;
    private float pinchStartScale = 1f;

    // --- Pre-allocated paints (see PadTheme) -----------------------------------

    private final android.graphics.Paint pGlass = PadTheme.fill(PadTheme.GLASS);
    private final android.graphics.Paint pGlassStrong = PadTheme.fill(PadTheme.GLASS_STRONG);
    private final android.graphics.Paint pGlow = PadTheme.fill(PadTheme.GLOW);
    private final android.graphics.Paint pStroke;
    private final android.graphics.Paint pStrokeActive;
    private final android.graphics.Paint pDashed;
    private final android.graphics.Paint pHidden;
    private final android.graphics.Paint pChrome = PadTheme.fill(PadTheme.EDIT_CHROME);
    private final android.graphics.Paint pChromeStroke;
    private final android.graphics.Paint pText;
    private final android.graphics.Paint pTextSmall;
    private final android.graphics.Paint pTextBig;
    private final android.graphics.Paint pAccent = PadTheme.fill(0);
    private final RectF tmpRect = new RectF();
    private final android.graphics.Path tmpPath = new android.graphics.Path();

    public VirtualPadView(Context context) {
        super(context);
        this.settings = PadSettings.get(context);
        for (int i = 0; i < MAX_POINTERS; i++) {
            pointers[i] = new Pointer();
        }
        float density = getResources().getDisplayMetrics().density;
        this.pStroke = PadTheme.stroke(2f * density, PadTheme.STROKE);
        this.pStrokeActive = PadTheme.stroke(3f * density, PadTheme.STROKE_ACTIVE);
        this.pDashed = PadTheme.dashed(2f * density, PadTheme.EDIT_SELECT);
        this.pHidden = PadTheme.dashed(2f * density, PadTheme.HIDDEN_TINT);
        this.pChromeStroke = PadTheme.stroke(1.5f * density, 0x33FFFFFF);
        this.pText = PadTheme.text(15f * density, 0xEEFFFFFF);
        this.pTextSmall = PadTheme.text(12f * density, PadTheme.EDIT_HINT);
        this.pTextBig = PadTheme.text(18f * density, PadTheme.EDIT_TEXT);
        setClickable(true);
        setFocusable(false);
        // The overlay draws over the game; never let accessibility pan it.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    // --- Settings plumbing ------------------------------------------------------

    /** Shows/hides the overlay per the master enabled setting. */
    public void applyEnabledState() {
        setVisibility(settings.enabled() ? VISIBLE : GONE);
        if (!settings.enabled()) {
            releaseAll();
        }
        requestLayoutIfStale(true);
    }

    /** Re-reads settings if they changed since the last layout pass. */
    private void requestLayoutIfStale(boolean force) {
        int gen = settings.generation();
        if (force || gen != lastGeneration) {
            lastGeneration = gen;
            relayout();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        relayout();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            releaseAll();
        }
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            placements = new PadGeometry.Placement[0];
            return;
        }
        lastWidth = w;
        lastHeight = h;
        JSONObject overrides = settings.overrides();
        placements = PadGeometry.layout(w, h, settings.globalScale(), overrides);
        computeChrome(w, h);
        invalidate();
    }

    private void computeChrome(int w, int h) {
        chrome.badges.clear();
        float density = getResources().getDisplayMetrics().density;
        float unit = Math.min(w, h);

        // Gear (settings) button: bottom-center, outside the thumb clusters.
        float gearR = 0.030f * unit;
        chrome.gear.set(w * 0.5f - gearR, h * 0.955f - gearR, w * 0.5f + gearR, h * 0.955f + gearR);

        // Edit toolbar: top-center row of round buttons + a hint strip.
        float btnR = 0.032f * unit;
        float cy = Math.max(btnR * 1.6f, 14f * density + btnR);
        float cx = w * 0.5f;
        chrome.done.set(cx - btnR * 4.6f, cy - btnR, cx - btnR * 2.8f, cy + btnR);
        chrome.minus.set(cx - btnR * 2.2f, cy - btnR, cx - btnR * 0.4f, cy + btnR);
        chrome.plus.set(cx + btnR * 0.4f, cy - btnR, cx + btnR * 2.2f, cy + btnR);
        chrome.eye.set(cx + btnR * 2.8f, cy - btnR, cx + btnR * 4.6f, cy + btnR);
        chrome.hint.set(cx - btnR * 5.2f, cy + btnR * 1.35f, cx + btnR * 5.2f,
                cy + btnR * 1.35f + 18f * density);

        // Per-control eye badges (edit mode).
        for (PadGeometry.Placement p : placements) {
            float badgeR = Math.max(11f * density, p.r * 0.30f);
            RectF r = new RectF(p.cx + p.r * 0.72f - badgeR, p.cy - p.r * 0.72f - badgeR,
                    p.cx + p.r * 0.72f + badgeR, p.cy - p.r * 0.72f + badgeR);
            chrome.badges.put(p.id, r);
        }
    }

    // --- Input ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Always consume: game touches must never reach the SDL surface
        // underneath (the game reads a gamepad, not a mouse).
        requestLayoutIfStale(false);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                onPointerDown(event.getPointerId(idx), event.getX(idx), event.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = event.getActionIndex();
                onPointerUp(event.getPointerId(idx), event.getX(idx), event.getY(idx));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                releaseAll();
                break;
            }
            default:
                break;
        }
        return true;
    }

    private Pointer acquirePointer(int pointerId) {
        for (Pointer p : pointers) {
            if (p.id == pointerId) {
                return p;
            }
        }
        for (Pointer p : pointers) {
            if (p.id == -1) {
                p.id = pointerId;
                p.control = null;
                p.active = false;
                return p;
            }
        }
        return null;
    }

    private Pointer findPointer(int pointerId) {
        for (Pointer p : pointers) {
            if (p.id == pointerId) {
                return p;
            }
        }
        return null;
    }

    private void onPointerDown(int pointerId, float x, float y) {
        Pointer p = acquirePointer(pointerId);
        if (p == null) {
            return;
        }
        p.lastX = x;
        p.lastY = y;

        if (editMode) {
            onEditPointerDown(p, x, y);
            return;
        }

        // Chrome first: the gear opens settings.
        if (chrome.gear.contains(x, y)) {
            p.control = null;
            p.active = false;
            post(() -> PadSettingsDialog.show(getContext(), VirtualPadView.this));
            return;
        }

        PadGeometry.Placement hit = PadGeometry.resolveTouch(x, y, placements);
        if (hit == null) {
            p.control = null;
            p.active = false;
            return;
        }
        p.control = hit;
        p.active = true;
        if (hit.kind == PadGeometry.KIND_STICK) {
            // Floating stick: the pivot is where the thumb lands, not the
            // control's home position - landing off-center never produces an
            // instant full deflection.
            p.anchorX = x;
            p.anchorY = y;
            int idx = PadGeometry.STICK_L.equals(hit.id) ? 0 : 1;
            stickAnchorX[idx] = x;
            stickAnchorY[idx] = y;
            stickHeld[idx] = true;
        }
        applyControlPress(hit, x, y);
        invalidate();
    }

    private void onPointerMove(int pointerId, float x, float y) {
        Pointer p = findPointer(pointerId);
        if (p == null || p.control == null) {
            return;
        }
        p.lastX = x;
        p.lastY = y;

        if (editMode) {
            onEditPointerMove(p, x, y);
            return;
        }
        applyControlMove(p.control, x, y, p.anchorX, p.anchorY);
        invalidate();
    }

    private void onPointerUp(int pointerId, float x, float y) {
        Pointer p = findPointer(pointerId);
        if (p == null) {
            return;
        }
        if (p.control != null) {
            if (editMode) {
                onEditPointerUp(p, x, y);
            } else {
                applyControlRelease(p.control);
                invalidate();
            }
        }
        p.id = -1;
        p.control = null;
        p.active = false;
        if (editMode && pinchStartDistance >= 0f) {
            pinchStartDistance = -1f;
        }
    }

    // --- Game input application ---------------------------------------------------

    private void applyControlPress(PadGeometry.Placement c, float x, float y) {
        switch (c.kind) {
            case PadGeometry.KIND_STICK:
                updateStick(c, x, y, x, y);
                break;
            case PadGeometry.KIND_DPAD:
                updateDpad(c, x, y);
                break;
            case PadGeometry.KIND_TRIGGER:
                setTrigger(c.id, 1f);
                break;
            case PadGeometry.KIND_BUTTON:
            default:
                setButton(c.id, true);
                break;
        }
    }

    private void applyControlMove(PadGeometry.Placement c, float x, float y, float ax, float ay) {
        switch (c.kind) {
            case PadGeometry.KIND_STICK:
                updateStick(c, ax, ay, x, y);
                break;
            case PadGeometry.KIND_DPAD:
                updateDpad(c, x, y);
                break;
            default:
                break; // Buttons/triggers latch while held.
        }
    }

    private void applyControlRelease(PadGeometry.Placement c) {
        switch (c.kind) {
            case PadGeometry.KIND_STICK:
                if (PadGeometry.STICK_L.equals(c.id)) {
                    stickX[0] = 0f;
                    stickY[0] = 0f;
                    stickHeld[0] = false;
                    PadInputBridge.nativeSetStick(0, 0f, 0f);
                } else {
                    stickX[1] = 0f;
                    stickY[1] = 0f;
                    stickHeld[1] = false;
                    PadInputBridge.nativeSetStick(1, 0f, 0f);
                }
                break;
            case PadGeometry.KIND_DPAD:
                setDpad(0);
                break;
            case PadGeometry.KIND_TRIGGER:
                setTrigger(c.id, 0f);
                break;
            case PadGeometry.KIND_BUTTON:
            default:
                setButton(c.id, false);
                break;
        }
    }

    private void updateStick(PadGeometry.Placement c, float ax, float ay, float x, float y) {
        // Floating stick: deflection is measured from the ANCHOR (where the
        // thumb first landed), clamped to the ring. The cap follows the
        // finger; releasing snaps everything back to center. Landing
        // off-center never produces an instant full deflection, which plays
        // much better than a fixed pivot on glass.
        float dx = x - ax;
        float dy = y - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float max = c.r * 0.92f;
        float nx = 0f, ny = 0f;
        if (len > 0f) {
            nx = dx / len;
            ny = dy / len;
        }
        float defl = PadGeometry.clamp(len / max, 0f, 1f);
        // Radial dead zone: below 0.14 the stick reports center.
        if (defl < 0.14f) {
            defl = 0f;
        }
        int idx = PadGeometry.STICK_L.equals(c.id) ? 0 : 1;
        stickX[idx] = nx * defl;
        stickY[idx] = ny * defl;
        PadInputBridge.nativeSetStick(idx, stickX[idx], stickY[idx]);
    }

    private void updateDpad(PadGeometry.Placement c, float x, float y) {
        setDpad(PadGeometry.dpadMask(x - c.cx, y - c.cy, c.r));
    }

    private void setDpad(int mask) {
        if (mask == dpadMask) {
            return;
        }
        int changed = mask ^ dpadMask;
        if ((changed & PadGeometry.DPAD_UP) != 0) {
            PadInputBridge.nativeSetButton(PadInputBridge.BTN_DPAD_UP,
                    (mask & PadGeometry.DPAD_UP) != 0);
        }
        if ((changed & PadGeometry.DPAD_DOWN) != 0) {
            PadInputBridge.nativeSetButton(PadInputBridge.BTN_DPAD_DOWN,
                    (mask & PadGeometry.DPAD_DOWN) != 0);
        }
        if ((changed & PadGeometry.DPAD_LEFT) != 0) {
            PadInputBridge.nativeSetButton(PadInputBridge.BTN_DPAD_LEFT,
                    (mask & PadGeometry.DPAD_LEFT) != 0);
        }
        if ((changed & PadGeometry.DPAD_RIGHT) != 0) {
            PadInputBridge.nativeSetButton(PadInputBridge.BTN_DPAD_RIGHT,
                    (mask & PadGeometry.DPAD_RIGHT) != 0);
        }
        dpadMask = mask;
    }

    private void setButton(String controlId, boolean down) {
        Integer sdlButton = sdlButtonFor(controlId);
        if (sdlButton == null) {
            return;
        }
        if (down) {
            pressedButtons.put(controlId, sdlButton);
        } else {
            pressedButtons.remove(controlId);
        }
        PadInputBridge.nativeSetButton(sdlButton, down);
    }

    private void setTrigger(String controlId, float value) {
        int idx = PadGeometry.TRIG_L.equals(controlId) ? 0 : 1;
        triggerValue[idx] = value;
        PadInputBridge.nativeSetTrigger(idx, value);
    }

    private static Integer sdlButtonFor(String controlId) {
        switch (controlId) {
            case PadGeometry.BTN_A:
                return PadInputBridge.BTN_SOUTH;
            case PadGeometry.BTN_B:
                return PadInputBridge.BTN_EAST;
            case PadGeometry.BTN_X:
                return PadInputBridge.BTN_WEST;
            case PadGeometry.BTN_Y:
                return PadInputBridge.BTN_NORTH;
            case PadGeometry.BTN_LB:
                return PadInputBridge.BTN_LEFT_SHOULDER;
            case PadGeometry.BTN_RB:
                return PadInputBridge.BTN_RIGHT_SHOULDER;
            case PadGeometry.BTN_START:
                return PadInputBridge.BTN_START;
            case PadGeometry.BTN_BACK:
                return PadInputBridge.BTN_BACK;
            default:
                return null;
        }
    }

    /** Releases every held control (host pause, edit-mode entry, disable). */
    public void releaseAll() {
        for (Pointer p : pointers) {
            if (p.control != null) {
                applyControlRelease(p.control);
            }
            p.id = -1;
            p.control = null;
            p.active = false;
        }
        setDpad(0);
        stickX[0] = stickY[0] = 0f;
        stickX[1] = stickY[1] = 0f;
        stickHeld[0] = stickHeld[1] = false;
        triggerValue[0] = triggerValue[1] = 0f;
        PadInputBridge.nativeSetStick(0, 0f, 0f);
        PadInputBridge.nativeSetStick(1, 0f, 0f);
        PadInputBridge.nativeSetTrigger(0, 0f);
        PadInputBridge.nativeSetTrigger(1, 0f);
        invalidate();
    }

    /** Host pause: release everything so no button sticks down. */
    public void onHostPause() {
        releaseAll();
    }

    // --- Edit mode -----------------------------------------------------------------

    /** Enters layout editing (releases all game input first). */
    public void enterEditMode() {
        releaseAll();
        editMode = true;
        selectedControl = null;
        invalidate();
    }

    public void exitEditMode() {
        editMode = false;
        selectedControl = null;
        releaseAll();
        invalidate();
    }

    public boolean isInEditMode() {
        return editMode;
    }

    private void onEditPointerDown(Pointer p, float x, float y) {
        // Toolbar buttons.
        if (chrome.done.contains(x, y)) {
            exitEditMode();
            return;
        }
        if (chrome.plus.contains(x, y)) {
            nudgeSelectedScale(+0.1f);
            return;
        }
        if (chrome.minus.contains(x, y)) {
            nudgeSelectedScale(-0.1f);
            return;
        }
        if (chrome.eye.contains(x, y)) {
            if (selectedControl != null) {
                toggleVisibility(selectedControl);
            }
            return;
        }
        // Eye badges on controls.
        for (java.util.Map.Entry<String, RectF> e : chrome.badges.entrySet()) {
            if (e.getValue().contains(x, y)) {
                toggleVisibility(e.getKey());
                return;
            }
        }
        // Selecting / starting a drag on a control.
        PadGeometry.Placement hit = PadGeometry.resolveTouch(x, y, placements);
        if (hit != null) {
            selectedControl = hit.id;
            dragOffX = hit.cx - x;
            dragOffY = hit.cy - y;
            p.control = hit;
            p.active = true;
            updatePinchAnchor();
            invalidate();
        } else {
            selectedControl = null;
            invalidate();
        }
    }

    private void onEditPointerMove(Pointer p, float x, float y) {
        if (p.control == null || selectedControl == null
                || !p.control.id.equals(selectedControl)) {
            return;
        }
        // Two pointers on the selected control -> pinch to resize.
        int holders = 0;
        for (Pointer q : pointers) {
            if (q.control != null && q.control.id.equals(selectedControl)) {
                holders++;
            }
        }
        if (holders >= 2) {
            Pointer other = null;
            for (Pointer q : pointers) {
                if (q != p && q.control != null && q.control.id.equals(selectedControl)) {
                    other = q;
                    break;
                }
            }
            if (other != null) {
                float d = distance(x, y, other.lastX, other.lastY);
                if (pinchStartDistance < 0f) {
                    pinchStartDistance = d;
                    pinchStartScale = currentScaleOf(selectedControl);
                } else if (pinchStartDistance > 1f) {
                    float scale = PadGeometry.clamp(
                            pinchStartScale * (d / pinchStartDistance), 0.5f, 2.0f);
                    settings.setPlacement(selectedControl, Float.NaN, Float.NaN, scale, null);
                    relayoutFromSettings();
                }
            }
            return;
        }
        // Single pointer -> drag to move.
        float nx = (x + dragOffX) / Math.max(1, getWidth());
        float ny = (y + dragOffY) / Math.max(1, getHeight());
        settings.setPlacement(selectedControl, nx, ny, null, null);
        relayoutFromSettings();
    }

    private void onEditPointerUp(Pointer p, float x, float y) {
        if (pinchStartDistance >= 0f) {
            pinchStartDistance = -1f;
        }
    }

    private void updatePinchAnchor() {
        pinchStartDistance = -1f;
    }

    private void nudgeSelectedScale(float delta) {
        if (selectedControl == null) {
            return;
        }
        float scale = currentScaleOf(selectedControl) + delta;
        settings.setPlacement(selectedControl, Float.NaN, Float.NaN, scale, null);
        relayoutFromSettings();
    }

    private float currentScaleOf(String controlId) {
        JSONObject o = settings.placementOf(controlId);
        return (float) o.optDouble("scale", 1.0);
    }

    private void toggleVisibility(String controlId) {
        JSONObject o = settings.placementOf(controlId);
        boolean visible = o.optBoolean("visible", true);
        settings.setPlacement(controlId, Float.NaN, Float.NaN, null, !visible);
        relayoutFromSettings();
    }

    private void relayoutFromSettings() {
        lastGeneration = settings.generation();
        relayout();
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // --- Rendering -------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        requestLayoutIfStale(false);
        if (placements.length == 0) {
            return;
        }
        float opacity = settings.opacity();

        if (editMode) {
            drawEditMode(canvas);
            return;
        }

        for (PadGeometry.Placement c : placements) {
            if (!c.visible) {
                continue;
            }
            drawControl(canvas, c, opacity, false);
        }

        // Gear (settings) button.
        drawRoundButton(canvas, chrome.gear, "⚙", false, opacity);
    }

    private void drawControl(Canvas canvas, PadGeometry.Placement c, float opacity,
                             boolean editTint) {
        boolean pressed = isPressed(c);
        switch (c.kind) {
            case PadGeometry.KIND_STICK:
                drawStick(canvas, c, opacity, editTint);
                break;
            case PadGeometry.KIND_DPAD:
                drawDpad(canvas, c, opacity, pressed, editTint);
                break;
            case PadGeometry.KIND_TRIGGER:
                drawTrigger(canvas, c, opacity, pressed, editTint);
                break;
            case PadGeometry.KIND_BUTTON:
            default:
                drawButton(canvas, c, opacity, pressed, editTint);
                break;
        }
    }

    private boolean isPressed(PadGeometry.Placement c) {
        if (c.kind == PadGeometry.KIND_STICK) {
            int idx = PadGeometry.STICK_L.equals(c.id) ? 0 : 1;
            return stickX[idx] != 0f || stickY[idx] != 0f;
        }
        if (c.kind == PadGeometry.KIND_DPAD) {
            return dpadMask != 0;
        }
        if (c.kind == PadGeometry.KIND_TRIGGER) {
            int idx = PadGeometry.TRIG_L.equals(c.id) ? 0 : 1;
            return triggerValue[idx] > 0f;
        }
        return pressedButtons.containsKey(c.id);
    }

    private void drawStick(Canvas canvas, PadGeometry.Placement c, float opacity,
                           boolean editTint) {
        int idx = PadGeometry.STICK_L.equals(c.id) ? 0 : 1;
        // Held means "a thumb is down on this stick" (stickHeld), not
        // "deflection is non-zero" - the anchor visual stays put while the
        // thumb rests inside the dead zone.
        boolean held = stickHeld[idx];
        // While held, the whole ring floats to where the thumb landed (the
        // anchor); at rest it sits at its configured home.
        float baseX = held ? stickAnchorX[idx] : c.cx;
        float baseY = held ? stickAnchorY[idx] : c.cy;
        pGlassStrong.setColor(PadTheme.withOpacity(editTint ? PadTheme.HIDDEN_TINT : PadTheme.GLASS_STRONG, opacity));
        canvas.drawCircle(baseX, baseY, c.r, pGlassStrong);
        pStroke.setColor(PadTheme.withOpacity(held ? PadTheme.STROKE_ACTIVE : PadTheme.STROKE, opacity));
        pStroke.setStrokeWidth(held ? pStrokeActive.getStrokeWidth() : 2f * density());
        canvas.drawCircle(baseX, baseY, c.r, pStroke);
        // Cross-hair guides.
        pStroke.setColor(PadTheme.withOpacity(0x33FFFFFF, opacity));
        canvas.drawLine(baseX - c.r * 0.45f, baseY, baseX + c.r * 0.45f, baseY, pStroke);
        canvas.drawLine(baseX, baseY - c.r * 0.45f, baseX, baseY + c.r * 0.45f, pStroke);
        // Cap.
        float capR = c.r * 0.42f;
        float capX = baseX + stickX[idx] * c.r * 0.92f;
        float capY = baseY + stickY[idx] * c.r * 0.92f;
        pGlass.setColor(PadTheme.withOpacity(held ? PadTheme.GLOW : PadTheme.GLASS_STRONG, opacity));
        canvas.drawCircle(capX, capY, capR, pGlass);
        pStroke.setColor(PadTheme.withOpacity(PadTheme.STROKE_ACTIVE, opacity));
        canvas.drawCircle(capX, capY, capR, pStroke);
        // Label.
        pText.setColor(PadTheme.withOpacity(0xDDFFFFFF, opacity));
        pText.setTextSize(c.r * 0.42f);
        canvas.drawText(PadGeometry.STICK_L.equals(c.id) ? "L" : "R",
                capX, capY + pText.getTextSize() * 0.34f, pText);
    }

    private void drawDpad(Canvas canvas, PadGeometry.Placement c, float opacity, boolean pressed,
                          boolean editTint) {
        pGlassStrong.setColor(PadTheme.withOpacity(editTint ? PadTheme.HIDDEN_TINT : PadTheme.GLASS_STRONG, opacity));
        canvas.drawCircle(c.cx, c.cy, c.r, pGlassStrong);
        pStroke.setColor(PadTheme.withOpacity(pressed ? PadTheme.STROKE_ACTIVE : PadTheme.STROKE, opacity));
        canvas.drawCircle(c.cx, c.cy, c.r, pStroke);
        // Four arrows; pressed directions glow.
        float r = c.r;
        float arm = r * 0.55f;
        float tri = r * 0.30f;
        drawArrow(canvas, c.cx, c.cy - arm, 0f, (dpadMask & PadGeometry.DPAD_UP) != 0, tri, opacity);
        drawArrow(canvas, c.cx + arm, c.cy, 90f, (dpadMask & PadGeometry.DPAD_RIGHT) != 0, tri, opacity);
        drawArrow(canvas, c.cx, c.cy + arm, 180f, (dpadMask & PadGeometry.DPAD_DOWN) != 0, tri, opacity);
        drawArrow(canvas, c.cx - arm, c.cy, 270f, (dpadMask & PadGeometry.DPAD_LEFT) != 0, tri, opacity);
    }

    private void drawArrow(Canvas canvas, float x, float y, float rotationDeg, boolean on,
                           float size, float opacity) {
        canvas.save();
        canvas.rotate(rotationDeg, x, y);
        android.graphics.Path path = tmpPath;
        path.reset();
        path.moveTo(x, y - size * 0.5f);
        path.lineTo(x - size * 0.5f, y + size * 0.35f);
        path.lineTo(x + size * 0.5f, y + size * 0.35f);
        path.close();
        pAccent.setColor(PadTheme.withOpacity(on ? PadTheme.STROKE_ACTIVE : 0x99FFFFFF, opacity));
        canvas.drawPath(path, pAccent);
        canvas.restore();
    }

    private void drawButton(Canvas canvas, PadGeometry.Placement c, float opacity, boolean pressed,
                            boolean editTint) {
        pGlass.setColor(PadTheme.withOpacity(
                editTint ? PadTheme.HIDDEN_TINT : (pressed ? PadTheme.GLOW : PadTheme.GLASS),
                opacity * (editTint ? 0.4f : 1f)));
        canvas.drawCircle(c.cx, c.cy, c.r, pGlass);
        pStroke.setColor(PadTheme.withOpacity(pressed ? PadTheme.STROKE_ACTIVE : PadTheme.STROKE, opacity));
        pStroke.setStrokeWidth(pressed ? pStrokeActive.getStrokeWidth() : 2f * density());
        canvas.drawCircle(c.cx, c.cy, c.r, pStroke);
        String label = labelFor(c.id);
        int accent = accentFor(c.id);
        pText.setColor(PadTheme.withOpacity(accent, opacity));
        pText.setTextSize(c.r * 0.95f);
        canvas.drawText(label, c.cx, c.cy + pText.getTextSize() * 0.34f, pText);
    }

    private void drawTrigger(Canvas canvas, PadGeometry.Placement c, float opacity, boolean pressed,
                             boolean editTint) {
        // Pill-shaped trigger with an analog fill level (currently digital
        // full-press, but drawn as a fill so the analog path stays visible).
        tmpRect.set(c.cx - c.r * 1.15f, c.cy - c.r * 0.65f, c.cx + c.r * 1.15f, c.cy + c.r * 0.65f);
        pGlass.setColor(PadTheme.withOpacity(
                editTint ? PadTheme.HIDDEN_TINT : (pressed ? PadTheme.GLOW : PadTheme.GLASS),
                opacity * (editTint ? 0.4f : 1f)));
        canvas.drawRoundRect(tmpRect, c.r * 0.65f, c.r * 0.65f, pGlass);
        pStroke.setColor(PadTheme.withOpacity(pressed ? PadTheme.STROKE_ACTIVE : PadTheme.STROKE, opacity));
        canvas.drawRoundRect(tmpRect, c.r * 0.65f, c.r * 0.65f, pStroke);
        // Fill.
        int idx = PadGeometry.TRIG_L.equals(c.id) ? 0 : 1;
        if (triggerValue[idx] > 0f) {
            float halfW = tmpRect.width() * 0.5f * triggerValue[idx];
            tmpRect.set(c.cx - halfW, c.cy - c.r * 0.65f, c.cx + halfW, c.cy + c.r * 0.65f);
            pAccent.setColor(PadTheme.withOpacity(PadTheme.ACCENT_TRIGGER, opacity * 0.7f));
            canvas.drawRoundRect(tmpRect, c.r * 0.65f, c.r * 0.65f, pAccent);
        }
        pText.setColor(PadTheme.withOpacity(PadTheme.ACCENT_TRIGGER, opacity));
        pText.setTextSize(c.r * 0.78f);
        canvas.drawText(PadGeometry.TRIG_L.equals(c.id) ? "LT" : "RT",
                c.cx, c.cy + pText.getTextSize() * 0.34f, pText);
    }

    private void drawRoundButton(Canvas canvas, RectF r, String glyph, boolean pressed,
                                 float opacity) {
        float cx = r.centerX(), cy = r.centerY(), rad = r.width() * 0.5f;
        pGlass.setColor(PadTheme.withOpacity(pressed ? PadTheme.GLOW : PadTheme.GLASS, opacity));
        canvas.drawCircle(cx, cy, rad, pGlass);
        pStroke.setColor(PadTheme.withOpacity(PadTheme.STROKE, opacity));
        canvas.drawCircle(cx, cy, rad, pStroke);
        pText.setColor(PadTheme.withOpacity(0xEEFFFFFF, opacity));
        pText.setTextSize(rad * 0.95f);
        canvas.drawText(glyph, cx, cy + pText.getTextSize() * 0.34f, pText);
    }

    // --- Edit-mode rendering ----------------------------------------------------------

    private void drawEditMode(Canvas canvas) {
        // Controls: hidden ones drawn tinted, selected gets a dashed ring.
        for (PadGeometry.Placement c : placements) {
            drawControl(canvas, c, 1f, !c.visible);
            if (c.id.equals(selectedControl)) {
                canvas.drawCircle(c.cx, c.cy, c.r * 1.12f, pDashed);
            }
            RectF badge = chrome.badges.get(c.id);
            if (badge != null) {
                drawEyeBadge(canvas, badge, c.visible);
            }
        }
        // Toolbar.
        drawToolbarButton(canvas, chrome.done,
                getContext().getString(R.string.pad_edit_done), true);
        drawToolbarButton(canvas, chrome.minus, "−", selectedControl != null);
        drawToolbarButton(canvas, chrome.plus, "+", selectedControl != null);
        drawToolbarButton(canvas, chrome.eye, selectedControl == null ? "👁" : eyeGlyph(),
                selectedControl != null);
        // Hint strip.
        tmpRect.set(chrome.hint);
        pChrome.setAlpha(0xF0);
        canvas.drawRoundRect(tmpRect, 10f * density(), 10f * density(), pChrome);
        pChromeStroke.setAlpha(0x33);
        canvas.drawRoundRect(tmpRect, 10f * density(), 10f * density(), pChromeStroke);
        pTextSmall.setColor(PadTheme.EDIT_HINT);
        canvas.drawText(getContext().getString(R.string.pad_edit_hint),
                tmpRect.centerX(), tmpRect.top + 13f * density(), pTextSmall);
    }

    private String eyeGlyph() {
        if (selectedControl == null) {
            return "👁";
        }
        return settings.placementOf(selectedControl).optBoolean("visible", true) ? "👁" : "🚫";
    }

    private void drawToolbarButton(Canvas canvas, RectF r, String glyph, boolean enabled) {
        float cx = r.centerX(), cy = r.centerY(), rad = r.width() * 0.5f;
        pChrome.setAlpha(enabled ? 0xFF : 0x60);
        canvas.drawCircle(cx, cy, rad, pChrome);
        pChromeStroke.setAlpha(enabled ? 0x55 : 0x22);
        canvas.drawCircle(cx, cy, rad, pChromeStroke);
        pTextBig.setColor(enabled ? PadTheme.EDIT_TEXT : 0x60FFFFFF);
        pTextBig.setTextSize(rad * (glyph.length() > 2 ? 0.5f : 0.8f));
        canvas.drawText(glyph, cx, cy + pTextBig.getTextSize() * 0.34f, pTextBig);
    }

    private void drawEyeBadge(Canvas canvas, RectF r, boolean visible) {
        float cx = r.centerX(), cy = r.centerY(), rad = r.width() * 0.5f;
        pChrome.setAlpha(0xE6);
        canvas.drawCircle(cx, cy, rad, pChrome);
        pTextSmall.setColor(visible ? 0xFFDDFFFFFF : PadTheme.HIDDEN_TINT);
        pTextSmall.setTextSize(rad * 0.9f);
        canvas.drawText(visible ? "👁" : "🚫", cx, cy + pTextSmall.getTextSize() * 0.34f, pTextSmall);
    }

    // --- Misc ---------------------------------------------------------------------------

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private static String labelFor(String id) {
        switch (id) {
            case PadGeometry.BTN_A:
                return "A";
            case PadGeometry.BTN_B:
                return "B";
            case PadGeometry.BTN_X:
                return "X";
            case PadGeometry.BTN_Y:
                return "Y";
            case PadGeometry.BTN_LB:
                return "LB";
            case PadGeometry.BTN_RB:
                return "RB";
            case PadGeometry.BTN_START:
                return "❙❙";
            case PadGeometry.BTN_BACK:
                return "▤";
            default:
                return "?";
        }
    }

    private static int accentFor(String id) {
        switch (id) {
            case PadGeometry.BTN_A:
                return PadTheme.ACCENT_A;
            case PadGeometry.BTN_B:
                return PadTheme.ACCENT_B;
            case PadGeometry.BTN_X:
                return PadTheme.ACCENT_X;
            case PadGeometry.BTN_Y:
                return PadTheme.ACCENT_Y;
            default:
                return PadTheme.ACCENT_NEUTRAL;
        }
    }
}
