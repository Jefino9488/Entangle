# ADR 0003: Defer models and full processing behind resource-aware contracts

Status: accepted for initialization. Date: 2026-11-21.

The target device normally has about 1.5 GB free RAM. A reader foundation should not download, load or retain an inference model. Keep inference sessions cancellable and explicitly closeable; the architecture must not hardcode a runtime or model.

Evaluate llama.cpp, LiteRT-LM and small quantized language/embedding/entity models later. Measure resident memory, peak initialization memory, latency, context length, thermals, Android ABI support, and model/runtime licenses on the Redmi K50i. Benchmark one active model at a time; do not assume embeddings and generation can coexist in memory.

Reuse specialized document libraries. Start with Android PdfRenderer for bounded visual previews; PDF text extraction, layout, EPUB parsing, DOCX, HTML/Markdown parsing, and OCR are separate integration decisions. Preserve original coordinates and extraction versions. Avoid a custom PDF renderer, OCR engine, database, or LLM runtime.

WorkManager will own Android scheduling; Rust will own incremental processing and checkpoints. A future pipeline must support small batches, pause/resume and cancellation, with low-memory handling and no persistent background polling. No fake ingestion jobs or fabricated AI/graph output are introduced during setup.
