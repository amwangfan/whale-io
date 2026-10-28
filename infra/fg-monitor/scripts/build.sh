#!/usr/bin/env bash
set -euo pipefail
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY no_proxy NO_PROXY || true
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:?请先设置 JAVA_HOME（JDK 17）}
export ANDROID_HOME=${ANDROID_HOME:?请先设置 ANDROID_HOME（Android SDK 路径）}
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
if ! grep -q "sdk.dir=" local.properties 2>/dev/null; then
  echo "sdk.dir=$ANDROID_HOME" > local.properties
fi
./gradlew :app:assembleRootDebug :app:assembleUserDebug --no-daemon "$@"
echo "APKs:"
ls -la app/build/outputs/apk/*/*/app-*-debug.apk 2>/dev/null || ls -la app/build/outputs/apk/**/*.apk
