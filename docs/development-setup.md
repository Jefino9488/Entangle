# Development setup

Validated host: Linux x86_64. User-facing validation date: 2026-11-21. Android app ID: `dev.entangle.app`. Primary physical target: Redmi K50i, arm64-v8a; emulator target: x86_64.

## Pinned tools

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Gradle | 8.14 via committed wrapper |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.2.20 |
| Android compile/target SDK | 36 |
| Android build tools | 36.0.0 |
| Android minimum SDK | 26 |
| Android NDK | 28.2.13676358 |
| Rust | 1.96.1 via rust/rust-toolchain.toml |
| cargo-ndk | 4.1.2 |
| UniFFI | 0.29.4, part of Cargo.lock |

Android Studio is optional for command-line builds. This host already had JDK 17/21, SDK 36, NDK 28.2, adb, Rust 1.96.1 and KVM. Initialization added SDK command-line tools 19.0, an API 35 Google APIs x86_64 emulator image/AVD, the two Rust Android targets, the pinned Rust toolchain and cargo-ndk 4.1.2. Flutter is not required for Entangle and was not installed.

## Provisioning

Install Android SDK command-line tools through Android Studio or the official Android developer download. Set your SDK path and install:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --licenses
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'platform-tools'
```

Accept SDK licenses according to your own environment. A different installed command-line tools directory is fine; adapt PATH. On this host SDK packages previously installed by a newer Android Studio can emit an SDK XML version warning in tools 19.0; the required packages and emulator were nevertheless provisioned and validated.

```bash
rustup toolchain install 1.96.1 --component rustfmt --component clippy
rustup target add --toolchain 1.96.1 aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
```

Use JDK 17 and set `ANDROID_HOME`, or create ignored `android/local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`. Do not commit host paths or SDK files. The Gradle wrapper downloads its SHA-256-verified distribution on first use. First builds need network access to resolve dependencies; after caches and SDK/model assets are present, the application itself operates offline. Bit-identical signed artifacts are not claimed; tool versions and dependency selections are pinned.

## Builds

From the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
android/gradlew -p android assembleDebug
```

The `buildRust` task builds host UniFFI tooling, regenerates Kotlin source, then uses cargo-ndk to compile bundled SQLite, document parsers and llama.cpp into arm64-v8a and x86_64 native libraries. Install CMake, Clang and libclang development packages on the host; Ubuntu users can use `sudo apt install cmake clang libclang-dev`. Native compiler concurrency is capped at four and the portable CPU build profile is recorded in `rust/.cargo/config.toml`. Host scripts currently target Linux; macOS needs the appropriate host `.dylib` path in the generator script, and Windows needs an equivalent shell/path setup. Do not manually edit generated Kotlin or copy stale binaries.

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. Use `-PrustAbis=arm64-v8a` for a phone-only build or `-PrustAbis=x86_64` for an emulator-only build; Gradle's native ABI filters match that selection. Rust always uses a release-optimized native library, including in the debug APK.

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.entangle.app/.MainActivity
```

For a Rust-only build:

```bash
infrastructure/android-build/build-rust.sh arm64-v8a,x86_64
```

## Formatting and checks

```bash
cd rust
cargo fmt --all
cargo fmt --all --check
cargo test --workspace --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
cd ..
android/gradlew -p android ktlintFormat
android/gradlew -p android ktlintCheck lintDebug
```

The Kotlin formatter excludes UniFFI generated files. Tests exercise mature parsers, SQLite migrations, checkpoint/pause/resume behavior, original UTF-8 offsets, FTS retrieval, real concept/graph evidence, annotations, Kotlin/native integration, system document imports, assistant/source navigation and independent reference layouts. Source fixtures exist only in tests. The local model integration test uses genuine GGUF weights and is skipped when no model is provisioned.

## Emulator and device verification

```bash
sdkmanager 'emulator' 'system-images;android-35;google_apis;x86_64'
avdmanager create avd --name Entangle_API 35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd Entangle_API 35
```

Enable KVM on Linux or the supported virtualization backend on your host. Physical devices need USB debugging and user authorization. A Redmi K50i (observed model 22041216I, SDK 37) was connected and passed the MVP native processing and genuine local-model tests. The API 35 x86_64 emulator verified phone/tablet UI behavior.

```bash
android/gradlew -p android connectedDebugAndroidTest
```

For the validated phone/tablet matrix, use `infrastructure/android-build/verify-device.sh` with exactly one connected emulator. It runs native processing, document-picker import, knowledge/assistant evidence, annotation and compact-panel tests, then an expanded-window panel test; emulator size/density overrides are restored on exit. Test fixture sources and their imported copies are removed by the current tests; unrelated user documents are preserved. Never run emulator size overrides against the user's physical phone.

Connected test reports are under `android/app/build/reports/androidTests/connected/debug/`. See [build results](build-results.md) for the actual completed checks and limitations.

## Optional model and device inference

Import a supported instruction GGUF below 512 MiB through Settings. No model weights are bundled with the APK. The tested candidate, revision, license and checksum are recorded in `models/README.md`; the runtime accepts supported user-selected models rather than requiring this candidate.

```bash
cd rust
ENTANGLE_TEST_MODEL=/absolute/path/model.gguf cargo test -p entangle-inference --locked -- --include-ignored
```

Once a genuine model has been imported on a device, run the model test:

```bash
android/gradlew -p android assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r -e class dev.entangle.app.LocalModelTest dev.entangle.app.test/androidx.test.runner.AndroidJUnitRunner
```

See `docs/local-inference.md` for admission limits, cancellation, citation validation and memory ownership. Regenerate dependency audits with `android/gradlew -p android dependencyInventory` followed by `python3 infrastructure/android-build/inventory-licenses.py`.
