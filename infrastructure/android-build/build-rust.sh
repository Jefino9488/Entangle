#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$task_sdk" && -f "$task_root/android/local.properties" ]]; then
    task_sdk="$(sed -n 's/^sdk.dir=//p' "$task_root/android/local.properties")"
fi
if [[ -z "$task_sdk" ]]; then
    echo 'Set ANDROID_HOME or sdk.dir in android/local.properties.' >&2
    exit 1
fi
export ANDROID_NDK_HOME="$task_sdk/ndk/28.2.13676358"
export ANDROID_NDK="$ANDROID_NDK_HOME"
# Bound native compiler concurrency and keep optional llama.cpp CPU-only.
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-4}"
export LLAMA_LIB_PROFILE=Release
export GGML_OPENMP=OFF
# Portable x86_64 emulator binaries must not assume the build host's AVX features.
export GGML_AVX=OFF
export GGML_AVX2=OFF
export GGML_AVX512=OFF
export GGML_FMA=OFF
export GGML_F16C=OFF
export GGML_BMI2=OFF
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "Install NDK 28.2.13676358 in $task_sdk/ndk." >&2
    exit 1
fi
command -v cargo-ndk >/dev/null || { echo 'Install cargo-ndk 4.1.2 (see docs/development-setup.md).' >&2; exit 1; }
IFS=',' read -ra task_abis <<< "${1:-arm64-v8a,x86_64}"
task_targets=()
for task_abi in "${task_abis[@]}"; do
    case "$task_abi" in
        arm64-v8a|x86_64) task_targets+=(-t "$task_abi") ;;
        *) echo "Unsupported ABI: $task_abi" >&2; exit 1 ;;
    esac
done
cd "$task_root/rust"
cargo build --locked -p entangle-android --features bindgen
mkdir -p "$task_root/android/app/src/main/kotlin"
target/debug/uniffi-bindgen generate \
    --library target/debug/libentangle_android.so --language kotlin \
    --no-format \
    --config android-bindings/uniffi.toml \
    --out-dir "$task_root/android/app/src/main/kotlin"
cargo ndk "${task_targets[@]}" --platform 26 \
    -o "$task_root/android/app/src/main/jniLibs" \
    build --locked --release -p entangle-android
