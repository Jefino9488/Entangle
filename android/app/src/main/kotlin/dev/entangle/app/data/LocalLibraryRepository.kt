package dev.entangle.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.entangle.app.domain.AssistantAnswer
import dev.entangle.app.domain.ContextualReference
import dev.entangle.app.domain.DocumentRepository
import dev.entangle.app.domain.DocumentSection
import dev.entangle.app.domain.KnowledgeConcept
import dev.entangle.app.domain.KnowledgeEdge
import dev.entangle.app.domain.LibraryDocument
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.SourceLocation
import dev.entangle.app.domain.SourcePassage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.entangle_android.AnnotationRecord
import uniffi.entangle_android.Engine
import uniffi.entangle_android.EngineStatus
import uniffi.entangle_android.PassageRecord
import uniffi.entangle_android.generateLocalAnswer
import uniffi.entangle_android.validateLocalModel
import java.io.File
import java.util.UUID

/** All engine calls and file access run on IO. Rust exclusively owns library.sqlite. */
class LocalLibraryRepository(
    private val context: Context,
) : DocumentRepository {
    private val engine: Engine by lazy {
        val directory = File(context.noBackupFilesDir, "knowledge").apply { mkdirs() }
        Engine.open(File(directory, "library.sqlite").absolutePath)
    }

    suspend fun status(): EngineStatus = withContext(Dispatchers.IO) { engine.status() }

    override suspend fun documents(): List<LibraryDocument> =
        withContext(Dispatchers.IO) {
            engine.documents().map {
                LibraryDocument(
                    it.id,
                    it.title,
                    it.localPath,
                    it.mediaType,
                    it.pageIndex.toInt(),
                    it.scrollOffset.toInt(),
                    it.ingestionState,
                    it.indexedPassages.toInt(),
                    it.error,
                )
            }
        }

    suspend fun importDocument(uri: Uri): LibraryDocument =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val title =
                resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.takeIf { it.isNotBlank() } ?: "Imported document"
            val extension = title.substringAfterLast('.', "").lowercase()
            val mediaType =
                when (extension) {
                    "pdf" -> "application/pdf"
                    "epub" -> "application/epub+zip"
                    "txt" -> "text/plain"
                    "md", "markdown" -> "text/markdown"
                    "html", "htm" -> "text/html"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> resolver.getType(uri) ?: "application/octet-stream"
                }
            require(mediaType in supportedTypes) { "Choose a PDF, EPUB, text, Markdown, HTML, or DOCX document." }
            val id = UUID.randomUUID().toString()
            val directory = File(context.noBackupFilesDir, "documents").apply { mkdirs() }
            val partial = File(directory, "$id.part")
            val destination = File(directory, id)
            try {
                val input = resolver.openInputStream(uri) ?: error("The document provider could not open this file.")
                input.use { source ->
                    partial.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            target.write(buffer, 0, read)
                        }
                    }
                }
                require(partial.length() > 0) { "This document is empty." }
                check(partial.renameTo(destination)) { "Could not save the local document." }
                engine.registerDocument(id, title, destination.absolutePath, mediaType)
                LibraryDocument(id, title, destination.absolutePath, mediaType)
            } catch (error: Exception) {
                partial.delete()
                destination.delete()
                throw error
            }
        }

    override suspend fun savePosition(
        documentId: String,
        pageIndex: Int,
        scrollOffset: Int,
    ) = withContext(Dispatchers.IO) {
        require(pageIndex >= 0 && scrollOffset >= 0)
        engine.savePosition(documentId, pageIndex.toUInt(), scrollOffset.toUInt())
    }

    suspend fun processDocument(id: String) = withContext(Dispatchers.IO) { engine.processDocument(id, 32u) }

    suspend fun setPaused(
        id: String,
        paused: Boolean,
    ) = withContext(Dispatchers.IO) { engine.setIngestionPaused(id, paused) }

    suspend fun retryDocument(id: String) = withContext(Dispatchers.IO) { engine.retryDocument(id) }

    suspend fun passages(id: String): List<SourcePassage> =
        withContext(Dispatchers.IO) {
            engine.passages(id, null).map(PassageRecord::toDomain)
        }

    suspend fun sections(id: String): List<DocumentSection> =
        withContext(Dispatchers.IO) {
            engine.sections(id).map { DocumentSection(it.title, it.ordinal.toInt(), it.pageNumber?.toInt()) }
        }

    suspend fun search(
        query: String,
        documentId: String? = null,
    ): List<SourcePassage> =
        withContext(Dispatchers.IO) {
            engine.search(query, documentId, 40u).map(PassageRecord::toDomain)
        }

    suspend fun concepts(query: String = ""): List<KnowledgeConcept> =
        withContext(Dispatchers.IO) {
            engine.concepts(query, 100u).map { KnowledgeConcept(it.id, it.label, it.mentionCount.toInt()) }
        }

    suspend fun conceptsForPassage(id: String): List<KnowledgeConcept> =
        withContext(Dispatchers.IO) {
            engine.conceptsForPassage(id).map { KnowledgeConcept(it.id, it.label, it.mentionCount.toInt()) }
        }

    suspend fun referencesFor(
        conceptId: String,
        sourceId: String?,
    ): List<ContextualReference> =
        withContext(Dispatchers.IO) {
            engine.referencesFor(conceptId, sourceId, 40u).map { reference ->
                val passage = reference.passage
                ContextualReference(
                    passage.id,
                    passage.documentTitle,
                    SourceLocation(
                        passage.documentId,
                        passage.editionId,
                        passage.section,
                        passage.pageNumber?.toInt(),
                        passage.startOffset?.toInt(),
                        passage.endOffset?.toInt(),
                        passage.extractionMethod,
                        passage.processingVersion,
                    ),
                    passage.text,
                    reference.explanation,
                    reference.relationshipType,
                    reference.verificationStatus,
                )
            }
        }

    suspend fun graph(id: String): List<KnowledgeEdge> =
        withContext(Dispatchers.IO) {
            engine.graph(id).map { edge ->
                KnowledgeEdge(
                    edge.id,
                    KnowledgeConcept(edge.source.id, edge.source.label, edge.source.mentionCount.toInt()),
                    KnowledgeConcept(edge.target.id, edge.target.label, edge.target.mentionCount.toInt()),
                    edge.relationshipType,
                    edge.evidence.toDomain(),
                    edge.verificationStatus,
                )
            }
        }

    suspend fun annotations(documentId: String): List<ReaderAnnotation> =
        withContext(Dispatchers.IO) {
            engine.annotations(documentId).map {
                ReaderAnnotation(
                    it.id,
                    it.documentId,
                    it.passageId,
                    it.pageIndex.toInt(),
                    it.kind,
                    it.quote,
                    it.note,
                    it.color,
                    it.createdAt,
                )
            }
        }

    suspend fun saveAnnotation(annotation: ReaderAnnotation) =
        withContext(Dispatchers.IO) {
            engine.saveAnnotation(
                AnnotationRecord(
                    annotation.id,
                    annotation.documentId,
                    annotation.passageId,
                    annotation.pageIndex.toUInt(),
                    annotation.kind,
                    annotation.quote,
                    annotation.note,
                    annotation.color,
                    annotation.createdAt,
                ),
            )
        }

    suspend fun deleteAnnotation(id: String) = withContext(Dispatchers.IO) { engine.deleteAnnotation(id) }

    suspend fun removeDocument(document: LibraryDocument) =
        withContext(Dispatchers.IO) {
            engine.removeDocument(document.id)
            File(document.localPath).delete()
        }

    suspend fun answer(
        question: String,
        useModel: Boolean,
    ): AssistantAnswer =
        withContext(Dispatchers.IO) {
            val ignored =
                setOf(
                    "what",
                    "which",
                    "when",
                    "where",
                    "does",
                    "have",
                    "about",
                    "explain",
                    "compare",
                    "between",
                    "these",
                    "sources",
                    "from",
                    "with",
                    "this",
                    "that",
                    "the",
                    "and",
                    "how",
                    "why",
                    "are",
                    "for",
                )
            val terms =
                Regex("[\\p{L}\\p{N}]+")
                    .findAll(question.lowercase())
                    .map {
                        it.value
                    }.filter { it.length > 2 && it !in ignored }
                    .distinct()
                    .toList()
            val query = terms.joinToString(" ").ifBlank { question.trim() }
            val candidates = engine.search(query, null, 40u).map(PassageRecord::toDomain)
            val evidence =
                candidates
                    .groupBy { it.documentId }
                    .values
                    .flatMap { it.take(2) }
                    .take(8)
            if (evidence.isEmpty()) {
                return@withContext AssistantAnswer(
                    "No indexed passage supports this question yet. Try a term used in your sources, or check that indexing has finished.",
                    emptyList(),
                    "Local evidence",
                )
            }
            val contexts =
                evidence
                    .mapIndexed { index, passage ->
                        val title = passage.documentTitle.replace('\n', ' ').replace('\r', ' ')
                        val section = passage.section?.replace('\n', ' ')?.replace('\r', ' ') ?: "Source"
                        val location = passage.pageNumber?.let { "page $it" } ?: "passage ${passage.ordinal + 1}"
                        val excerpt =
                            passage.text
                                .take(1800)
                                .replace('\n', ' ')
                                .replace('\r', ' ')
                        "[${index + 1}] $title · $section · $location\n$excerpt"
                    }.joinToString("\n\n")
            val model = modelInfo()
            val text =
                if (useModel) {
                    require(model != null) { "Import a local GGUF model to use model inference." }
                    generateLocalAnswer(model.path, question, contexts, 256u)
                } else {
                    val sourceCount = evidence.map { it.documentId }.distinct().size
                    "Found ${evidence.size} relevant passages across $sourceCount source${if (sourceCount == 1) "" else "s"}.\n\n$contexts"
                }
            AssistantAnswer(text, evidence, if (useModel) "On-device model · source context" else "Local evidence · exact source excerpts")
        }

    suspend fun modelInfo(): LocalModelInfo? =
        withContext(Dispatchers.IO) {
            val directory = File(context.noBackupFilesDir, "models")
            val model = File(directory, "current.gguf")
            if (!model.isFile) {
                null
            } else {
                LocalModelInfo(
                    File(directory, "name.txt")
                        .takeIf {
                            it.isFile
                        }?.readText() ?: "Local GGUF",
                    model.absolutePath,
                    model.length(),
                )
            }
        }

    suspend fun importModel(uri: Uri): LocalModelInfo =
        withContext(Dispatchers.IO) {
            val title =
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Local GGUF"
            val directory = File(context.noBackupFilesDir, "models").apply { mkdirs() }
            val partial = File(directory, "pending.gguf")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    partial.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            require(total <= 512L * 1024 * 1024) { "Choose a quantized GGUF below 512 MB for this device's memory budget." }
                            output.write(bytes, 0, count)
                        }
                    }
                }
                partial.inputStream().use { input ->
                    val header = ByteArray(4)
                    require(
                        input.read(header) == 4 && String(header, Charsets.US_ASCII) == "GGUF",
                    ) { "The selected file is not a GGUF model." }
                }
                validateLocalModel(partial.absolutePath)
                val destination = File(directory, "current.gguf")
                check(partial.renameTo(destination)) { "Could not save the model." }
                File(directory, "name.txt").writeText(title)
                LocalModelInfo(title, destination.absolutePath, destination.length())
            } finally {
                partial.delete()
            }
        }

    suspend fun removeModel() =
        withContext(Dispatchers.IO) {
            File(context.noBackupFilesDir, "models/current.gguf").delete()
            File(context.noBackupFilesDir, "models/name.txt").delete()
        }

    companion object {
        val supportedTypes =
            setOf(
                "application/pdf",
                "application/epub+zip",
                "text/plain",
                "text/markdown",
                "text/html",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
    }
}

data class LocalModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
)

private fun PassageRecord.toDomain() =
    SourcePassage(
        id,
        documentId,
        documentTitle,
        editionId,
        section,
        pageNumber?.toInt(),
        ordinal.toInt(),
        text,
        startOffset?.toInt(),
        endOffset?.toInt(),
        extractionMethod,
        processingVersion,
    )
