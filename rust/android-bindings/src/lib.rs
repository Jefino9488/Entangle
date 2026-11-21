//! Stable Kotlin DTOs; core SQLite and parser internals never cross the boundary.
mod inference;
use entangle_core::{self as core, Store};
use std::sync::{Arc, Mutex, MutexGuard};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("{detail}")]
    Storage { detail: String },
}
impl From<core::CoreError> for EngineError {
    fn from(error: core::CoreError) -> Self {
        Self::Storage {
            detail: error.to_string(),
        }
    }
}

#[derive(uniffi::Record)]
pub struct EngineStatus {
    pub engine_version: String,
    pub schema_version: u32,
    pub document_count: u32,
    pub fts5_enabled: bool,
}
#[derive(uniffi::Record)]
pub struct DocumentRecord {
    pub id: String,
    pub title: String,
    pub local_path: String,
    pub media_type: String,
    pub page_index: u32,
    pub scroll_offset: u32,
    pub ingestion_state: String,
    pub indexed_passages: u32,
    pub error: Option<String>,
}
#[derive(uniffi::Record)]
pub struct IngestionProgress {
    pub document_id: String,
    pub state: String,
    pub processed: u32,
    pub total: u32,
    pub error: Option<String>,
}
#[derive(uniffi::Record)]
pub struct PassageRecord {
    pub id: String,
    pub document_id: String,
    pub document_title: String,
    pub edition_id: String,
    pub section: Option<String>,
    pub page_number: Option<u32>,
    pub ordinal: u32,
    pub text: String,
    pub start_offset: Option<u32>,
    pub end_offset: Option<u32>,
    pub extraction_method: String,
    pub processing_version: String,
}
#[derive(uniffi::Record)]
pub struct SectionRecord {
    pub title: String,
    pub ordinal: u32,
    pub page_number: Option<u32>,
}
#[derive(uniffi::Record)]
pub struct ConceptRecord {
    pub id: String,
    pub label: String,
    pub mention_count: u32,
}
#[derive(uniffi::Record)]
pub struct ReferenceRecord {
    pub passage: PassageRecord,
    pub concept_id: String,
    pub relationship_type: String,
    pub explanation: String,
    pub verification_status: String,
}
#[derive(uniffi::Record)]
pub struct GraphEdgeRecord {
    pub id: String,
    pub source: ConceptRecord,
    pub target: ConceptRecord,
    pub relationship_type: String,
    pub evidence: PassageRecord,
    pub verification_status: String,
}
#[derive(uniffi::Record)]
pub struct AnnotationRecord {
    pub id: String,
    pub document_id: String,
    pub passage_id: Option<String>,
    pub page_index: u32,
    pub kind: String,
    pub quote: String,
    pub note: String,
    pub color: String,
    pub created_at: String,
}

impl From<core::Document> for DocumentRecord {
    fn from(d: core::Document) -> Self {
        Self {
            id: d.id,
            title: d.title,
            local_path: d.local_path,
            media_type: d.media_type,
            page_index: d.page_index,
            scroll_offset: d.scroll_offset,
            ingestion_state: d.ingestion_state,
            indexed_passages: d.indexed_passages,
            error: d.error,
        }
    }
}
impl From<core::IngestionProgress> for IngestionProgress {
    fn from(p: core::IngestionProgress) -> Self {
        Self {
            document_id: p.document_id,
            state: p.state,
            processed: p.processed,
            total: p.total,
            error: p.error,
        }
    }
}
impl From<core::Passage> for PassageRecord {
    fn from(p: core::Passage) -> Self {
        Self {
            id: p.id,
            document_id: p.document_id,
            document_title: p.document_title,
            edition_id: p.edition_id,
            section: p.section,
            page_number: p.page_number,
            ordinal: p.ordinal,
            text: p.text,
            start_offset: p.start_offset,
            end_offset: p.end_offset,
            extraction_method: p.extraction_method,
            processing_version: p.processing_version,
        }
    }
}
impl From<core::Section> for SectionRecord {
    fn from(s: core::Section) -> Self {
        Self {
            title: s.title,
            ordinal: s.ordinal,
            page_number: s.page_number,
        }
    }
}
impl From<core::Concept> for ConceptRecord {
    fn from(c: core::Concept) -> Self {
        Self {
            id: c.id,
            label: c.label,
            mention_count: c.mention_count,
        }
    }
}
impl From<core::Reference> for ReferenceRecord {
    fn from(r: core::Reference) -> Self {
        Self {
            passage: r.passage.into(),
            concept_id: r.concept_id,
            relationship_type: r.relationship_type,
            explanation: r.explanation,
            verification_status: r.verification_status,
        }
    }
}
impl From<core::GraphEdge> for GraphEdgeRecord {
    fn from(e: core::GraphEdge) -> Self {
        Self {
            id: e.id,
            source: e.source.into(),
            target: e.target.into(),
            relationship_type: e.relationship_type,
            evidence: e.evidence.into(),
            verification_status: e.verification_status,
        }
    }
}
impl From<core::Annotation> for AnnotationRecord {
    fn from(a: core::Annotation) -> Self {
        Self {
            id: a.id,
            document_id: a.document_id,
            passage_id: a.passage_id,
            page_index: a.page_index,
            kind: a.kind,
            quote: a.quote,
            note: a.note,
            color: a.color,
            created_at: a.created_at,
        }
    }
}
impl From<AnnotationRecord> for core::Annotation {
    fn from(a: AnnotationRecord) -> Self {
        Self {
            id: a.id,
            document_id: a.document_id,
            passage_id: a.passage_id,
            page_index: a.page_index,
            kind: a.kind,
            quote: a.quote,
            note: a.note,
            color: a.color,
            created_at: a.created_at,
        }
    }
}

