# Ferrous reference audit

Reviewed on 2026-11-21. Source: https://github.com/JuanSoFly/Ferrous. Commit: `e30ada691c251d22781115d55d263bdb9bfa8367` (default branch at clone time). Location: `Refrence/Ferrous`.

The clone is kept intact and excluded from the Entangle repository. The upstream license is MIT, copyright © 2025 Juan Teodoro N. De Vera; it remains in the clone and `docs/ferrous-LICENSE`. No upstream implementation is transplanted in this milestone.

## Architecture inspected

- Flutter/Dart presentation under `lib/app`, `lib/core`, `lib/data`, and `lib/features`. Hive repositories own books, annotations, collections, themes and reader progress. Native Android code implements Storage Access Framework access.
- A single Rust crate, `rust_lib_ferrous`, integrates through generated Flutter Rust Bridge 2.11.1 bindings and Cargokit. `rust/src/api` contains PDF/PDFium, library scanning, TXT, DOCX, MOBI, comics, cover extraction, crop and TTS interfaces.
- PDF rendering dynamically loads `libpdfium.so`, uses global PDFium state, native page buffers, cache and rendering epochs. It requires external ABI-matched PDFium binaries. Rust `walkdir` accepts filesystem paths; Android document provider URIs must first be resolved/copied or adapted through SAF.
- Reader UX patterns worth studying include split-reader state, independent progress, source offset mapping, pagination and bounded page caching. Most EPUB/reflow layout code lives in Dart and cannot be used directly from Compose.
- Although broadly described as offline, optional dictionary/update services use HTTP. Entangle does not inherit those services, remote lookups, telemetry concerns, broad storage access, or startup behavior.

## Build checks

`cargo check --manifest-path Refrence/Ferrous/rust/Cargo.toml --locked` was attempted against host Rust 1.96.1. It stopped because locked `ppmd-turbo 0.2.2` and `reedsolomon-rs 0.4.11` require Rust 1.97.1. The upstream lockfile was not edited.

The reference clone does not contain `android/gradlew` or the wrapper JAR. Running Entangle's Gradle 8.14 wrapper against the reference Android project fails evaluating `settings.gradle` because `android/local.properties` is absent. Flutter/Dart are not installed on this host, and the settings require `flutter.sdk`. Therefore an upstream Android build is **not verified**, and no claim is made that Ferrous itself builds here.

The reference README requests Flutter 3.44+, compile SDK 36, AGP 8.11.1, Kotlin 2.2.20 and externally provisioned PDFium binaries. Its README Rust minimum does not account for the newer requirements in the current lockfile. Entangle's independent native APK and native bridge are validated separately in `build-results.md`.

## License findings

The full locked Rust inventory is recorded in [ferrous-rust-dependency-inventory.md](ferrous-rust-dependency-inventory.md). Direct parser/rendering dependencies generally declare MIT, Apache-2.0, ISC, or combinations. Important exceptions and integration boundaries:

- `unrar-rs 0.15.0` supplies a LICENSE file: **Apache-2.0 plus the UnRAR restriction** prohibiting development of RAR-compatible compression/archiving. Binary distribution must include that license file. It is not plain Apache-2.0; it is not introduced into Entangle.
- `encoding_rs` combines an MIT/Apache choice with BSD-3-Clause notices.
- `pdfium-render`'s MIT/Apache license is separate from PDFium's BSD-style license and PDFium third-party binary notices. Any future binary packaging needs an artifact-specific notice review.
- The Dart ecosystem includes Flutter (BSD-3-Clause), Flutter Rust Bridge (MIT), Hive/Hive Flutter (Apache-2.0), and parser/UI packages. Those package constraints were inspected in `pubspec.yaml`; their resolved transitive license set was **not verified** without a working Flutter dependency resolution. Do not treat the repository's MIT license as the license of every dependency.

## Reuse decision

Use Ferrous as a reference for document and reader behaviors. Keep Entangle's core independent of Flutter-generated types and Hive. Evaluate focused parser libraries individually, retaining source offsets and license notices; avoid importing the complete reader crate with its global renderer, bridge, comic engines and unrelated resource requirements.
