#!/usr/bin/env bash
# Portable, no-admin Android toolchain in ./.toolchain (Git Bash on Windows; also works on Linux/macOS
# with the matching download names). Idempotent: re-running skips what is already there.
#
# Workarounds recorded from the Windows 11 machine this was first built on:
#  - cmdline-tools >= 2026 delegate `sdkmanager` to a native "Android CLI" that Windows Application
#    Control blocked (os error 4551). The Java-based cmdline-tools 13114758 (sdkmanager 19.0) works.
#  - AGP's Maven-downloaded aapt2.exe was likewise blocked, so Gradle is pointed at the SDK's
#    build-tools aapt2 through `android.aapt2FromMavenOverride` in the toolchain-local
#    GRADLE_USER_HOME (never in the repo's gradle.properties: the path is machine-specific).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/.toolchain"
mkdir -p "$T"
cd "$T"

if [ ! -x jdk/bin/java ] && [ ! -f jdk/bin/java.exe ]; then
  curl -sSL -o jdk.zip "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
  rm -rf jdk-tmp && mkdir jdk-tmp && unzip -q jdk.zip -d jdk-tmp && mv jdk-tmp/* jdk && rm -rf jdk-tmp jdk.zip
fi
export JAVA_HOME="$(cygpath -w "$T/jdk" 2>/dev/null || echo "$T/jdk")"

if [ ! -d android-sdk/cmdline-tools/latest ]; then
  curl -sSL -o clt.zip "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
  mkdir -p android-sdk/cmdline-tools && unzip -q clt.zip -d android-sdk/cmdline-tools
  mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest && rm clt.zip
fi

# Relative paths on purpose: the project folder name contains a space, which cmd.exe mangles.
SDKM=./android-sdk/cmdline-tools/latest/bin/sdkmanager.bat
yes | "$SDKM" --sdk_root=android-sdk --licenses > /dev/null 2>&1 || true
"$SDKM" --sdk_root=android-sdk "platform-tools" "platforms;android-36" "platforms;android-37.0" "build-tools;36.0.0" > sdk.log 2>&1

mkdir -p gradle-home
AAPT2="$T/android-sdk/build-tools/36.0.0/aapt2.exe"
if [ -f "$AAPT2" ]; then
  echo "android.aapt2FromMavenOverride=$(cygpath -m "$AAPT2")" > gradle-home/gradle.properties
fi

SDK_PATH="$(cygpath -m "$T/android-sdk" 2>/dev/null || echo "$T/android-sdk")"
echo "sdk.dir=${SDK_PATH/:/\\:}" > "$ROOT/android/local.properties"
echo "toolchain ready in $T"
