# Working MVP validation results

Validated on 2026-11-21. Source changes are pushed to https://github.com/Jefino9488/Entangle/tree/main. The deployed app has no demo library, fabricated graph or canned assistant responses.

## APK

App ID `dev.entangle.app`, version 0.1.0, minimum SDK 26, compile/target SDK 36. The debug APK includes arm64-v8a and x86_64 with actual Rust parsers, SQLite and llama.cpp.

- Build: `android/gradlew -p android assembleDebug` passed.
- Local artifact: `artifacts/entangle-mvp-debug.apk`.
- Size: 37,028,953 bytes, approximately 35.3 MiB.
- SHA-256: `3dda2c194a35fdaed7ed725a9502392d24302f6048b7160251243e6121114ed9`.
- APK v2 signature, ZIP alignment and all packaged native ELF LOAD segments passed 16 KiB alignment checks.
- Installed on the Redmi K50i and API 35 x86_64 emulator. Model weights are separate private files, excluded from APK and Git.

## Checks

| Check | Result |
| --- | --- |
| Rust workspace tests | 15 passed; genuine-model test skipped in default run |
| Rust core | 10 passed: real parsers, migration, UTF-8 offsets, limits, cross-connection pause, restart/resume, FTS, graph evidence and annotations |
| Explicit genuine-model host run | All 6 inference tests passed, including generation, valid citations, invented-ID rejection and fair evidence budgets |
| Rust formatting and Clippy | Passed; Clippy used all targets/features and `-D warnings` |
| Android APK and test APK builds | Passed |
| Kotlin formatting | ktlintCheck passed |
| Android lint | Passed, 0 errors and 14 warnings, mostly newer dependency notices and minor compatibility/style suggestions |
| Phone-window emulator | 5 passed: real native source processing, system picker/local copy, graph/assistant/source navigation, selected-text annotations and sheet scroll preservation |
| Tablet-window emulator | Side-panel preview/collapse preserved primary PDF scroll |
| Redmi K50i, observed model 22041216I / SDK 37 | Real native processing and genuine local-model generation both passed |

Phone emulation used 1080×1920 at 420 dpi; tablet emulation used 1920×1200 at 240 dpi. The verification script restores display overrides and rejects physical-device serials for layout changes. Fixtures are real generated documents in test code and are removed after tests.

## Actual inference

The separately provisioned Qwen2.5-0.5B-Instruct Q4_K_M model is 469 MiB, Apache-2.0; revision and checksum are in `models/README.md`. It answered the radial-nerve origin question from an actual imported passage and cited [1].

- Host: 10.9 seconds, approximately 641 MiB peak RSS.
- Redmi K50i: 8,270 ms for the tested answer with a 128-token output cap.
- Sampled phone app PSS: approximately 100–674 MiB. Sampling does not establish an exhaustive peak or a complete hardware benchmark.

The runtime caps one active request, four CPU threads, a 2,048-token context and 256 output tokens. It implements cancellation and releases model/context ownership after the request. Exact source cards accompany generated prose; citation validation establishes source identity rather than automatically verifying every claim.

## Local evidence

Generated reports are in `artifacts/verification/`: `mvp-rust-tests.txt`, `mvp-clippy.txt`, `mvp-android-checks.txt`, `mvp-emulator-matrix.txt`, `emulator-phone.txt`, `emulator-tablet.txt`, `redmi-k50i-mvp-native-model.txt`, `redmi-k50i-model-memory-samples.txt`, `inference-host-model.txt`, `inference-android-build.txt`, `mvp-apk-signature.txt`, and `mvp-native-alignment.txt`. Logs and binaries are excluded from source commits.

## Limits and next milestone

Source/text/passage/archive budgets are 64 MiB / 64 MiB / 10,000 passages / 128 MiB. Oversized inputs fail visibly, preserving the imported source. Scanned PDFs render visually but require OCR for text indexing. Protected/DRM content is not bypassed; text imports assume UTF-8.

Graph edges describe observed co-occurrence and shared mentions. Semantic relationship extraction, embeddings/vector retrieval, OCR, rich EPUB/DOCX layout, PDF coordinates/EPUB CFIs, deduplication and export/recovery require later work. Repeated identical selected quotes are highlighted at matching occurrences in their passage.

Ferrous's reference build remains blocked by its locked Rust 1.97.1 requirement and absent Flutter configuration. Entangle's independent APK is verified. Dependency inventories and the original reference license are preserved; a production release needs a selected new-source license and artifact-specific notices.

The recommended next milestone is OCR and richer source locators, followed by measured semantic extraction/retrieval on the target hardware.
