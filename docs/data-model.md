# Persistent source and knowledge data model

The Rust-owned SQLite schema has transactional v1/v2 migrations. All following tables are implemented and used by the MVP; no sample records are seeded.

| Entity | Persistent responsibility |
| --- | --- |
| Document | UUID, title, private path, media type, import timestamp, ingestion status/error and indexed count |
| DocumentEdition | Specific imported edition identity, separate from logical document |
| Passage | Exact extracted text, section, actual PDF page or nullable reflow page, ordinal, offsets, extraction method/version |
| ReadingPosition | Zero-based PDF page or reflow passage ordinal, independent scroll offset |
| ExtractionQueue | Staged extraction rows and remaining indexing checkpoint |
| Concept | Canonical Unicode-normalized identity and display label |
| ConceptAlias | Actual alternative spelling observed in source text |
| ConceptMention | Concept occurrence with exact original extracted-passage UTF-8 byte offsets |
| Relationship | Domain-independent typed concept pair |
| Assertion | Source-edition-specific observation, processing version and verification status |
| Evidence | Exact passage supporting an assertion |
| Annotation | Highlight/note/bookmark, original document/passage/reader page, exact quote, note, color and creation time |
| PassagesFTS | FTS5 external-content index maintained by source insert/update/delete triggers |

Each source import currently creates one initial edition and a distinct UUID. Multiple-edition reconciliation/content-hash deduplication is not implemented; separate imports remain separate sources. Removing/retrying source data cascades/prunes related mentions, evidence and graph rows while preserving unrelated documents.

## Source conventions

Reader pages are zero-based. PDF citation pages are one-based and come from the real parser. EPUB/DOCX/HTML/Markdown/text citations retain a section/passage locator with a null page rather than an invented page number.

Text offsets in Rust address UTF-8 bytes in the extracted page/section or passage. They do not address PDF file bytes or Kotlin UTF-16 indexes. Plain-text extraction offsets are verified to slice the exact original UTF-8 source after batching/restart. Rich-format extraction retains processing method/version and page/section context. PDF coordinates and EPUB CFIs are not implemented in this MVP; source identity, section and actual page/passage remain available.

Annotations retain exact selected quotes and passage identity. Highlight presentation currently marks matching occurrences of that quote in the passage; selection-offset disambiguation for repeated identical text is a later enhancement.

## Evidence and verification

Observed concept mentions produce `SAME_CONCEPT` references to other documents. Observed concept pairs produce `CO_OCCURS_WITH` graph relationships, with separate assertions and evidence. Verification states explicitly identify observed text/co-occurrence. No model inference is promoted into an accepted graph fact.

Source-specific claims are not merged. Different explanations remain separate passages/assertions; the assistant can retrieve them together. Graph relation vocabulary is extensible and domain-independent. Semantically inferred relationship extraction and human verification workflows need separate later migrations and evaluation.
