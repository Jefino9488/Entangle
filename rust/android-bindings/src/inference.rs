//! Optional model inference exports; storage APIs remain model-independent.

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum InferenceError {
    #[error("{detail}")]
    Local { detail: String },
}

impl From<entangle_inference::InferenceError> for InferenceError {
    fn from(error: entangle_inference::InferenceError) -> Self {
        Self::Local {
            detail: error.to_string(),
        }
    }
}

#[derive(uniffi::Record)]
pub struct ModelFileInfo {
    pub file_name: String,
    pub size_bytes: u64,
    pub gguf_version: u32,
}

#[uniffi::export]
pub fn validate_local_model(model_path: String) -> Result<ModelFileInfo, InferenceError> {
    let information = entangle_inference::validate_model_file(model_path)?;
    Ok(ModelFileInfo {
        file_name: information.file_name,
        size_bytes: information.size_bytes,
        gguf_version: information.gguf_version,
    })
}

#[uniffi::export]
pub fn generate_local_answer(
    model_path: String,
    question: String,
    evidence_text: String,
    max_tokens: u32,
) -> Result<String, InferenceError> {
    Ok(entangle_inference::generate_answer(
        model_path,
        &question,
        &evidence_text,
        max_tokens,
    )?)
}

#[uniffi::export]
pub fn cancel_local_generation() {
    entangle_inference::cancel_generation();
}
