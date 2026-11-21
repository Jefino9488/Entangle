package dev.entangle.app.domain

data class LibraryDocument(
    val id: String,
    val title: String,
    val localPath: String,
    val mediaType: String,
    val pageIndex: Int = 0,
    val scrollOffset: Int = 0,
    val ingestionState: String = "pending",
    val indexedPassages: Int = 0,
    val error: String? = null,
)

data class SourcePassage(
    val id: String,
    val documentId: String,
    val documentTitle: String,
    val editionId: String,
    val section: String?,
    val pageNumber: Int?,
    val ordinal: Int,
    val text: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val extractionMethod: String,
    val processingVersion: String,
)

data class DocumentSection(
    val title: String,
    val ordinal: Int,
    val pageNumber: Int?,
)

data class KnowledgeConcept(
    val id: String,
    val label: String,
    val mentionCount: Int,
)

data class ReaderAnnotation(
    val id: String,
    val documentId: String,
    val passageId: String?,
    val pageIndex: Int,
    val kind: String,
    val quote: String,
    val note: String,
    val color: String,
    val createdAt: String,
)

data class KnowledgeEdge(
    val id: String,
    val source: KnowledgeConcept,
    val target: KnowledgeConcept,
    val relationshipType: String,
    val evidence: SourcePassage,
    val verificationStatus: String,
)

data class AssistantAnswer(
    val text: String,
    val sources: List<SourcePassage>,
    val method: String,
)

/** Source page numbers are one-based; reader page indexes are zero-based. */
data class SourceLocation(
    val documentId: String,
    val editionId: String?,
    val section: String?,
    val pageNumber: Int?,
    val startOffset: Int?,
    val endOffset: Int?,
    val extractionMethod: String,
    val processingVersion: String,
)

data class ContextualReference(
    val id: String,
    val documentTitle: String,
    val location: SourceLocation,
    val excerpt: String,
    val explanation: String,
    val relationshipType: String?,
    val verificationStatus: String,
)

/** The panel has its own selection and navigation, independent of the reader. */
data class ReferencePanelState(
    val concept: String,
    val references: List<ContextualReference>,
    val selectedReferenceId: String?,
    val isLoading: Boolean = false,
    val error: String? = null,
)

interface ReferenceRepository {
    suspend fun referencesFor(
        conceptId: String,
        sourceDocumentId: String?,
    ): List<ContextualReference>
}

interface DocumentRepository {
    suspend fun documents(): List<LibraryDocument>

    suspend fun savePosition(
        documentId: String,
        pageIndex: Int,
        scrollOffset: Int,
    )
}
