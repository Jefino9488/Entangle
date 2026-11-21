# ADR 0001: Native Android foundation alongside Ferrous

Status: accepted for initialization. Date: 2026-11-21.

Ferrous at commit `e30ada691c251d22781115d55d263bdb9bfa8367` is a Flutter reader with useful document-processing and reader-state patterns. Entangle requires Kotlin, Compose, SQLite and a Kotlin/Rust boundary. Translating or silently relabelling Ferrous would inherit the wrong presentation and persistence stack.

Keep the complete upstream clone in `Refrence/Ferrous` as an unmodified, Git-ignored reference. Build original native Android source next to it. Preserve the upstream MIT notice in its clone and in `docs/ferrous-LICENSE`; no implementation is copied. Reuse a parser only after a separate API, Android, provenance, resource and license evaluation.

Use one Android module with layered packages and two Rust crates initially. Splitting empty modules adds overhead without enforceable real dependencies. The source graph and documentation identify intended boundaries for later extraction. Android's baseline is API 26; compile/target SDK 36, with arm64 and x86_64 binaries. The Redmi K50i is within this baseline; older-device support can be evaluated separately.

The new Entangle source distribution license remains unselected. Upstream and dependency notices do not grant a license to unrelated new source automatically.
