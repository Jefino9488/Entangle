ALTER TABLE documents ADD COLUMN ingestion_state TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE documents ADD COLUMN indexed_passages INTEGER NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN error TEXT;
ALTER TABLE passages ADD COLUMN ordinal INTEGER NOT NULL DEFAULT 0;
CREATE INDEX passages_edition_ordinal ON passages(edition_id, ordinal);
CREATE TABLE extraction_queue (
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    section TEXT,
    page_number INTEGER,
    text TEXT NOT NULL,
    start_offset INTEGER,
    end_offset INTEGER,
    extraction_method TEXT NOT NULL,
    PRIMARY KEY(document_id, ordinal)
);
CREATE TABLE concepts (
    id TEXT PRIMARY KEY NOT NULL,
    canonical_name TEXT NOT NULL UNIQUE,
    label TEXT NOT NULL
);
CREATE TABLE concept_aliases (
    concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    alias TEXT NOT NULL,
    PRIMARY KEY(concept_id, alias)
);
CREATE TABLE concept_mentions (
    id TEXT PRIMARY KEY NOT NULL,
    concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    passage_id TEXT NOT NULL REFERENCES passages(id) ON DELETE CASCADE,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    UNIQUE(concept_id, passage_id, start_offset)
);
CREATE INDEX mentions_concept ON concept_mentions(concept_id);
CREATE INDEX mentions_passage ON concept_mentions(passage_id);
CREATE TABLE relationships (
    id TEXT PRIMARY KEY NOT NULL,
    source_concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    target_concept_id TEXT NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
    relationship_type TEXT NOT NULL,
    UNIQUE(source_concept_id, target_concept_id, relationship_type)
);
CREATE TABLE assertions (
    id TEXT PRIMARY KEY NOT NULL,
    relationship_id TEXT NOT NULL REFERENCES relationships(id) ON DELETE CASCADE,
    edition_id TEXT NOT NULL REFERENCES document_editions(id) ON DELETE CASCADE,
    verification_status TEXT NOT NULL DEFAULT 'observed-co-occurrence',
    processing_version TEXT NOT NULL
);
CREATE TABLE evidence (
    assertion_id TEXT NOT NULL REFERENCES assertions(id) ON DELETE CASCADE,
    passage_id TEXT NOT NULL REFERENCES passages(id) ON DELETE CASCADE,
    PRIMARY KEY(assertion_id, passage_id)
);
CREATE TABLE annotations (
    id TEXT PRIMARY KEY NOT NULL,
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    passage_id TEXT REFERENCES passages(id) ON DELETE SET NULL,
    page_index INTEGER NOT NULL CHECK(page_index >= 0),
    kind TEXT NOT NULL CHECK(kind IN ('highlight','note','bookmark')),
    quote TEXT NOT NULL,
    note TEXT NOT NULL,
    color TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX annotations_document ON annotations(document_id);
PRAGMA user_version = 2;
