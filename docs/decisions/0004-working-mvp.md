# ADR 0004: Expand initialization into a source-backed working MVP

Status: accepted. Date: 2026-11-21. User explicitly requested implementation of a working MVP, removal of all demo/placeholder content, and feature commits pushed to Jefino9488/Entangle.

Extend the established native foundation rather than transplanting Ferrous's Flutter stack. Implement actual imports, checkpointed extraction, reading/search/annotations, references, graph and assistant. Keep product screens data-driven; test fixtures are permitted only in test source. The Rust workspace now has processing/core, Android bindings and a modular inference crate.

Use mature PDF/EPUB/DOCX/HTML/Markdown parsers. Bound input/extraction resources and report unsupported/scanned/oversized sources. Keep schema v2 transactional, with separate concept identity, aliases, mentions, relationships, source assertions and evidence.

Use deterministic domain-independent keyphrase indexing and observed co-occurrence as the first working graph. Do not invent semantic relations or merge conflicting source claims. Evidence-only answers work without model weights; optional llama.cpp GGUF generation uses real source context and grammar-constrained valid citation IDs, with source cards retained separately for claim assessment.

Model imports are user/device-local choices. The validation candidate is not hardcoded into runtime architecture. Sessions are bounded, cancellable, single-request and released after each answer. Phone/tablet references preserve reader anchors across actual viewport resizing.

Commit and push each completed tested change to main, preserving remote history. Keep generated binaries, model weights, user documents, SDK paths and reference checkout out of Git. Earlier initialization-only module/runtime descriptions are superseded by this MVP decision and current architecture documentation.
