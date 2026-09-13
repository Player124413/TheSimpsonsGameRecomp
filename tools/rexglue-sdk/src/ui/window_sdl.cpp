/**
 *******************************************************************************
 * ReXGlue : Xbox 360 Recompilation SDK
 *******************************************************************************
 * SDL3 implementation of the Window abstraction (Android).
 *
 * Derived from the upstream SDL window backend (Tom Clay, 2026, BSD 3-Clause;
 * itself derived from Xenia's window_win.cc by Ben Vanik, 2020), adapted to
 * this SDK revision's Window API and focused on the Android platform.
 *
 * @added      Android port support, 2026
 */

#include <rex/ui/window_sdl.h>

#include <cstdint>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/ui/flags.h>
#include <rex/ui/menu_item.h>
#include <rex/ui/sdl_virtual_key.h>
#include <rex/ui/surface_android.h>

namespace rex {
namespace ui {

namespace {

uint32_t ResolveWindowWidth(uint32_t requested_width) {
  if (REXCVAR_GET(window_width) > 0) {
    return uint32_t(REXCVAR_GET(window_width));
  }
  return requested_width;
}

uint32_t ResolveWindowHeight(uint32_t requested_height) {
  if (REXCVAR_GET(window_height) > 0) {
    return uint32_t(REXCVAR_GET(window_height));
  }
  return requested_height;
}

// SDL timer callback (runs on SDL's timer thread): defer the actual hide to
// the UI thread. The deferred function only touches the global SDL cursor and
// the window's nonvirtual cursor-visibility getter; the window owns the timer
// and removes it before destroying the SDL window.
Uint32 CursorAutoHideTimerCallback(void* userdata, SDL_TimerID timer_id, Uint32 interval) {
  (void)timer_id;
  (void)interval;
  auto* window = static_cast<WindowSDL*>(userdata);
  window->app_context().CallInUIThreadDeferred([window] {
    if (window->GetCursorVisibility() == Window::CursorVisibility::kAutoHidden) {
      SDL_HideCursor();
    }
  });
  return 0;  // One-shot.
}

MouseEvent::Button TranslateSDLMouseButton(Uint8 button) {
  switch (button) {
    case SDL_BUTTON_LEFT:
      return MouseEvent::Button::kLeft;
    case SDL_BUTTON_RIGHT:
      return MouseEvent::Button::kRight;
    case SDL_BUTTON_MIDDLE:
      return MouseEvent::Button::kMiddle;
    case SDL_BUTTON_X1:
      return MouseEvent::Button::kX1;
    case SDL_BUTTON_X2:
      return MouseEvent::Button::kX2;
    default:
      return MouseEvent::Button::kNone;
  }
}

}  // namespace

std::unique_ptr<Window> Window::Create(WindowedAppContext& app_context,
                                       const std::string_view title, uint32_t desired_logical_width,
                                       uint32_t desired_logical_height) {
  desired_logical_width = ResolveWindowWidth(desired_logical_width);
  desired_logical_height = ResolveWindowHeight(desired_logical_height);
  return std::make_unique<WindowSDL>(app_context, title, desired_logical_width,
                                     desired_logical_height);
}

WindowSDL::WindowSDL(WindowedAppContext& app_context, const std::string_view title,
                     uint32_t desired_logical_width, uint32_t desired_logical_height)
    : Window(app_context, title, desired_logical_width, desired_logical_height) {}

WindowSDL::~WindowSDL() {
  EnterDestructor();
  DestroySDLWindow();
}

bool WindowSDL::OpenImpl() {
  // On Android the SDL window attaches to the activity's SurfaceView; the
  // requested size is a hint only (the compositor decides the real size,
  // reported back through SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED).
  SDL_WindowFlags flags =
      SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_HIDDEN;
  int initial_width = int(SizeToPhysical(GetDesiredLogicalWidth()));
  int initial_height = int(SizeToPhysical(GetDesiredLogicalHeight()));
  sdl_window_ = SDL_CreateWindow(GetTitle().c_str(), initial_width, initial_height, flags);
  if (!sdl_window_) {
    REXLOG_ERROR("SDL_CreateWindow failed: {}", SDL_GetError());
    return false;
  }
#if REX_PLATFORM_ANDROID
  // On Android the window is backed by the activity's SurfaceView; make it
  // immersive fullscreen so the game renders edge-to-edge without system bars.
  SDL_SetWindowFullscreen(sdl_window_, true);
#endif
  sdl_window_id_ = SDL_GetWindowID(sdl_window_);
  sdl_app_context().RegisterWindow(sdl_window_id_, this);

  ApplyCursorVisibilityNow();
  SDL_ShowWindow(sdl_window_);

  // Actualize state for the common Window code. Listener dispatch is handled
  // by Window::Open after OpenImpl returns; these only record initial state.
  int pixel_width = 0;
  int pixel_height = 0;
  SDL_GetWindowSizeInPixels(sdl_window_, &pixel_width, &pixel_height);
  WindowDestructionReceiver destruction_receiver(this);
  OnActualSizeUpdate(uint32_t(pixel_width), uint32_t(pixel_height), destruction_receiver);
  if (destruction_receiver.IsWindowDestroyed()) {
    return true;
  }
  if (SDL_GetWindowFlags(sdl_window_) & SDL_WINDOW_INPUT_FOCUS) {
    OnFocusUpdate(true, destruction_receiver);
  }
  return true;
}

void WindowSDL::RequestCloseImpl() {
  PerformClose();
}

void WindowSDL::PerformClose() {
  WindowDestructionReceiver destruction_receiver(this);
  OnBeforeClose(destruction_receiver);
  if (destruction_receiver.IsWindowDestroyed()) {
    return;
  }
  DestroySDLWindow();
  OnAfterClose();
}

void WindowSDL::DestroySDLWindow() {
  if (cursor_hide_timer_) {
    SDL_RemoveTimer(cursor_hide_timer_);
    cursor_hide_timer_ = 0;
  }
  if (sdl_window_) {
    sdl_app_context().UnregisterWindow(sdl_window_id_);
    SDL_DestroyWindow(sdl_window_);
    sdl_window_ = nullptr;
    sdl_window_id_ = 0;
  }
}

void* WindowSDL::GetNativeWindowHandle() const {
  if (!sdl_window_) {
    return nullptr;
  }
  SDL_PropertiesID props = SDL_GetWindowProperties(sdl_window_);
  return SDL_GetPointerProperty(props, SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, nullptr);
}

float WindowSDL::GetPixelDensity() const {
  float density = sdl_window_ ? SDL_GetWindowPixelDensity(sdl_window_) : 1.0f;
  return density > 0.0f ? density : 1.0f;
}

uint32_t WindowSDL::GetLatestDpiImpl() const {
  float scale = sdl_window_ ? SDL_GetWindowDisplayScale(sdl_window_)
                            : SDL_GetDisplayContentScale(SDL_GetPrimaryDisplay());
  if (scale <= 0.0f) {
    return GetMediumDpi();
  }
  return uint32_t(scale * float(GetMediumDpi()) + 0.5f);
}

void WindowSDL::ApplyNewFullscreen() {
  if (!sdl_window_) {
    return;
  }
  SDL_SetWindowFullscreen(sdl_window_, IsFullscreen());
}

void WindowSDL::ApplyNewTitle() {
  if (!sdl_window_) {
    return;
  }
  SDL_SetWindowTitle(sdl_window_, GetTitle().c_str());
}

void WindowSDL::ApplyNewMouseCapture() {
  SDL_CaptureMouse(true);
}

void WindowSDL::ApplyNewMouseRelease() {
  SDL_CaptureMouse(false);
}

void WindowSDL::ApplyNewCursorVisibility(CursorVisibility old_cursor_visibility) {
  (void)old_cursor_visibility;
  ApplyCursorVisibilityNow();
}

void WindowSDL::ApplyCursorVisibilityNow() {
  switch (GetCursorVisibility()) {
    case CursorVisibility::kVisible:
      if (cursor_hide_timer_) {
        SDL_RemoveTimer(cursor_hide_timer_);
        cursor_hide_timer_ = 0;
      }
      SDL_ShowCursor();
      break;
    case CursorVisibility::kHidden:
      if (cursor_hide_timer_) {
        SDL_RemoveTimer(cursor_hide_timer_);
        cursor_hide_timer_ = 0;
      }
      SDL_HideCursor();
      break;
    case CursorVisibility::kAutoHidden:
      // Hide immediately (see the contract in window.h: switching to
      // kAutoHidden hides instantly, e.g. when entering fullscreen); the
      // mouse-motion handler reveals the cursor and re-arms the timer.
      SDL_HideCursor();
      break;
  }
}

void WindowSDL::RearmCursorAutoHideTimer() {
  if (cursor_hide_timer_) {
    SDL_RemoveTimer(cursor_hide_timer_);
  }
  cursor_hide_timer_ =
      SDL_AddTimer(kDefaultCursorAutoHideMilliseconds, CursorAutoHideTimerCallback, this);
}

void WindowSDL::FocusImpl() {
  if (!sdl_window_) {
    return;
  }
  SDL_RaiseWindow(sdl_window_);
}

std::unique_ptr<Surface> WindowSDL::CreateSurfaceImpl(Surface::TypeFlags allowed_types) {
  if (!sdl_window_) {
    return nullptr;
  }
#if REX_PLATFORM_ANDROID
  if (allowed_types & Surface::kTypeFlag_AndroidNativeWindow) {
    SDL_PropertiesID props = SDL_GetWindowProperties(sdl_window_);
    // NOTE: depending on the SDL3 revision, the property is either
    // SDL_PROP_WINDOW_ANDROID_NATIVE_WINDOW_POINTER or
    // SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER ("SDL.window.android.window");
    // resolve whichever exists at compile time, falling back to the raw
    // property name.
#ifdef SDL_PROP_WINDOW_ANDROID_NATIVE_WINDOW_POINTER
    auto* native_window = static_cast<ANativeWindow*>(SDL_GetPointerProperty(
        props, SDL_PROP_WINDOW_ANDROID_NATIVE_WINDOW_POINTER, nullptr));
#elif defined(SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER)
    auto* native_window =
        static_cast<ANativeWindow*>(SDL_GetPointerProperty(props, SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, nullptr));
#else
    auto* native_window = static_cast<ANativeWindow*>(
        SDL_GetPointerProperty(props, "SDL.window.android.window", nullptr));
#endif
    if (native_window) {
      return std::make_unique<AndroidNativeWindowSurface>(native_window);
    }
  }
#endif
  return nullptr;
}

void WindowSDL::RequestPaintImpl() {
  // Coalesce: at most one queued paint event at a time. Callable from non-UI
  // threads; SDL_PushEvent is thread-safe.
  if (paint_pending_.exchange(true, std::memory_order_acq_rel)) {
    return;
  }
  SDL_Event event{};
  event.type = sdl_app_context().paint_event_type();
  event.user.windowID = sdl_window_id_;
  SDL_PushEvent(&event);
}

void WindowSDL::HandlePaintEvent() {
  paint_pending_.store(false, std::memory_order_release);
  OnPaint();
}

void WindowSDL::HandleWindowEvent(SDL_Event& event) {
  WindowDestructionReceiver destruction_receiver(this);
  switch (event.type) {
    case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
      OnActualSizeUpdate(uint32_t(event.window.data1), uint32_t(event.window.data2),
                         destruction_receiver);
      break;
    case SDL_EVENT_WINDOW_RESIZED: {
      // Track the user-driven size as the desired size for the normal state
      // only (mirrors the Win32 WM_SIZE handling).
      SDL_WindowFlags flags = SDL_GetWindowFlags(sdl_window_);
      if (!(flags & (SDL_WINDOW_MAXIMIZED | SDL_WINDOW_FULLSCREEN | SDL_WINDOW_MINIMIZED))) {
        OnDesiredLogicalSizeUpdate(SizeToLogical(uint32_t(event.window.data1)),
                                   SizeToLogical(uint32_t(event.window.data2)));
      }
      break;
    }
    case SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED: {
      UISetupEvent e(this);
      OnDpiChanged(e, destruction_receiver);
      break;
    }
    case SDL_EVENT_WINDOW_DISPLAY_CHANGED: {
      MonitorUpdateEvent e(this, true);
      OnMonitorUpdate(e);
      break;
    }
    case SDL_EVENT_WINDOW_FOCUS_GAINED:
      OnFocusUpdate(true, destruction_receiver);
      break;
    case SDL_EVENT_WINDOW_FOCUS_LOST:
      OnFocusUpdate(false, destruction_receiver);
      break;
    case SDL_EVENT_WINDOW_EXPOSED:
      // The platform cannot retain the previous image; force the paint.
      OnPaint(true);
      break;
    case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
      // SDL destroys nothing on its own. On Android this arrives when the
      // activity is being destroyed (SDLActivity surfaceDestroyed / quit).
      PerformClose();
      break;
    default:
      break;
  }
}

void WindowSDL::HandleKeyEvent(SDL_Event& event) {
  VirtualKey virtual_key = TranslateSDLScancode(event.key.scancode);
  if (virtual_key == VirtualKey::kNone) {
    return;
  }
  SDL_Keymod mod = event.key.mod;
  KeyEvent e(this, virtual_key, /*repeat_count=*/1,
             /*prev_state=*/event.key.repeat,
             /*modifier_shift_pressed=*/(mod & SDL_KMOD_SHIFT) != 0,
             /*modifier_ctrl_pressed=*/(mod & SDL_KMOD_CTRL) != 0,
             /*modifier_alt_pressed=*/(mod & SDL_KMOD_ALT) != 0,
             /*modifier_super_pressed=*/(mod & SDL_KMOD_GUI) != 0);
  WindowDestructionReceiver destruction_receiver(this);
  if (event.type == SDL_EVENT_KEY_DOWN) {
    OnKeyDown(e, destruction_receiver);
  } else {
    OnKeyUp(e, destruction_receiver);
  }
}

void WindowSDL::HandleMouseEvent(SDL_Event& event) {
  // SDL3 reports float window coordinates; listeners expect physical pixels.
  float density = GetPixelDensity();
  WindowDestructionReceiver destruction_receiver(this);
  switch (event.type) {
    case SDL_EVENT_MOUSE_MOTION: {
      if (GetCursorVisibility() == CursorVisibility::kAutoHidden) {
        SDL_ShowCursor();
        RearmCursorAutoHideTimer();
      }
      MouseEvent e(this, MouseEvent::Button::kNone, int32_t(event.motion.x * density),
                   int32_t(event.motion.y * density));
      OnMouseMove(e, destruction_receiver);
      break;
    }
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP: {
      MouseEvent e(this, TranslateSDLMouseButton(event.button.button),
                   int32_t(event.button.x * density), int32_t(event.button.y * density));
      if (event.type == SDL_EVENT_MOUSE_BUTTON_DOWN) {
        OnMouseDown(e, destruction_receiver);
      } else {
        OnMouseUp(e, destruction_receiver);
      }
      break;
    }
    case SDL_EVENT_MOUSE_WHEEL: {
      MouseEvent e(this, MouseEvent::Button::kNone, int32_t(event.wheel.mouse_x * density),
                   int32_t(event.wheel.mouse_y * density),
                   int32_t(event.wheel.x * float(MouseEvent::kScrollPerDetent)),
                   int32_t(event.wheel.y * float(MouseEvent::kScrollPerDetent)));
      OnMouseWheel(e, destruction_receiver);
      break;
    }
    default:
      break;
  }
}

namespace {
// The SDL/Android backend has no platform menu bar (desktop menus are
// replaced by the in-app touch overlay), but the core MenuItem helpers in
// menu_item.cpp delegate to this backend factory, so it must exist for the
// link. The base class already implements the full child-list bookkeeping.
class SdlMenuItem : public MenuItem {
 public:
  SdlMenuItem(Type type, const std::string& text, const std::string& hotkey,
              std::function<void()> callback)
      : MenuItem(type, text, hotkey, std::move(callback)) {}
};
}  // namespace

std::unique_ptr<ui::MenuItem> MenuItem::Create(Type type, const std::string& text,
                                               const std::string& hotkey,
                                               std::function<void()> callback) {
  return std::make_unique<SdlMenuItem>(type, text, hotkey, std::move(callback));
}

}  // namespace ui
}  // namespace rex
