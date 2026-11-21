# Entangle working MVP

User scope updated 2026-11-21: implement the product, remove all demo/placeholder content, and push each completed change to https://github.com/Jefino9488/Entangle.git.

## Product acceptance

- Import PDF, EPUB, DOCX, plain text, Markdown and HTML with mature parsers; stream local copies through SAF.
- Incrementally extract page/section-aware passages, preserve extraction provenance, and checkpoint/pause/resume processing through WorkManager.
- Read real PDF pages or extracted text, navigate pages/sections, search locally, persist reading location, create highlights, notes and bookmarks.
- Extract domain-independent keyphrase concepts from actual text; retain aliases/mentions separately.
- Retrieve cross-document passages using FTS5 and concept mentions, with precise locations and honest relationship labels.
- Build graph edges backed by source-specific assertions/evidence. Initial deterministic edges mean observed co-occurrence, never invented semantic facts.
- Inspect actual references in phone sheets or tablet side panels while preserving the main reading position.
- Answer library questions and compare sources using retrieved evidence; optional local GGUF inference is modular, bounded and unloadable.
- All pages perform real operations or show genuine empty/error/loading states. No demo fixtures, fake documents or fabricated results in product source.
- Run Rust, Android, device/emulator checks; produce an installable APK. Commit and push each completed feature change.

## Implementation boundaries

1. Rust processing/storage: extend entangle-core schema via migrations; parsers, checkpointing, passages, FTS, concepts/mentions/aliases, provenance graph, retrieval, annotation persistence.
2. Android reader: real imported document reading, sections/search, annotations/bookmarks, independent reference preview. Consume stable Kotlin domain/repository interfaces.
3. Local inference: separate Rust entangle-inference crate, native GGUF adapter and generated boundary functions; no model hardcoded in architecture. Retain extractive evidence answers without requiring model weights.
4. Root integration: Android state/repository/WorkManager/library/references/knowledge/assistant/settings, remove demo code, connect native APIs, build/tests/docs/GitHub pushes.

## Shared native API contract

Engine retains open/status/documents/register_document/save_position. New methods:
- process_document(document_id, batch_size) -> IngestionProgress; batches/checkpoints, maximum 32 passages per call.
- set_ingestion_paused(document_id, paused); retry_document(document_id).
- passages(document_id, page_number optional) -> PassageRecord list; sections(document_id) -> SectionRecord list.
- search(query, document_id optional, limit) -> PassageRecord list.
- concepts(query, limit) -> ConceptRecord list; graph(concept_id) -> GraphEdgeRecord list.
- references_for(concept_id, source_document_id optional, limit) -> ReferenceRecord list.
- concepts_for_passage(passage_id) -> ConceptRecord list.
- annotations(document_id) / save_annotation(AnnotationRecord) / delete_annotation(annotation_id).
- remove_document(document_id) deletes metadata/index only; Android owns local-file removal.

Transfer fields:
- DocumentRecord: existing fields plus ingestion_state, indexed_passages, error optional.
- IngestionProgress: document_id, state, processed, total, error optional.
- PassageRecord: id, document_id, document_title, edition_id, section optional, page_number optional, ordinal, text, start_offset optional, end_offset optional, extraction_method, processing_version.
- SectionRecord: title, ordinal, page_number optional.
- ConceptRecord: id, label, mention_count.
- ReferenceRecord: passage PassageRecord, concept_id, relationship_type, explanation, verification_status.
- GraphEdgeRecord: id, source ConceptRecord, target ConceptRecord, relationship_type, evidence PassageRecord, verification_status.
- AnnotationRecord: id, document_id, passage_id optional, page_index, kind ('highlight'/'note'/'bookmark'), quote, note, color, created_at.

Native synchronous calls run on Kotlin Dispatchers.IO. Generated Kotlin u32 fields map to UInt. Errors must avoid a field named message (UniFFI Throwable collision); use detail.

Android packages remain layered in one app module. Reader UI consumes AppState and LocalLibraryRepository; implementation teams coordinate exact Kotlin additions before edits. Native structs live in android-bindings while core structures stay independent of UniFFI.

## Validation and delivery

Use real generated PDF/EPUB/DOCX/text fixtures only in tests. Verify extraction/checkpoint/persistence/FTS/provenance and cross-document results. Verify readers, system import, graph/assistant evidence, and independent reference layouts on emulator and the connected Redmi K50i. Use separate commits for completed functional changes; never force-push remote history. Keep artifacts ignored, and do not commit models, user documents, host paths or credentials.
