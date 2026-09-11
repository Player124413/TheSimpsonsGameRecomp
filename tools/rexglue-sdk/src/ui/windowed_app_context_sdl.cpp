/**
 *******************************************************************************
 * ReXGlue : Xbox 360 Recompilation SDK
 *******************************************************************************
 * SDL3 implementation of the windowed app UI loop context (Android).
 *
 * Derived from the upstream SDL app context (Tom Clay, 2026, BSD 3-Clause).
 *
 * @added      Android port support, 2026
 */

#include <rex/ui/windowed_app_context_sdl.h>

#include <cstdlib>
#include <string>
#include <vector>

#include <SDL3/SDL.h>

#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/ui/window_sdl.h>

namespace rex {
namespace ui {

SDLWindowedAppContext::~SDLWindowedAppContext() {
  // Execute leftover pending functions before the loop machinery goes away,
  // mirroring the shutdown contract documented in WindowedAppContext.
  ExecutePendingFunctionsFromUIThread();
  if (SDL_WasInit(SDL_INIT_VIDEO)) {
    SDL_QuitSubSystem(SDL_INIT_VIDEO);
  }
}

bool SDLWindowedAppContext::Initialize() {
  if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) {
    REXLOG_ERROR("SDL_InitSubSystem(SDL_INIT_VIDEO) failed: {}", SDL_GetError());
    return false;
  }
  const char* video_driver_in_use = SDL_GetCurrentVideoDriver();
  REXLOG_INFO("SDL video driver: {}", video_driver_in_use ? video_driver_in_use : "unknown");
  uint32_t first = SDL_RegisterEvents(2);
  if (first == 0) {
    REXLOG_ERROR("SDL_RegisterEvents failed: {}", SDL_GetError());
    return false;
  }
  wakeup_event_type_ = first;
  paint_event_type_ = first + 1;
  return true;
}

void SDLWindowedAppContext::NotifyUILoopOfPendingFunctions() {
  // SDL_PushEvent is thread-safe by SDL contract.
  SDL_Event event{};
  event.type = wakeup_event_type_;
  SDL_PushEvent(&event);
}

void SDLWindowedAppContext::PlatformQuitFromUIThread() {
  // RunMainMessageLoop re-checks HasQuitFromUIThread after every event; a
  // wakeup guarantees SDL_WaitEvent returns promptly if the queue is empty.
  NotifyUILoopOfPendingFunctions();
}

int SDLWindowedAppContext::RunMainMessageLoop() {
  while (!HasQuitFromUIThread()) {
    SDL_Event event;
    if (!SDL_WaitEvent(&event)) {
      REXLOG_ERROR("SDL_WaitEvent failed: {}", SDL_GetError());
      return EXIT_FAILURE;
    }
    ProcessEvent(event);
  }
  return EXIT_SUCCESS;
}

void SDLWindowedAppContext::ProcessEvent(SDL_Event& event) {
  if (event.type == wakeup_event_type_) {
    ExecutePendingFunctionsFromUIThread();
    return;
  }
  if (event.type == paint_event_type_) {
    // Give an already queued quit request priority over rendering: entering
    // a paint during teardown can block on a surface that is going away.
    SDL_PumpEvents();
    SDL_Event quit_event{};
    if (SDL_PeepEvents(&quit_event, 1, SDL_GETEVENT, SDL_EVENT_QUIT, SDL_EVENT_QUIT) > 0) {
      ProcessEvent(quit_event);
      if (HasQuitFromUIThread()) {
        return;
      }
    }
    if (WindowSDL* window = GetWindow(event.user.windowID)) {
      window->HandlePaintEvent();
    }
    return;
  }
  if (event.type >= SDL_EVENT_WINDOW_FIRST && event.type <= SDL_EVENT_WINDOW_LAST) {
    if (WindowSDL* window = GetWindow(event.window.windowID)) {
      window->HandleWindowEvent(event);
    }
    return;
  }
  switch (event.type) {
    case SDL_EVENT_QUIT:
      if (synchronously_handled_quit_events_ != 0) {
        --synchronously_handled_quit_events_;
      } else {
        ProcessQuitRequest();
      }
      break;
    case SDL_EVENT_KEY_DOWN:
    case SDL_EVENT_KEY_UP: {
      if (WindowSDL* window = GetWindow(event.key.windowID)) {
        window->HandleKeyEvent(event);
      }
      break;
    }
    case SDL_EVENT_MOUSE_MOTION: {
      if (WindowSDL* window = GetWindow(event.motion.windowID)) {
        window->HandleMouseEvent(event);
      }
      break;
    }
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP: {
      if (WindowSDL* window = GetWindow(event.button.windowID)) {
        window->HandleMouseEvent(event);
      }
      break;
    }
    case SDL_EVENT_MOUSE_WHEEL: {
      if (WindowSDL* window = GetWindow(event.wheel.windowID)) {
        window->HandleMouseEvent(event);
      }
      break;
    }
    default:
      break;
  }
}

void SDLWindowedAppContext::ProcessQuitRequest() {
  // Use the normal close-request path rather than terminating the message loop
  // directly. Window listeners use OnClosing to stop guest, audio and GPU
  // threads; bypassing it leaves the process hanging during teardown.
  std::vector<SDL_WindowID> window_ids;
  window_ids.reserve(windows_.size());
  for (const auto& [id, window] : windows_) {
    (void)window;
    window_ids.push_back(id);
  }
  for (SDL_WindowID id : window_ids) {
    if (WindowSDL* window = GetWindow(id)) {
      SDL_Event close_event{};
      close_event.type = SDL_EVENT_WINDOW_CLOSE_REQUESTED;
      close_event.window.windowID = id;
      window->HandleWindowEvent(close_event);
    }
  }
}

}  // namespace ui
}  // namespace rex
