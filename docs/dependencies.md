# Dependency and compatibility review

Reviewed 2026-11-21. Kotlin/Android dependencies are explicitly versioned or selected by a pinned Compose BOM; Cargo.lock pins the Rust graph. The [Rust inventory](rust-dependency-inventory.md) records transitive/host/test declarations. The [Android inventory](android-dependency-inventory.md) records resolved runtime/test coordinates and cached POM declarations. Missing inherited license declarations remain marked for source/parent review.

## Introduced dependencies

| Dependency | Version | License | Actual MVP use |
| --- | --- | --- | --- |
| Gradle / Android Gradle Plugin | 8.14 / 8.11.1 | Apache-2.0 | Reproducible SDK 36 build; verified wrapper distribution hash |
| Kotlin / Compose compiler | 2.2.20 | Apache-2.0 | Android application/compiler |
| Compose BOM | 2025.08.01 | Apache-2.0 | Compose UI, Material 3, responsive window classes and UI tests |
| AndroidX Activity | 1.10.1 | Apache-2.0 | SAF document/model picker |
| AndroidX Lifecycle | 2.9.4 | Apache-2.0 | ViewModel/SavedStateHandle/flows |
| AndroidX Navigation | 2.9.4 | Apache-2.0 | Actual library/reader/knowledge/assistant/settings routes |
| AndroidX WorkManager | 2.10.4 | Apache-2.0 | Checkpointed local ingestion scheduling |
| Kotlin coroutines | 1.10.2 | Apache-2.0 | IO calls, cancellation and state observation |
| JNA Android | 5.17.0 | LGPL-2.1-or-later OR Apache-2.0 | Generated UniFFI transport; Apache alternative |
| UniFFI | 0.29.4 | MPL-2.0 | Rust/Kotlin boundary; preserve covered-file notices/source availability |
| rusqlite / libsqlite3-sys | 0.37.0 / 0.35.0 | MIT wrappers; SQLite public domain | Rust-owned bundled SQLite/FTS5 |
| thiserror | 2.0.21 | MIT OR Apache-2.0 | Typed storage/inference errors |
| pdf-extract | 0.12.1 | MIT | Pure-Rust PDF text extraction with actual page provenance |
| rbook | 0.7.13 | Apache-2.0 | EPUB spine/chapter parsing; selected after license evaluation |
| docx-rs | 0.4.18 | MIT | DOCX paragraph/table extraction; image feature disabled |
| scraper | 0.22.0 | ISC | HTML text extraction through mature html5ever ecosystem |
| pulldown-cmark | 0.13.0 | MIT | Markdown parsing |
| unicode-normalization | 0.1.x resolved in Cargo.lock | MIT OR Apache-2.0 | Canonical concept normalization |
| llama-cpp-2 / llama-cpp-sys-2 | 0.1.161 | MIT OR Apache-2.0 bindings; llama.cpp MIT | Real optional local GGUF CPU inference on both Android ABIs |
| tempfile | 3.23.0 | MIT OR Apache-2.0 | Test fixtures and host tooling |
| cargo-ndk | 4.1.2 | MIT OR Apache-2.0 | Android cross-compilation |
| ktlint plugin / ktlint | 12.1.2 / 1.5.0 | MIT | Kotlin formatting/checking |
| JUnit | 4.13.2 | EPL-1.0 | Tests only |
| AndroidX Test / UI Automator | runner 1.6.2, ext 1.2.1, UI Automator 2.3.0 | Apache-2.0 | Genuine device/emulator flows |

Android PdfRenderer is a platform API, reused for bounded visual pages. The specialized Rust parsers and llama.cpp compiled for arm64 and x86_64; Android tests verify actual PDF extraction, source indexing and model execution rather than inferring compatibility from desktop compilation alone.

The genuine Qwen2.5-0.5B-Instruct Q4_K_M validation model is Apache-2.0, with artifact revision/checksum in `models/README.md`. Its weights are separate private development/device files and are not committed or bundled. Model weights have licenses independent of their inference runtime.

No Ferrous implementation, Flutter/Hive bridge, DuckDB, PDFium binary, UnRAR engine, OCR engine, vector database or remote inference dependency is adopted. Ferrous's UnRAR-specific license restriction and build blockers are documented in the [base audit](base-project-review.md), with its original MIT notice retained.

MPL is file-level copyleft; preserve covered notices and corresponding source availability when redistributing. Debug initialization/MVP validation is not a completed production release notice bundle. Release distribution still needs artifact-specific third-party notices, inherited-POM/source reviews and a selected license for the new Entangle source.

## Evaluated alternatives and later integrations

PDFium/Readium remain candidates for richer rendering, PDF coordinates and EPUB CFIs. Tesseract or an offline Android OCR adapter needs separate native/language-data/license/memory evaluation. LiteRT-LM is a possible alternate inference backend; local embeddings/entity models and a vector index require measured Android budgets. Current retrieval uses FTS5 and observed keyphrase mentions, and graph relations retain source-backed co-occurrence rather than an inferred domain ontology.

All parser inputs are bounded by source/text/passage/archive limits and preserve extraction versions. Source data remains on the device. See the build-results report for completed compatibility checks and practical limits.
