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
#     the app's onboarding screen picks the folder on first launch.
#
# Usage:
#   ./scripts/build-android.sh [--release] [--install]
#
#   --release   assembleRelease instead of assembleDebug (default). Release
#               builds come out unsigned unless signing is configured.
#   --install   additionally install the APK on the connected device
#               (adb from ANDROID_HOME/platform-tools is used).
#
# Output:
#   android/app/build/outputs/apk/{debug|release}/app-{debug|release}.apk
#
# Signing: debug builds are signed with the debug key automatically. For a
# release build, wire the standard AGP signingConfigs into
# android/app/build.gradle.kts from uncommitted local properties.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}/android"

BUILD_TYPE="debug"
DO_INSTALL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) BUILD_TYPE="release"; shift ;;
    --install) DO_INSTALL=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1 (use --help)" >&2; exit 1 ;;
  esac
done

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
unzip -l "${APK}" 'lib/*' | grep -E "libmain|librexruntime|libc\+\+_shared" || true

# Install -------------------------------------------------------------------
if [[ "${DO_INSTALL}" == 1 ]]; then
  ADB="${ANDROID_HOME}/platform-tools/adb"
  [[ -x "${ADB}" ]] || { echo "adb not found at ${ADB}" >&2; exit 1; }
  echo "Installing..."
  "${ADB}" install -r "${APK}"
fi
