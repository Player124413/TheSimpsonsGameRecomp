#pragma once
/**
 *******************************************************************************
 * ReXGlue : Xbox 360 Recompilation SDK
 *******************************************************************************
 * SDL3 implementation of the Window abstraction (Android).
 *
 * Derived from the upstream SDL window backend (Tom Clay, 2026, BSD 3-Clause;
 * itself derived from Xenia's window_win.cc by Ben Vanik, 2020), adapted to
 * this SDK revision's Window API and focused on the Android platform: the
 * window is backed by the SDL activity's SurfaceView and presents through
 * an ANativeWindow (VK_KHR_android_surface).
 *
 * @added      Android port support, 2026
 */

#include <atomic>
#include <cstdint>
#include <memory>
#include <string_view>

#include <SDL3/SDL.h>

#include <rex/ui/window.h>
#include <rex/ui/windowed_app_context_sdl.h>

namespace rex {
namespace ui {

class WindowSDL final : public Window {
 public:
  WindowSDL(WindowedAppContext& app_context, const std::string_view title,
            uint32_t desired_logical_width, uint32_t desired_logical_height);
  ~WindowSDL() override;

  void* GetNativeWindowHandle() const override;

  // Called by SDLWindowedAppContext on the UI thread.
  void HandleWindowEvent(SDL_Event& event);
  void HandleKeyEvent(SDL_Event& event);
  void HandleMouseEvent(SDL_Event& event);
  void HandlePaintEvent();

 protected:
  uint32_t GetLatestDpiImpl() const override;

  bool OpenImpl() override;
  void RequestCloseImpl() override;

  void ApplyNewFullscreen() override;
  void ApplyNewTitle() override;
  void ApplyNewMouseCapture() override;
  void ApplyNewMouseRelease() override;
  void ApplyNewCursorVisibility(CursorVisibility old_cursor_visibility) override;
  void FocusImpl() override;

  std::unique_ptr<Surface> CreateSurfaceImpl(Surface::TypeFlags allowed_types) override;
  void RequestPaintImpl() override;

 private:
  SDLWindowedAppContext& sdl_app_context() const {
    return static_cast<SDLWindowedAppContext&>(app_context());
  }

  // Performs the common close choreography (OnBeforeClose, native destroy,
  // OnAfterClose). Used by both RequestCloseImpl and the close-requested
  // event handler.
  void PerformClose();
  void DestroySDLWindow();

  void ApplyCursorVisibilityNow();
  void RearmCursorAutoHideTimer();

  // Ratio between SDL window coordinates and the physical pixels listeners
  // expect. Never zero.
  float GetPixelDensity() const;

  SDL_Window* sdl_window_ = nullptr;
  SDL_WindowID sdl_window_id_ = 0;
  std::atomic<bool> paint_pending_{false};
  // Auto-hide cursor bookkeeping (CursorVisibility::kAutoHidden).
  SDL_TimerID cursor_hide_timer_ = 0;
};

}  // namespace ui
}  // namespace rex
