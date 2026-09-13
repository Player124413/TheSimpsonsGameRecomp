/**
 * @file        rex/core/ucontext_android.cpp
 * @brief       Minimal getcontext/makecontext/swapcontext/setcontext for
 *              aarch64 Android (bionic provides ucontext_t but not the
 *              deprecated ucontext API functions).
 *
 * Scope note: the runtime's only consumer is Fiber (fiber_posix.cpp), which
 * uses the no-argument makecontext form (the trampoline pulls its arguments
 * from TLS) and never relies on signal-mask save/restore. This file
 * implements exactly that subset:
 *   - callee-saved integer registers x19-x30, sp and pc
 *   - caller-saved registers are left unspecified on resume, which is ABI
 *     legal: a resumed getcontext/swapcontext behaves like the call returned
 *     0 (regs[0] is forced to zero before the saved pc is entered)
 *   - makecontext switches pc to the given function and sp to the top of
 *     uc_stack (16-byte aligned); uc_link is ignored (the fiber entry never
 *     returns)
 *
 * Floating point callee-saved registers (d8-d15) are NOT preserved. The
 * fiber switch sites in the runtime are plain C++ control flow (no active
 * FP values live across SwitchTo), matching the upstream ucontext backend's
 * assumptions; if that ever changes, extend the save/restore blocks.
 *
 * Implementation note: these are naked functions containing only BASIC asm
 * with literal offsets - extended asm (operands/constraints) is not allowed
 * in naked functions. The literals are pinned by the static_asserts below:
 * if the bionic ucontext_t layout ever drifts, the build fails here loudly
 * instead of corrupting stacks.
 *
 * @added      Android port support, 2026
 */

#include <rex/platform.h>

#if defined(__ANDROID__) && defined(__aarch64__)

#include <stddef.h>
#include <signal.h>
#include <ucontext.h>

extern "C" {

// --- Layout pins (bionic arm64, LP64: uc_mcontext = struct sigcontext) ----
// Derivation (bionic sys/ucontext.h, aarch64, LP64):
//   uc_flags 0 | uc_link 8 | uc_stack 16 (stack_t 24B) | uc_sigmask 40
//   (userspace sigset_t = 128B, LP64) -> 168 | uc_mcontext aligned(16) -> 176.
static_assert(offsetof(ucontext_t, uc_flags) == 0, "bionic ucontext_t drift");
static_assert(offsetof(ucontext_t, uc_link) == 8, "bionic ucontext_t drift");
static_assert(offsetof(ucontext_t, uc_stack) == 16, "bionic ucontext_t drift");
static_assert(offsetof(ucontext_t, uc_sigmask) == 40, "bionic ucontext_t drift");
static_assert(offsetof(ucontext_t, uc_mcontext) == 176, "bionic ucontext_t drift");
static_assert(offsetof(mcontext_t, fault_address) == 0, "bionic mcontext_t drift");
static_assert(offsetof(mcontext_t, regs) == 8, "bionic mcontext_t drift");
static_assert(offsetof(mcontext_t, sp) == 256, "bionic mcontext_t drift");
static_assert(offsetof(mcontext_t, pc) == 264, "bionic mcontext_t drift");
static_assert(offsetof(mcontext_t, pstate) == 272, "bionic mcontext_t drift");

// Register slots in ucontext_t (uc_mcontext at 176, regs[] at +8):
//   regs[i] -> 184 + 8*i;  regs[0] -> 184;  sp -> 432;  pc -> 440;
//   pstate -> 448.
//
// Offsets from the regs base (x3 = ucp + 184):
//   x19/x20 -> 152, x21/x22 -> 168, x23/x24 -> 184, x25/x26 -> 200,
//   x27/x28 -> 216, x29/x30 -> 232, sp -> 248, pc -> 256.
__attribute__((naked)) int getcontext(ucontext_t* ucp) {
  __asm__ volatile(
      // x0 = ucp; capture the return address before anything else.
      "mov     x2, x30\n"
      "add     x3, x0, #184\n"              // x3 = &ucp->uc_mcontext.regs[0]
      "stp     x19, x20, [x3, #152]\n"
      "stp     x21, x22, [x3, #168]\n"
      "stp     x23, x24, [x3, #184]\n"
      "stp     x25, x26, [x3, #200]\n"
      "stp     x27, x28, [x3, #216]\n"
      "stp     x29, x30, [x3, #232]\n"
      "mov     x4, sp\n"
      "str     x4, [x3, #248]\n"            // sp
      "str     x2, [x3, #256]\n"            // pc = return address
      "str     xzr, [x0, #184]\n"           // regs[0] = 0: resume -> return 0
      "str     xzr, [x0, #448]\n"           // pstate = 0
      "mov     w0, #0\n"
      "ret\n");
}

void makecontext(ucontext_t* ucp, void (*func)(), int argc, ...) {
  // Fiber trampolines take no ucontext arguments (args ride in TLS).
  (void)argc;
  ucp->uc_mcontext.pc = reinterpret_cast<uint64_t>(func);
  ucp->uc_mcontext.sp = (reinterpret_cast<uint64_t>(ucp->uc_stack.ss_sp) +
                         ucp->uc_stack.ss_size) &
                        ~static_cast<uint64_t>(0xF);
}

__attribute__((naked)) int setcontext(const ucontext_t* ucp) {
  __asm__ volatile(
      // x0 = ucp
      "add     x3, x0, #184\n"
      "ldp     x19, x20, [x3, #152]\n"
      "ldp     x21, x22, [x3, #168]\n"
      "ldp     x23, x24, [x3, #184]\n"
      "ldp     x25, x26, [x3, #200]\n"
      "ldp     x27, x28, [x3, #216]\n"
      "ldp     x29, x30, [x3, #232]\n"
      "ldr     x4, [x3, #248]\n"
      "mov     sp, x4\n"
      "ldr     x16, [x3, #256]\n"
      "br      x16\n");
}

__attribute__((naked)) int swapcontext(ucontext_t* oucp, const ucontext_t* ucp) {
  __asm__ volatile(
      // x0 = oucp, x1 = ucp; capture the return address before anything else.
      "mov     x2, x30\n"
      "add     x3, x0, #184\n"
      "stp     x19, x20, [x3, #152]\n"
      "stp     x21, x22, [x3, #168]\n"
      "stp     x23, x24, [x3, #184]\n"
      "stp     x25, x26, [x3, #200]\n"
      "stp     x27, x28, [x3, #216]\n"
      "stp     x29, x30, [x3, #232]\n"
      "mov     x4, sp\n"
      "str     x4, [x3, #248]\n"            // sp
      "str     x2, [x3, #256]\n"            // pc = return address
      "str     xzr, [x0, #184]\n"           // regs[0] = 0: resume -> return 0
      "str     xzr, [x0, #448]\n"           // pstate = 0
      // Restore from ucp (x1).
      "add     x3, x1, #184\n"
      "ldp     x19, x20, [x3, #152]\n"
      "ldp     x21, x22, [x3, #168]\n"
      "ldp     x23, x24, [x3, #184]\n"
      "ldp     x25, x26, [x3, #200]\n"
      "ldp     x27, x28, [x3, #216]\n"
      "ldp     x29, x30, [x3, #232]\n"
      "ldr     x4, [x3, #248]\n"
      "mov     sp, x4\n"
      "ldr     x16, [x3, #256]\n"
      "br      x16\n");
}

}  // extern "C"

#endif  // __ANDROID__ && __aarch64__
