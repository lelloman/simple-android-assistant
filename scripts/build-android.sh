#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.0.12077973}"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
args=()
IFS=',' read -ra abis <<< "${ASSISTANT_ABIS:-arm64-v8a,armeabi-v7a,x86_64,x86}"
for abi in "${abis[@]}"; do args+=(-t "$abi"); done
cargo ndk "${args[@]}" --platform 24 -o android/assistant-core/build/generated/assistant/jniLibs build --locked --release -p assistant-jni
