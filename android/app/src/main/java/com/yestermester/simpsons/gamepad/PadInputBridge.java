package com.yestermester.simpsons.gamepad;

/**
 * JNI bridge feeding the on-screen overlay into the SDL3 virtual gamepad
 * (see android_gamepad.cpp). Button indices follow SDL3's
 * SDL_GamepadButton order; sticks and triggers are analog.
 *
 * All calls are fire-and-forget and safe from any thread; every method
 * returns false when the native pad is not attached yet (startup) so the
 * view can skip work instead of queueing stale state.
 */
public final class PadInputBridge {

    // SDL_GamepadButton order (subset used by the overlay).
    public static final int BTN_SOUTH = 0;       // A
    public static final int BTN_EAST = 1;        // B
    public static final int BTN_WEST = 2;        // X
    public static final int BTN_NORTH = 3;       // Y
    public static final int BTN_BACK = 4;
    public static final int BTN_START = 6;
    public static final int BTN_GUIDE = 5;       // unused by the overlay
    public static final int BTN_LEFT_SHOULDER = 9;
    public static final int BTN_RIGHT_SHOULDER = 10;
    public static final int BTN_DPAD_UP = 11;
    public static final int BTN_DPAD_DOWN = 12;
    public static final int BTN_DPAD_LEFT = 13;
    public static final int BTN_DPAD_RIGHT = 14;

    // Sticks: 0 = left, 1 = right. Triggers: 0 = left, 1 = right.

    private PadInputBridge() {
    }

    public static native boolean nativeSetButton(int button, boolean down);

    public static native boolean nativeSetStick(int stick, float x, float y);

    public static native boolean nativeSetTrigger(int trigger, float value);

    public static native boolean nativeIsAttached();
}
