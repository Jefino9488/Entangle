//! Android-independent local document indexing, retrieval and provenance storage.
mod concept_extraction;
mod extraction;
mod models;
pub use models::*;

use rusqlite::{params, Connection, OptionalExtension, Row, Transaction};
use std::path::Path;
use std::time::Duration;
use thiserror::Error;

const PROCESSING_VERSION: &str = "entangle-keyphrases-v1";
const PASSAGE_COLUMNS: &str = "p.id,d.id,d.title,p.edition_id,p.section,p.page_number,p.ordinal,p.text,p.start_offset,p.end_offset,p.extraction_method,p.processing_version";
const PASSAGE_JOIN: &str = "passages p JOIN document_editions e ON e.id=p.edition_id JOIN documents d ON d.id=e.document_id";

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("SQLite: {0}")]
    Database(#[from] rusqlite::Error),
    #[error("File: {0}")]
    Io(#[from] std::io::Error),
    #[error("Unsupported database version {0}; upgrade Entangle before opening it")]
    UnsupportedSchema(i32),
    #[error("Invalid document: {0}")]
    InvalidDocument(&'static str),
    #[error("Document processing: {0}")]
    Processing(String),
}

pub struct Store {
    connection: Connection,
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CoreError> {
        let mut connection = Connection::open(path)?;
        connection.busy_timeout(Duration::from_secs(5))?;
        connection.execute_batch(
            "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA cache_size=-2048;",
        )?;
        let version: i32 = connection.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if !(0..=2).contains(&version) {
            return Err(CoreError::UnsupportedSchema(version));
        }
        if version < 2 {
            let tx = connection.transaction()?;
            if version == 0 {
                tx.execute_batch(include_str!("schema.sql"))?;
            }
            tx.execute_batch(include_str!("migration_v2.sql"))?;
            tx.commit()?;
        }
        Ok(Self { connection })
    }

    pub fn status(&self) -> Result<Status, CoreError> {
        Ok(Status {
            engine_version: env!("CARGO_PKG_VERSION").into(),
            schema_version: self
                .connection
                .query_row("PRAGMA user_version", [], |r| r.get(0))?,
            document_count: self.connection.query_row(
                "SELECT count(*) FROM documents",
                [],
                |r| r.get(0),
            )?,
            fts5_enabled: self.connection.query_row(
                "SELECT sqlite_compileoption_used('ENABLE_FTS5')",
                [],
                |r| r.get(0),
            )?,
        })
    }

    pub fn register(&mut self, document: &Document) -> Result<(), CoreError> {
        if document.id.is_empty() || document.title.trim().is_empty() {
            return Err(CoreError::InvalidDocument(
                "identity and title are required",
            ));
        }
        if !Path::new(&document.local_path).is_file() {
            return Err(CoreError::InvalidDocument("local file is missing"));
        }
        let tx = self.connection.transaction()?;
        tx.execute(
            "INSERT INTO documents(id,title,local_path,media_type) VALUES (?1,?2,?3,?4)",
            params![
                document.id,
                document.title,
                document.local_path,
                document.media_type
            ],
        )?;
        tx.execute(
            "INSERT INTO document_editions(id,document_id) VALUES (?1,?2)",
            params![format!("{}:initial", document.id), document.id],
        )?;
        tx.execute(
            "INSERT INTO reading_positions(document_id) VALUES (?1)",
            [&document.id],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn documents(&self) -> Result<Vec<Document>, CoreError> {
        let mut statement = self.connection.prepare("SELECT d.id,d.title,d.local_path,d.media_type,r.page_index,r.scroll_offset,d.ingestion_state,d.indexed_passages,d.error FROM documents d JOIN reading_positions r ON d.id=r.document_id ORDER BY d.imported_at DESC,d.id")?;
        let items = statement
            .query_map([], document_row)?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    fn document(&self, id: &str) -> Result<Document, CoreError> {
        self.connection.query_row("SELECT d.id,d.title,d.local_path,d.media_type,r.page_index,r.scroll_offset,d.ingestion_state,d.indexed_passages,d.error FROM documents d JOIN reading_positions r ON d.id=r.document_id WHERE d.id=?1", [id], document_row).optional()?.ok_or(CoreError::InvalidDocument("document does not exist"))
    }

    pub fn save_position(&self, id: &str, page: u32, scroll: u32) -> Result<(), CoreError> {
        if self.connection.execute(
            "UPDATE reading_positions SET page_index=?2,scroll_offset=?3 WHERE document_id=?1",
            params![id, page, scroll],
        )? != 1
        {
            return Err(CoreError::InvalidDocument("document does not exist"));
        }
        Ok(())
    }

    pub fn process_document(
        &mut self,
        id: &str,
        batch_size: u32,
    ) -> Result<IngestionProgress, CoreError> {
        self.process_document_with_extractor(id, batch_size, extraction::extract)
    }

    fn process_document_with_extractor(
        &mut self,
        id: &str,
        batch_size: u32,
        extract: impl FnOnce(&Document) -> Result<Vec<extraction::ExtractedPassage>, CoreError>,
    ) -> Result<IngestionProgress, CoreError> {
        let document = self.document(id)?;
        if matches!(
            document.ingestion_state.as_str(),
            "paused" | "ready" | "failed"
        ) {
            return self.progress(id);
        }
        let queue_count: u32 = self.connection.query_row(
            "SELECT count(*) FROM extraction_queue WHERE document_id=?1",
            [id],
            |r| r.get(0),
        )?;
        if queue_count == 0 && document.indexed_passages == 0 {
            let changed = self.connection.execute(
                "UPDATE documents SET ingestion_state='extracting',error=NULL WHERE id=?1 AND ingestion_state IN ('pending','extracting','indexing')",
                [id],
            )?;
            if changed == 0 {
                return self.progress(id);
            }
            match extract(&document) {
                Ok(passages) => {
                    let tx = self.connection.transaction()?;
                    for (ordinal, passage) in passages.into_iter().enumerate() {
                        tx.execute("INSERT INTO extraction_queue(document_id,ordinal,section,page_number,text,start_offset,end_offset,extraction_method) VALUES (?1,?2,?3,?4,?5,?6,?7,?8)",params![id,ordinal as u32,passage.section,passage.page_number,passage.text,passage.start_offset,passage.end_offset,passage.extraction_method])?;
                    }
                    tx.execute(
                        "UPDATE documents SET ingestion_state=CASE WHEN ingestion_state='paused' THEN 'paused' ELSE 'indexing' END WHERE id=?1",
                        [id],
                    )?;
                    tx.commit()?;
                }
                Err(error) => {
                    self.connection.execute(
                        "UPDATE documents SET ingestion_state='failed',error=?2 WHERE id=?1",
                        params![id, error.to_string()],
                    )?;
                    return self.progress(id);
                }
            }
        }
        // Another connection can pause extraction while no SQLite transaction is held.
        if self.document(id)?.ingestion_state == "paused" {
            return self.progress(id);
        }
        let batch = {
            let mut statement = self.connection.prepare("SELECT ordinal,section,page_number,text,start_offset,end_offset,extraction_method FROM extraction_queue WHERE document_id=?1 ORDER BY ordinal LIMIT ?2")?;
            let rows = statement.query_map(params![id, batch_size.clamp(1, 32)], |r| {
                Ok((
                    r.get::<_, u32>(0)?,
                    extraction::ExtractedPassage {
                        section: r.get(1)?,
                        page_number: r.get(2)?,
                        text: r.get(3)?,
                        start_offset: r.get(4)?,
                        end_offset: r.get(5)?,
                        extraction_method: r.get(6)?,
                    },
                ))
            })?;
            rows.collect::<Result<Vec<_>, _>>()?
        };
        let tx = self.connection.transaction()?;
        let edition = format!("{id}:initial");
        for (ordinal, passage) in &batch {
            let passage_id = format!("{id}:p:{ordinal}");
            tx.execute("INSERT INTO passages(id,edition_id,section,page_number,text,start_offset,end_offset,extraction_method,processing_version,ordinal) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",params![passage_id,edition,passage.section,passage.page_number,passage.text,passage.start_offset,passage.end_offset,passage.extraction_method,PROCESSING_VERSION,ordinal])?;
            index_concepts(&tx, &passage_id, &edition, &passage.text)?;
            tx.execute(
                "DELETE FROM extraction_queue WHERE document_id=?1 AND ordinal=?2",
                params![id, ordinal],
            )?;
        }
        tx.execute("UPDATE documents SET indexed_passages=indexed_passages+?2,ingestion_state=CASE WHEN EXISTS(SELECT 1 FROM extraction_queue WHERE document_id=?1) THEN 'indexing' ELSE 'ready' END WHERE id=?1",params![id,batch.len() as u32])?;
        tx.commit()?;
        self.progress(id)
    }

    fn progress(&self, id: &str) -> Result<IngestionProgress, CoreError> {
        let document = self.document(id)?;
        let remaining: u32 = self.connection.query_row(
            "SELECT count(*) FROM extraction_queue WHERE document_id=?1",
            [id],
            |r| r.get(0),
        )?;
        Ok(IngestionProgress {
            document_id: id.into(),
            state: document.ingestion_state,
            processed: document.indexed_passages,
            total: document.indexed_passages + remaining,
            error: document.error,
        })
    }

    pub fn set_ingestion_paused(&self, id: &str, paused: bool) -> Result<(), CoreError> {
        self.document(id)?;
        if paused {
            self.connection.execute("UPDATE documents SET ingestion_state='paused' WHERE id=?1 AND ingestion_state IN ('pending','extracting','indexing')",[id])?;
        } else {
            self.connection.execute("UPDATE documents SET ingestion_state=CASE WHEN EXISTS(SELECT 1 FROM extraction_queue WHERE document_id=?1) THEN 'indexing' ELSE 'pending' END WHERE id=?1 AND ingestion_state='paused'",[id])?;
        }
        Ok(())
    }

    pub fn retry_document(&mut self, id: &str) -> Result<(), CoreError> {
        self.document(id)?;
        let tx = self.connection.transaction()?;
        tx.execute("DELETE FROM extraction_queue WHERE document_id=?1", [id])?;
        tx.execute("DELETE FROM passages WHERE edition_id IN (SELECT id FROM document_editions WHERE document_id=?1)",[id])?;
        prune_graph(&tx)?;
        tx.execute("UPDATE documents SET ingestion_state='pending',indexed_passages=0,error=NULL WHERE id=?1",[id])?;
        tx.commit()?;
        Ok(())
    }

    pub fn passages(&self, id: &str, page: Option<u32>) -> Result<Vec<Passage>, CoreError> {
        self.document(id)?;
        let sql=format!("SELECT {PASSAGE_COLUMNS} FROM {PASSAGE_JOIN} WHERE d.id=?1 AND (?2 IS NULL OR p.page_number=?2) ORDER BY p.ordinal");
        let mut statement = self.connection.prepare(&sql)?;
        let items = statement
            .query_map(params![id, page], passage_row)?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn sections(&self, id: &str) -> Result<Vec<Section>, CoreError> {
        let mut statement=self.connection.prepare("SELECT coalesce(p.section,'Text'),min(p.ordinal),p.page_number FROM passages p JOIN document_editions e ON e.id=p.edition_id WHERE e.document_id=?1 GROUP BY coalesce(p.section,'Text'),p.page_number ORDER BY min(p.ordinal)")?;
        let items = statement
            .query_map([id], |r| {
                Ok(Section {
                    title: r.get(0)?,
                    ordinal: r.get(1)?,
                    page_number: r.get(2)?,
                })
            })?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn search(
        &self,
        query: &str,
        document_id: Option<&str>,
        limit: u32,
    ) -> Result<Vec<Passage>, CoreError> {
        let terms = concept_extraction::searchable_terms(query);
        if terms.is_empty() {
            return Ok(Vec::new());
        }
        let expression = terms
            .iter()
            .map(|term| format!("\"{}\"", term.replace('"', "\"\"")))
            .collect::<Vec<_>>()
            .join(" OR ");
        let sql=format!("SELECT {PASSAGE_COLUMNS} FROM {PASSAGE_JOIN} JOIN passages_fts f ON p.rowid=f.rowid WHERE passages_fts MATCH ?1 AND (?2 IS NULL OR d.id=?2) ORDER BY bm25(passages_fts),d.id,p.ordinal LIMIT ?3");
        let mut statement = self.connection.prepare(&sql)?;
        let items = statement
            .query_map(
                params![expression, document_id, limit.clamp(1, 100)],
                passage_row,
            )?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn concepts(&self, query: &str, limit: u32) -> Result<Vec<Concept>, CoreError> {
        let mut statement=self.connection.prepare("SELECT c.id,c.label,count(m.id) FROM concepts c JOIN concept_mentions m ON m.concept_id=c.id WHERE (?1='' OR instr(lower(c.label),lower(?1))>0 OR EXISTS(SELECT 1 FROM concept_aliases a WHERE a.concept_id=c.id AND instr(lower(a.alias),lower(?1))>0)) GROUP BY c.id ORDER BY count(m.id) DESC,length(c.label) DESC,c.label LIMIT ?2")?;
        let items = statement
            .query_map(params![query.trim(), limit.clamp(1, 1000)], concept_row)?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn concepts_for_passage(&self, id: &str) -> Result<Vec<Concept>, CoreError> {
        let mut statement=self.connection.prepare("SELECT c.id,c.label,count(m.id) FROM concepts c JOIN concept_mentions m ON m.concept_id=c.id WHERE m.passage_id=?1 GROUP BY c.id ORDER BY length(c.label) DESC,c.label LIMIT 16")?;
        let items = statement
            .query_map([id], concept_row)?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn references_for(
        &self,
        concept_id: &str,
        source_document_id: Option<&str>,
        limit: u32,
    ) -> Result<Vec<Reference>, CoreError> {
        let label: Option<String> = self
            .connection
            .query_row(
                "SELECT label FROM concepts WHERE id=?1",
                [concept_id],
                |r| r.get(0),
            )
            .optional()?;
        let Some(label) = label else {
            return Ok(Vec::new());
        };
        let sql=format!("SELECT DISTINCT {PASSAGE_COLUMNS} FROM {PASSAGE_JOIN} JOIN concept_mentions m ON m.passage_id=p.id WHERE m.concept_id=?1 AND (?2 IS NULL OR d.id<>?2) ORDER BY d.id,p.ordinal LIMIT ?3");
        let mut statement = self.connection.prepare(&sql)?;
        let passages = statement
            .query_map(
                params![concept_id, source_document_id, limit.clamp(1, 100)],
                passage_row,
            )?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(passages
            .into_iter()
            .map(|passage| Reference {
                passage,
                concept_id: concept_id.into(),
                relationship_type: "SAME_CONCEPT".into(),
                explanation: format!("This source contains an observed mention of ‘{label}’."),
                verification_status: "observed-text-mention".into(),
            })
            .collect())
    }

    pub fn graph(&self, concept_id: &str) -> Result<Vec<GraphEdge>, CoreError> {
        let sql=format!("SELECT r.id,r.source_concept_id,r.target_concept_id,r.relationship_type,a.verification_status,{PASSAGE_COLUMNS} FROM {PASSAGE_JOIN} JOIN evidence v ON p.id=v.passage_id JOIN assertions a ON v.assertion_id=a.id JOIN relationships r ON a.relationship_id=r.id WHERE r.source_concept_id=?1 OR r.target_concept_id=?1 ORDER BY d.title,p.ordinal LIMIT 80");
        let mut statement = self.connection.prepare(&sql)?;
        let rows = statement
            .query_map([concept_id], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?,
                    passage_row_at(r, 5)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows.into_iter()
            .map(
                |(id, source, target, relationship_type, verification_status, evidence)| {
                    Ok(GraphEdge {
                        id,
                        source: self.concept(&source)?,
                        target: self.concept(&target)?,
                        relationship_type,
                        evidence,
                        verification_status,
                    })
                },
            )
            .collect()
    }

    fn concept(&self, id: &str) -> Result<Concept, CoreError> {
        Ok(self.connection.query_row("SELECT c.id,c.label,(SELECT count(*) FROM concept_mentions WHERE concept_id=c.id) FROM concepts c WHERE c.id=?1",[id],concept_row)?)
    }

    pub fn annotations(&self, id: &str) -> Result<Vec<Annotation>, CoreError> {
        let mut statement=self.connection.prepare("SELECT id,document_id,passage_id,page_index,kind,quote,note,color,created_at FROM annotations WHERE document_id=?1 ORDER BY created_at DESC,id")?;
        let items = statement
            .query_map([id], annotation_row)?
            .collect::<Result<_, _>>()?;
        Ok(items)
    }

    pub fn save_annotation(&self, annotation: &Annotation) -> Result<(), CoreError> {
        if annotation.id.is_empty()
            || !matches!(annotation.kind.as_str(), "highlight" | "note" | "bookmark")
        {
            return Err(CoreError::InvalidDocument(
                "annotation identity/kind is invalid",
            ));
        }
        self.document(&annotation.document_id)?;
        if let Some(passage) = &annotation.passage_id {
            let matches:bool=self.connection.query_row("SELECT EXISTS(SELECT 1 FROM passages p JOIN document_editions e ON e.id=p.edition_id WHERE p.id=?1 AND e.document_id=?2)",params![passage,annotation.document_id],|r|r.get(0))?;
            if !matches {
                return Err(CoreError::InvalidDocument(
                    "annotation passage belongs to another document",
                ));
            }
        }
        self.connection.execute("INSERT INTO annotations(id,document_id,passage_id,page_index,kind,quote,note,color) VALUES (?1,?2,?3,?4,?5,?6,?7,?8) ON CONFLICT(id) DO UPDATE SET passage_id=excluded.passage_id,page_index=excluded.page_index,kind=excluded.kind,quote=excluded.quote,note=excluded.note,color=excluded.color WHERE annotations.document_id=excluded.document_id",params![annotation.id,annotation.document_id,annotation.passage_id,annotation.page_index,annotation.kind,annotation.quote,annotation.note,annotation.color])?;
        Ok(())
    }

    pub fn delete_annotation(&self, id: &str) -> Result<(), CoreError> {
        self.connection
            .execute("DELETE FROM annotations WHERE id=?1", [id])?;
        Ok(())
    }

    pub fn remove_document(&mut self, id: &str) -> Result<(), CoreError> {
        self.document(id)?;
        let tx = self.connection.transaction()?;
        tx.execute("DELETE FROM passages WHERE edition_id IN (SELECT id FROM document_editions WHERE document_id=?1)",[id])?;
        tx.execute("DELETE FROM documents WHERE id=?1", [id])?;
        prune_graph(&tx)?;
        tx.commit()?;
        Ok(())
    }
}

fn document_row(r: &Row<'_>) -> rusqlite::Result<Document> {
    Ok(Document {
        id: r.get(0)?,
        title: r.get(1)?,
        local_path: r.get(2)?,
        media_type: r.get(3)?,
        page_index: r.get(4)?,
        scroll_offset: r.get(5)?,
        ingestion_state: r.get(6)?,
        indexed_passages: r.get(7)?,
        error: r.get(8)?,
    })
}
fn concept_row(r: &Row<'_>) -> rusqlite::Result<Concept> {
    Ok(Concept {
        id: r.get(0)?,
        label: r.get(1)?,
        mention_count: r.get(2)?,
    })
}
fn passage_row(r: &Row<'_>) -> rusqlite::Result<Passage> {
    passage_row_at(r, 0)
}
fn passage_row_at(r: &Row<'_>, i: usize) -> rusqlite::Result<Passage> {
    Ok(Passage {
        id: r.get(i)?,
        document_id: r.get(i + 1)?,
        document_title: r.get(i + 2)?,
        edition_id: r.get(i + 3)?,
        section: r.get(i + 4)?,
        page_number: r.get(i + 5)?,
        ordinal: r.get(i + 6)?,
        text: r.get(i + 7)?,
        start_offset: r.get(i + 8)?,
        end_offset: r.get(i + 9)?,
        extraction_method: r.get(i + 10)?,
        processing_version: r.get(i + 11)?,
    })
}
fn annotation_row(r: &Row<'_>) -> rusqlite::Result<Annotation> {
    Ok(Annotation {
        id: r.get(0)?,
        document_id: r.get(1)?,
        passage_id: r.get(2)?,
        page_index: r.get(3)?,
        kind: r.get(4)?,
        quote: r.get(5)?,
        note: r.get(6)?,
        color: r.get(7)?,
        created_at: r.get(8)?,
    })
}

fn index_concepts(
    tx: &Transaction<'_>,
    passage: &str,
    edition: &str,
    text: &str,
) -> Result<(), CoreError> {
    let phrases = concept_extraction::keyphrases(text);
    for phrase in &phrases {
        let id = format!("c:{}", phrase.canonical);
        tx.execute(
            "INSERT OR IGNORE INTO concepts(id,canonical_name,label) VALUES (?1,?2,?3)",
            params![id, phrase.canonical, phrase.label],
        )?;
        tx.execute(
            "INSERT OR IGNORE INTO concept_aliases(concept_id,alias) VALUES (?1,?2)",
            params![id, phrase.label],
        )?;
        for (start, end) in &phrase.mentions {
            tx.execute("INSERT OR IGNORE INTO concept_mentions(id,concept_id,passage_id,start_offset,end_offset) VALUES (?1,?2,?3,?4,?5)",params![format!("{passage}:{id}:{start}"),id,passage,start,end])?;
        }
    }
    // At most 28 observed co-occurrence edges per passage, avoiding unbounded quadratic fan-out.
    let selected: Vec<_> = phrases.iter().take(8).collect();
    for (index, source) in selected.iter().enumerate() {
        for target in selected.iter().skip(index + 1) {
            let (source, target) = if source.canonical < target.canonical {
                (source, target)
            } else {
                (target, source)
            };
            let relationship = format!("r:{}|{}", source.canonical, target.canonical);
            let assertion = format!("{passage}:{relationship}");
            tx.execute("INSERT OR IGNORE INTO relationships(id,source_concept_id,target_concept_id,relationship_type) VALUES (?1,?2,?3,'CO_OCCURS_WITH')",params![relationship,format!("c:{}",source.canonical),format!("c:{}",target.canonical)])?;
            tx.execute("INSERT OR IGNORE INTO assertions(id,relationship_id,edition_id,verification_status,processing_version) VALUES (?1,?2,?3,'observed-co-occurrence',?4)",params![assertion,relationship,edition,PROCESSING_VERSION])?;
            tx.execute(
                "INSERT OR IGNORE INTO evidence(assertion_id,passage_id) VALUES (?1,?2)",
                params![assertion, passage],
            )?;
        }
    }
    Ok(())
}

fn prune_graph(tx: &Transaction<'_>) -> Result<(), CoreError> {
    tx.execute("DELETE FROM assertions WHERE NOT EXISTS(SELECT 1 FROM evidence WHERE evidence.assertion_id=assertions.id)",[])?;
    tx.execute("DELETE FROM relationships WHERE NOT EXISTS(SELECT 1 FROM assertions WHERE assertions.relationship_id=relationships.id)",[])?;
    tx.execute("DELETE FROM concepts WHERE NOT EXISTS(SELECT 1 FROM concept_mentions WHERE concept_mentions.concept_id=concepts.id)",[])?;
    Ok(())
}

#[cfg(test)]
mod tests;
