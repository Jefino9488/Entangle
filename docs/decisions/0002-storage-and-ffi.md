# ADR 0002: Rust-owned SQLite and UniFFI

Status: accepted for initialization. Date: 2026-11-21.

Use rusqlite with bundled SQLite and FTS5, with one Rust-owned migration stream and a mutex-protected connection. The database is stored in Android private storage. Enable foreign keys, WAL and a finite busy timeout. Never open the same schema through Room. If Room becomes useful for Android-owned jobs/settings, use another database.

Use UniFFI 0.29.4 generated Kotlin bindings over JNA. Keep UniFFI annotations in the binding crate, separating UI-facing records from core storage structures. Use synchronous engine methods invoked from Kotlin IO coroutines for this small foundation; no additional runtime is needed. Domain/storage errors become typed boundary errors and visible application errors.

Generate bindings and cross-compile both supported ABIs in Gradle's `buildRust` task. Cargo uses a lockfile; the Rust compiler, NDK and Gradle wrapper are pinned. Generated Kotlin/native files are ignored and recreated, preventing stale manually edited bridge source. JNA native libraries and Rust native alignment must be checked when changing ABI, SDK, NDK, Rust or JNA versions.

Do not add DuckDB or a vector index initially. Evaluate Android-compatible SQLite vector extensions versus a Rust in-memory/disk-backed index using actual library sizes and a bounded memory budget before selecting one.
