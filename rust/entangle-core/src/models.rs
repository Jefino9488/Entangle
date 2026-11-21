#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Document {
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

#[derive(Debug)]
pub struct Status {
    pub engine_version: String,
    pub schema_version: u32,
    pub document_count: u32,
    pub fts5_enabled: bool,
}

#[derive(Debug, Clone)]
pub struct IngestionProgress {
    pub document_id: String,
    pub state: String,
    pub processed: u32,
    pub total: u32,
    pub error: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Passage {
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

#[derive(Debug, Clone)]
pub struct Section {
    pub title: String,
    pub ordinal: u32,
    pub page_number: Option<u32>,
}

#[derive(Debug, Clone)]
pub struct Concept {
    pub id: String,
    pub label: String,
    pub mention_count: u32,
}

#[derive(Debug, Clone)]
pub struct Reference {
    pub passage: Passage,
    pub concept_id: String,
    pub relationship_type: String,
    pub explanation: String,
    pub verification_status: String,
}

#[derive(Debug, Clone)]
pub struct GraphEdge {
    pub id: String,
    pub source: Concept,
    pub target: Concept,
    pub relationship_type: String,
    pub evidence: Passage,
    pub verification_status: String,
}

#[derive(Debug, Clone)]
pub struct Annotation {
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
