#!/usr/bin/env bash
set -euo pipefail

UDID="$(
  xcrun simctl list devices available |
    grep -E '^\s+iPhone' |
    head -1 |
    grep -Eo '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' |
    head -1
)"

if [[ -z "${UDID}" ]]; then
  echo "No available iPhone simulator found for SPM unit tests"
  exit 1
fi

echo "Running NativeAudioPluginTests on iOS Simulator ${UDID}"

xcodebuild test \
  -scheme CapgoCapacitorNativeAudio \
  -destination "platform=iOS Simulator,id=${UDID}" \
  -only-testing:NativeAudioPluginTests/QueueSnapshotRoutingTests
