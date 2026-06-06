#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "$HOME/.local/jdks/jdk-21.0.11+10" ]]; then
    export JAVA_HOME="$HOME/.local/jdks/jdk-21.0.11+10"
  fi
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
fi

echo "==> Gradle assembleDebug"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --continue

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found at $APK" >&2
  exit 1
fi

echo "==> APK package check"
aapt dump badging "$APK" | grep -E "package:|application-label:|sdkVersion:|targetSdkVersion:"

echo "==> IME service present"
aapt dump xmltree "$APK" AndroidManifest.xml | grep -A2 "GrokVoiceInputMethodService" | head -6

echo "==> All checks passed"
echo "APK: $APK"