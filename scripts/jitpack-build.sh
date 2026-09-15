#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v rustup >/dev/null; then
  rust_installer=$(mktemp)
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o "$rust_installer"
  sh "$rust_installer" -y --profile minimal --default-toolchain 1.96.0
fi
export PATH="$HOME/.cargo/bin:$PATH"
rustup show
if ! cargo ndk --version | grep -q '4.1.2'; then cargo install cargo-ndk --version 4.1.2 --locked; fi
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -d "$sdk_root/ndk/27.0.12077973" ]]; then
  sdk_manager=$(command -v sdkmanager || true)
  if [[ -z "$sdk_manager" ]]; then sdk_manager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"; fi
  "$sdk_manager" 'ndk;27.0.12077973' 'platforms;android-36'
fi
export ANDROID_NDK_HOME="$sdk_root/ndk/27.0.12077973"
./gradlew clean check publishToMavenLocal
