#!/usr/bin/env bash
# THE iOS SDK, AS inapp-kmp's iOS TESTS LINK IT.
#
# The library itself never carries the iOS SDK: an app links it with CocoaPods or
# SwiftPM. A test binary has no app around it, so this builds the iOS SDK's sources
# as a static library for the simulator and prints the linker options that link it:
#
#   opts=$(scripts/ios-test-library.sh)
#   ./gradlew :inapp-kmp:iosSimulatorArm64Test -Pgraine.iosTestLinkerOpts="$opts" [-Pgraine.simulator=<device>]
#
# The iOS SDK is the folder `ios` beside this one: sdk/ios where this library is
# developed, and in a checkout of https://github.com/GraineAI/inapp-kmp a checkout of
# https://github.com/GraineAI/inapp-ios of the same version, cloned beside it as `ios`.
# GRAINE_IOS_SDK_DIR names another place. Output goes to build/ios-test-library
# (git-ignored), or to the folder given as the first argument.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ios="${GRAINE_IOS_SDK_DIR:-$here/../../ios}"
sources="$ios/Sources/GraineInApp"
if [ ! -d "$sources" ]; then
  echo "No iOS SDK sources at $sources." >&2
  echo "Clone https://github.com/GraineAI/inapp-ios (the same version) beside this folder as 'ios', or set GRAINE_IOS_SDK_DIR." >&2
  exit 1
fi
out="${1:-$here/../build/ios-test-library}"
mkdir -p "$out"
files=()
while IFS= read -r -d '' f; do files+=("$f"); done < <(find "$sources" -name '*.swift' -print0)
xcrun --sdk iphonesimulator swiftc \
  -target arm64-apple-ios17.0-simulator \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -swift-version 5 -module-name GraineInApp -parse-as-library \
  -emit-library -static -Onone \
  -o "$out/libGraineInApp.a" "${files[@]}" >&2
echo "-L$out -lGraineInApp -L/usr/lib/swift -rpath /usr/lib/swift"