#[derive(uniffi::Object)]
pub struct Engine {
    store: Mutex<Store>,
}
impl Engine {
    fn lock(&self) -> Result<MutexGuard<'_, Store>, EngineError> {
        self.store.lock().map_err(|_| EngineError::Storage {
            detail: "Database lock poisoned".into(),
        })
    }
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn open(database_path: String) -> Result<Arc<Self>, EngineError> {
        Ok(Arc::new(Self {
            store: Mutex::new(Store::open(database_path)?),
        }))
    }
    pub fn status(&self) -> Result<EngineStatus, EngineError> {
        let s = self.lock()?.status()?;
        Ok(EngineStatus {
            engine_version: s.engine_version,
            schema_version: s.schema_version,
            document_count: s.document_count,
            fts5_enabled: s.fts5_enabled,
        })
    }
    pub fn register_document(
        &self,
        id: String,
        title: String,
        local_path: String,
        media_type: String,
    ) -> Result<(), EngineError> {
        self.lock()?.register(&core::Document {
            id,
            title,
            local_path,
            media_type,
            page_index: 0,
            scroll_offset: 0,
            ingestion_state: "pending".into(),
            indexed_passages: 0,
            error: None,
        })?;
        Ok(())
    }
    pub fn documents(&self) -> Result<Vec<DocumentRecord>, EngineError> {
        Ok(self
            .lock()?
            .documents()?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn save_position(
        &self,
        document_id: String,
        page_index: u32,
        scroll_offset: u32,
    ) -> Result<(), EngineError> {
        self.lock()?
            .save_position(&document_id, page_index, scroll_offset)?;
        Ok(())
    }
    pub fn process_document(
        &self,
        document_id: String,
        batch_size: u32,
    ) -> Result<IngestionProgress, EngineError> {
        Ok(self
            .lock()?
            .process_document(&document_id, batch_size)?
            .into())
    }
    pub fn set_ingestion_paused(
        &self,
        document_id: String,
        paused: bool,
    ) -> Result<(), EngineError> {
        self.lock()?.set_ingestion_paused(&document_id, paused)?;
        Ok(())
    }
    pub fn retry_document(&self, document_id: String) -> Result<(), EngineError> {
        self.lock()?.retry_document(&document_id)?;
        Ok(())
    }
    pub fn passages(
        &self,
        document_id: String,
        page_number: Option<u32>,
    ) -> Result<Vec<PassageRecord>, EngineError> {
        Ok(self
            .lock()?
            .passages(&document_id, page_number)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn sections(&self, document_id: String) -> Result<Vec<SectionRecord>, EngineError> {
        Ok(self
            .lock()?
            .sections(&document_id)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn search(
        &self,
        query: String,
        document_id: Option<String>,
        limit: u32,
    ) -> Result<Vec<PassageRecord>, EngineError> {
        Ok(self
            .lock()?
            .search(&query, document_id.as_deref(), limit)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn concepts(&self, query: String, limit: u32) -> Result<Vec<ConceptRecord>, EngineError> {
        Ok(self
            .lock()?
            .concepts(&query, limit)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn graph(&self, concept_id: String) -> Result<Vec<GraphEdgeRecord>, EngineError> {
        Ok(self
            .lock()?
            .graph(&concept_id)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn references_for(
        &self,
        concept_id: String,
        source_document_id: Option<String>,
        limit: u32,
    ) -> Result<Vec<ReferenceRecord>, EngineError> {
        Ok(self
            .lock()?
            .references_for(&concept_id, source_document_id.as_deref(), limit)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn concepts_for_passage(
        &self,
        passage_id: String,
    ) -> Result<Vec<ConceptRecord>, EngineError> {
        Ok(self
            .lock()?
            .concepts_for_passage(&passage_id)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn annotations(&self, document_id: String) -> Result<Vec<AnnotationRecord>, EngineError> {
        Ok(self
            .lock()?
            .annotations(&document_id)?
            .into_iter()
            .map(Into::into)
            .collect())
    }
    pub fn save_annotation(&self, annotation: AnnotationRecord) -> Result<(), EngineError> {
        self.lock()?.save_annotation(&annotation.into())?;
        Ok(())
    }
    pub fn delete_annotation(&self, annotation_id: String) -> Result<(), EngineError> {
        self.lock()?.delete_annotation(&annotation_id)?;
        Ok(())
    }
    pub fn remove_document(&self, document_id: String) -> Result<(), EngineError> {
        self.lock()?.remove_document(&document_id)?;
        Ok(())
    }
}
