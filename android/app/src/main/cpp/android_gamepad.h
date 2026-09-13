/**
 * android_gamepad.h - SDL3 virtual gamepad for the on-screen touch overlay.
 *
 * @added      Android port, 2026 (adapted from hells-gate-recomp,
 *             deivid22srk, BSD 3-Clause)
 */

#pragma once

namespace simpsons {
namespace gamepad {

/**
 * Attaches the virtual gamepad (idempotent, thread-safe). MUST be called
 * only after the runtime app's OnInitialize() returned - see the timing
 * comment in android_gamepad.cpp.
 *
 * @return true when the virtual pad is live.
 */
bool EnsureVirtualPadAttached();

}  // namespace gamepad
}  // namespace simpsons
