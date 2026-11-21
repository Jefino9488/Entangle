CREATE TABLE documents (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    local_path TEXT NOT NULL UNIQUE,
    media_type TEXT NOT NULL,
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE document_editions (
    id TEXT PRIMARY KEY NOT NULL,
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    label TEXT
);
CREATE TABLE reading_positions (
    document_id TEXT PRIMARY KEY NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    page_index INTEGER NOT NULL DEFAULT 0 CHECK(page_index >= 0),
    scroll_offset INTEGER NOT NULL DEFAULT 0 CHECK(scroll_offset >= 0)
);
CREATE TABLE passages (
    id TEXT PRIMARY KEY NOT NULL,
    edition_id TEXT NOT NULL REFERENCES document_editions(id),
    section TEXT,
    page_number INTEGER CHECK(page_number > 0),
    start_offset INTEGER,
    end_offset INTEGER,
    text TEXT NOT NULL,
    extraction_method TEXT NOT NULL,
    processing_version TEXT NOT NULL
);
CREATE VIRTUAL TABLE passages_fts USING fts5(
    text, content='passages', content_rowid='rowid', tokenize='unicode61'
);
CREATE TRIGGER passages_ai AFTER INSERT ON passages BEGIN
    INSERT INTO passages_fts(rowid, text) VALUES (new.rowid, new.text);
END;
CREATE TRIGGER passages_ad AFTER DELETE ON passages BEGIN
    INSERT INTO passages_fts(passages_fts, rowid, text) VALUES('delete', old.rowid, old.text);
END;
CREATE TRIGGER passages_au AFTER UPDATE ON passages BEGIN
    INSERT INTO passages_fts(passages_fts, rowid, text) VALUES('delete', old.rowid, old.text);
    INSERT INTO passages_fts(rowid, text) VALUES (new.rowid, new.text);
END;
PRAGMA user_version = 1;
