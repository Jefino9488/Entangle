# Entangle

An offline-first Android research platform built with Kotlin/Jetpack Compose and Rust. Import your sources, read and annotate them, follow cross-document references, explore a source-backed concept graph, and ask questions using local evidence or an optional on-device language model.

All product screens operate on imported data. No sample library, demo passages, fabricated graph or canned assistant responses are shipped. The application has no Internet permission, account requirement, analytics, or cloud backend.

## Working MVP

- **Library:** Android document picker imports PDF, EPUB, DOCX, UTF-8 text, Markdown and HTML into private local storage. WorkManager schedules checkpointed extraction/indexing with pause/resume, error reporting and retry. Search spans the indexed library.
- **Reader:** bounded native PDF page rendering and selectable extracted text; EPUB/DOCX/text/Markdown/HTML passage reading; page/passage navigation, contents, document search, reading-position persistence, highlights, notes and bookmarks.
- **References:** selecting an extracted concept retrieves actual mentions from other documents. Phone sheets and tablet side panels contain independent list/preview navigation, provenance and source locations. They preserve the primary reader position.
- **Knowledge:** canonical concepts, aliases and mentions are indexed from source text. Graph edges describe **observed co-occurrence**, with separate source-specific assertions and evidence. They do not silently turn similarity into a verified semantic fact.
- **Assistant:** evidence mode searches and compares exact source excerpts without a model. Optional local GGUF inference uses llama.cpp, bounded context/output and valid retrieved source citations. Models are imported through SAF and unloaded after each request.
- **Storage:** Rust owns SQLite/FTS5 and transactional migrations. Documents, editions, passages, mentions, relationships, assertions, evidence, reading positions and annotations remain separate entities.

## Build and run

See [development setup](docs/development-setup.md) for the pinned SDK/NDK/Rust dependencies.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk # adapt to your host
android/gradlew -p android assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.entangle.app/.MainActivity
```

The debug APK includes `arm64-v8a` and `x86_64`. Use `-PrustAbis=arm64-v8a` or `-PrustAbis=x86_64` for one ABI. Gradle generates the Kotlin UniFFI bindings and compiles the Rust/SQLite/llama.cpp libraries. Linux builds require CMake, Clang/libclang, cargo-ndk 4.1.2, Rust 1.96.1 and NDK 28.2.13676358.

```bash
cd rust
cargo test --workspace --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
cargo fmt --all --check
cd ..
android/gradlew -p android ktlintCheck lintDebug
infrastructure/android-build/verify-device.sh emulator-5554
```

A genuine model smoke test can be run separately; it never downloads a model automatically:

```bash
cd rust
ENTANGLE_TEST_MODEL=/absolute/path/model.gguf cargo test -p entangle-inference --locked -- --include-ignored
```

## Repository

```text
android/app/                Native Android app with layered domain/data/core/feature/UI packages
rust/entangle-core/         Parsers, chunking, checkpointing, SQLite/FTS5, concepts, graph and annotations
rust/android-bindings/     UniFFI DTOs and Kotlin-facing storage/inference APIs
rust/entangle-inference/    Modular optional llama.cpp GGUF adapter
infrastructure/android-build/  Builds, reference checkout, dependency audit and device tests
models/                    Model policy and tested candidate; no committed weights
docs/                      Architecture, data model, setup, decisions, dependency audit and results
Refrence/Ferrous/           Intact upstream reference clone, excluded from this repository
```

## Current limits

Sources must be at most 64 MiB; extraction is bounded to 64 MiB of text, 10,000 passages and 128 MiB expanded archives. Oversized or unsupported content fails visibly. Image-only/scanned PDFs can be rendered, but text indexing requires OCR, which is not included in this MVP. Protected PDFs/EPUB DRM are not bypassed. Text input is UTF-8; automatic legacy-encoding detection is not included.

Graph relations currently mean observed co-occurrence or shared concept mentions. They are not a fully inferred domain ontology. The local-model adapter accepts supported instruction GGUFs below 512 MiB, uses a 2,048-token context and at most 256 output tokens, and permits one request at a time. Citation validation establishes source identity; readers should inspect the supporting passages when assessing generated claims.

Removing the app removes its private library; keep original source documents. Duplicate imports create separate entries. Highlights retain exact quotes and passage identity; repeated identical quote text in a passage is highlighted at each matching occurrence.

See [architecture](docs/architecture.md), [data model](docs/data-model.md), [dependency review](docs/dependencies.md), [local inference](docs/local-inference.md), [build results](docs/build-results.md), and [base audit](docs/base-project-review.md).

Ferrous's MIT copyright and license remain in its clone and [docs/ferrous-LICENSE](docs/ferrous-LICENSE). No Ferrous implementation is copied; its Flutter/Hive stack is a reader reference. The new Entangle source distribution license remains unselected; dependency notices do not automatically license unrelated new source.
