#!/usr/bin/env bash
#
# build-android.sh - build the Android APK (arm64-v8a) of The Simpsons Game
# recomp.
#
# Prerequisites:
#   - Android SDK with the following installed:
#       * platform-tools
#       * platforms;android-35
#       * cmake;3.31.1
#       * NDK 27.2.12479018 (any NDK r27+ works; adjust ndkVersion in
#         android/app/build.gradle.kts if using a different one)
#   - JDK 17
#   - The game files extracted somewhere on the device (default.xex + data):
#     the app's onboarding screen picks the folder (or installs from the
#     player's own ISO) on first launch.
#
# The build uses the committed generated code (simpsons/generated), exactly
# like the desktop builds - no game dump is needed. Game data is supplied by
# the player on the device (folder pick or on-device ISO install).
#
# Usage:
#   ./scripts/build-android.sh [--release] [--install] [--turnip <zip-or-so>]
#
#   --release   assembleRelease instead of assembleDebug (default). Release
#               builds are signed with the debug key (see build.gradle.kts).
#   --install   additionally install the APK on the connected device
#               (adb from ANDROID_HOME/platform-tools is used).
#   --turnip T  path or URL to a Turnip driver ZIP (any arm64 libvulkan*.so
#               inside) or to a libvulkan*.so itself. The driver is packaged
#               into the APK as libvulkan.turnip.so and becomes selectable
#               under Graphics -> GPU driver (players can also install a
#               driver from a ZIP right in the app, no rebuild needed).
#
# Output:
#   android/app/build/outputs/apk/{debug|release}/app-{debug|release}.apk
#
# Signing: release builds are signed with the debug key so they install
# directly; swap in your own signingConfig for distribution.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}/android"

BUILD_TYPE="debug"
DO_INSTALL=0
TURNIP_SOURCE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) BUILD_TYPE="release"; shift ;;
    --install) DO_INSTALL=1; shift ;;
    --turnip) [[ $# -ge 2 ]] || { echo "--turnip needs an argument" >&2; exit 1; }
           TURNIP_SOURCE="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1 (use --help)" >&2; exit 1 ;;
  esac
done

fetch_to() {  # fetch_to <source-path-or-url> <dest>
  local src="$1" dst="$2"
  if [[ "${src}" == http://* || "${src}" == https:// ]]; then
    curl -fL --retry 3 --retry-delay 5 -o "${dst}" "${src}"
  else
    cp "${src}" "${dst}"
  fi
}

# --- Optional: bundle a Turnip driver ----------------------------------------
if [[ -n "${TURNIP_SOURCE}" ]]; then
  echo "==> Installing the Turnip driver into jniLibs"
  TMPDIR_T="$(mktemp -d)"
  if [[ "${TURNIP_SOURCE}" == *.so ]]; then
    fetch_to "${TURNIP_SOURCE}" "${TMPDIR_T}/libvulkan.turnip.so"
  else
    fetch_to "${TURNIP_SOURCE}" "${TMPDIR_T}/turnip.zip"
    unzip -q -o "${TMPDIR_T}/turnip.zip" -d "${TMPDIR_T}/unzipped"
    SO="$(find "${TMPDIR_T}/unzipped" -name 'libvulkan*.so' -path '*arm64*' | head -n 1)"
    [[ -n "${SO}" ]] || SO="$(find "${TMPDIR_T}/unzipped" -name 'libvulkan*.so' | head -n 1)"
    [[ -n "${SO}" ]] || { echo "no libvulkan*.so inside ${TURNIP_SOURCE}" >&2; exit 1; }
    cp "${SO}" "${TMPDIR_T}/libvulkan.turnip.so"
  fi
  mkdir -p app/src/main/jniLibs/arm64-v8a
  cp "${TMPDIR_T}/libvulkan.turnip.so" app/src/main/jniLibs/arm64-v8a/libvulkan.turnip.so
  ls -lh app/src/main/jniLibs/arm64-v8a/
fi

# Sanity checks -------------------------------------------------------------
[[ -f gradlew ]] || { echo "android/gradlew missing" >&2; exit 1; }
[[ -f ../simpsons/generated/default/sources.cmake ]] || {
  echo "generated code missing (simpsons/generated/default)" >&2; exit 1
}
[[ -f ../tools/rexglue-sdk/CMakeLists.txt ]] || {
  echo "vendored SDK missing (tools/rexglue-sdk)" >&2; exit 1
}

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${ANDROID_HOME}" ]]; then
  for d in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           /usr/local/android-sdk /opt/android-sdk; do
    [[ -d "$d" ]] && export ANDROID_HOME="$d" && break
  done
fi
[[ -n "${ANDROID_HOME}" ]] || {
  echo "ANDROID_HOME not set and no SDK found in the usual locations" >&2; exit 1
}
echo "ANDROID_HOME=${ANDROID_HOME}"

# Build ---------------------------------------------------------------------
CAP="$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}"
chmod +x ./gradlew
./gradlew "assemble${CAP}" --no-daemon

APK="app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"
[[ -f "${APK}" ]] || { echo "APK not found at ${APK}" >&2; exit 1; }

echo
echo "APK: ${ROOT}/android/${APK}"
ls -lh "${APK}"
echo "Packaged native libraries:"
unzip -l "${APK}" 'lib/*' | grep -E "libmain|librexruntime|libc\+\+_shared|libxiso|libvulkan|lib.*hook" || true

# Install -------------------------------------------------------------------
if [[ "${DO_INSTALL}" == 1 ]]; then
  ADB="${ANDROID_HOME}/platform-tools/adb"
  [[ -x "${ADB}" ]] || { echo "adb not found at ${ADB}" >&2; exit 1; }
  echo "Installing..."
  "${ADB}" install -r "${APK}"
fi
