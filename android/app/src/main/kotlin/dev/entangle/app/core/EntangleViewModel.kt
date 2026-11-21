package dev.entangle.app.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dev.entangle.app.data.DocumentPreview
import dev.entangle.app.data.IngestionWorker
import dev.entangle.app.data.LocalLibraryRepository
import dev.entangle.app.data.LocalModelInfo
import dev.entangle.app.data.loadDocumentPreview
import dev.entangle.app.domain.AssistantAnswer
import dev.entangle.app.domain.DocumentSection
import dev.entangle.app.domain.KnowledgeConcept
import dev.entangle.app.domain.KnowledgeEdge
import dev.entangle.app.domain.LibraryDocument
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.ReferencePanelState
import dev.entangle.app.domain.SourcePassage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.entangle_android.cancelLocalGeneration

// Domain state contains actual library records only; no fixture documents or concepts.
data class AppState(
    val documents: List<LibraryDocument> = emptyList(),
    val selectedDocument: LibraryDocument? = null,
    val preview: DocumentPreview? = null,
    val loading: Boolean = true,
    val importing: Boolean = false,
    val previewLoading: Boolean = false,
    val engineSummary: String = "Checking local engine…",
    val error: String? = null,
    val passages: List<SourcePassage> = emptyList(),
    val sections: List<DocumentSection> = emptyList(),
    val annotations: List<ReaderAnnotation> = emptyList(),
    val readerSearchResults: List<SourcePassage> = emptyList(),
    val readerSearchLoading: Boolean = false,
    val selectedConcepts: List<KnowledgeConcept> = emptyList(),
    val selectedPassage: SourcePassage? = null,
    val referencePanel: ReferencePanelState? = null,
    val concepts: List<KnowledgeConcept> = emptyList(),
    val graphEdges: List<KnowledgeEdge> = emptyList(),
    val graphConcept: KnowledgeConcept? = null,
    val knowledgeLoading: Boolean = false,
    val librarySearchResults: List<SourcePassage> = emptyList(),
    val assistantAnswer: AssistantAnswer? = null,
    val assistantLoading: Boolean = false,
    val model: LocalModelInfo? = null,
    val modelImporting: Boolean = false,
)

class EntangleViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = LocalLibraryRepository(application)
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var previewJob: Job? = null
    private var referenceJob: Job? = null
    private var conceptJob: Job? = null
    private var searchJob: Job? = null
    private var knowledgeJob: Job? = null
    private var assistantJob: Job? = null
    private val positionMutex = Mutex()

    init {
        viewModelScope.launch {
            handleErrors {
                val status = repository.status()
                val documents = repository.documents()
                val model = repository.modelInfo()
                mutableState.update {
                    it.copy(
                        documents = documents,
                        model = model,
                        loading = false,
                        engineSummary =
                            "Rust ${status.engineVersion} · SQLite schema ${status.schemaVersion} · " +
                                "FTS5 ${if (status.fts5Enabled) "ready" else "unavailable"}",
                    )
                }
                documents.filter { it.ingestionState in setOf("pending", "extracting", "indexing") }.forEach {
                    IngestionWorker.enqueue(application, it.id)
                }
                documents.find { it.id == savedState.get<String>("documentId") }?.let(::openDocument)
                searchConcepts("")
            }
            mutableState.update { it.copy(loading = false) }
        }
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosByTagFlow(IngestionWorker.TAG).collect {
                handleErrors { refreshLibrary() }
            }
        }
    }

    private suspend fun refreshLibrary() {
        val documents = repository.documents()
        val current = mutableState.value.selectedDocument
        val updated = documents.find { it.id == current?.id }
        mutableState.update { state ->
            state.copy(
                documents = documents,
                selectedDocument = updated?.copy(pageIndex = current?.pageIndex ?: 0, scrollOffset = current?.scrollOffset ?: 0),
            )
        }
        if (updated != null && (updated.indexedPassages != current?.indexedPassages || updated.ingestionState != current?.ingestionState)) {
            loadReaderData(updated.id)
        }
        val concepts = repository.concepts()
        mutableState.update { it.copy(concepts = concepts) }
    }

    fun importDocument(uri: Uri) {
        if (mutableState.value.importing || mutableState.value.loading) return
        mutableState.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                handleErrors {
                    val document = repository.importDocument(uri)
                    IngestionWorker.enqueue(getApplication(), document.id)
                    refreshLibrary()
                }
            } finally {
                mutableState.update { it.copy(importing = false) }
            }
        }
    }

    fun setIngestionPaused(
        document: LibraryDocument,
        paused: Boolean,
    ) {
        viewModelScope.launch {
            handleErrors {
                repository.setPaused(document.id, paused)
                if (!paused) IngestionWorker.enqueue(getApplication(), document.id, replace = true)
                refreshLibrary()
            }
        }
    }

    fun retryIngestion(document: LibraryDocument) {
        viewModelScope.launch {
            handleErrors {
                repository.retryDocument(document.id)
                IngestionWorker.enqueue(getApplication(), document.id, replace = true)
                refreshLibrary()
            }
        }
    }

    fun removeDocument(document: LibraryDocument) {
        viewModelScope.launch {
            handleErrors {
                repository.setPaused(document.id, true)
                WorkManager.getInstance(getApplication()).cancelUniqueWork("ingest-${document.id}")
                repository.removeDocument(document)
                if (mutableState.value.selectedDocument?.id == document.id) {
                    savedState["documentId"] = null
                    mutableState.update {
                        it.copy(
                            selectedDocument = null,
                            preview = null,
                            passages = emptyList(),
                            sections = emptyList(),
                            annotations = emptyList(),
                            selectedConcepts = emptyList(),
                            referencePanel = null,
                        )
                    }
                }
                refreshLibrary()
            }
        }
    }

    fun openDocument(document: LibraryDocument) {
        referenceJob?.cancel()
        searchJob?.cancel()
        savedState["documentId"] = document.id
        mutableState.update {
            it.copy(
                selectedDocument = document,
                preview = null,
                passages = emptyList(),
                sections = emptyList(),
                annotations = emptyList(),
                readerSearchResults = emptyList(),
                selectedConcepts = emptyList(),
                selectedPassage = null,
                referencePanel = null,
            )
        }
        loadPreview(document)
        viewModelScope.launch { handleErrors { loadReaderData(document.id) } }
    }

    private suspend fun loadReaderData(id: String) {
        val passages = repository.passages(id)
        val sections = repository.sections(id)
        val annotations = repository.annotations(id)
        if (mutableState.value.selectedDocument?.id == id) {
            mutableState.update { it.copy(passages = passages, sections = sections, annotations = annotations) }
        }
    }

    fun changePage(pageIndex: Int) {
        val document = mutableState.value.selectedDocument ?: return
        val count =
            if (document.mediaType == "application/pdf") {
                mutableState.value.preview?.pageCount ?: 1
            } else {
                mutableState.value.passages.size
                    .coerceAtLeast(1)
            }
        if (pageIndex !in 0 until count) return
        savePosition(pageIndex, 0)
        if (document.mediaType == "application/pdf") loadPreview(document.copy(pageIndex = pageIndex, scrollOffset = 0))
    }

    fun selectPassage(passage: SourcePassage) {
        if (mutableState.value.selectedPassage?.id == passage.id) return
        mutableState.update { it.copy(selectedPassage = passage, selectedConcepts = emptyList()) }
        conceptJob?.cancel()
        conceptJob =
            viewModelScope.launch {
                handleErrors {
                    val concepts = repository.conceptsForPassage(passage.id)
                    if (mutableState.value.selectedPassage?.id == passage.id) mutableState.update { it.copy(selectedConcepts = concepts) }
                }
            }
    }

    fun jumpToPassage(passage: SourcePassage) {
        val document = mutableState.value.documents.find { it.id == passage.documentId } ?: return
        if (mutableState.value.selectedDocument?.id != document.id) openDocument(document)
        val page = if (document.mediaType == "application/pdf") (passage.pageNumber ?: 1) - 1 else passage.ordinal
        savePosition(page, 0)
        selectPassage(passage)
        if (document.mediaType == "application/pdf") loadPreview(document.copy(pageIndex = page, scrollOffset = 0))
    }

    fun savePosition(
        pageIndex: Int,
        scrollOffset: Int,
    ) {
        val document = mutableState.value.selectedDocument ?: return
        val updated = document.copy(pageIndex = pageIndex, scrollOffset = scrollOffset)
        mutableState.update {
            it.copy(
                selectedDocument = updated,
                documents = it.documents.map { item -> if (item.id == updated.id) updated else item },
            )
        }
        viewModelScope.launch {
            positionMutex.withLock { handleErrors { repository.savePosition(document.id, pageIndex, scrollOffset) } }
        }
    }

    fun saveReaderScroll(
        documentId: String?,
        pageIndex: Int,
        scrollOffset: Int,
    ) {
        val current = mutableState.value.selectedDocument ?: return
        if (current.id != documentId || current.pageIndex != pageIndex) return
        savePosition(pageIndex, scrollOffset)
    }

    fun searchDocument(query: String) {
        val id = mutableState.value.selectedDocument?.id ?: return
        searchJob?.cancel()
        mutableState.update { it.copy(readerSearchLoading = query.isNotBlank()) }
        searchJob =
            viewModelScope.launch {
                handleErrors {
                    val results = if (query.isBlank()) emptyList() else repository.search(query, id)
                    mutableState.update { it.copy(readerSearchResults = results) }
                }
                mutableState.update { it.copy(readerSearchLoading = false) }
            }
    }

    fun searchLibrary(query: String) {
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                handleErrors {
                    val results = if (query.isBlank()) emptyList() else repository.search(query)
                    mutableState.update { it.copy(librarySearchResults = results) }
                }
            }
    }

    fun findReferences(concept: KnowledgeConcept) {
        mutableState.update { it.copy(referencePanel = ReferencePanelState(concept.label, emptyList(), null, isLoading = true)) }
        referenceJob?.cancel()
        referenceJob =
            viewModelScope.launch {
                try {
                    val references = repository.referencesFor(concept.id, mutableState.value.selectedDocument?.id)
                    mutableState.update { it.copy(referencePanel = ReferencePanelState(concept.label, references, null)) }
                } catch (
                    error: CancellationException,
                ) {
                    throw error
                } catch (
                    error: Exception,
                ) {
                    mutableState.update { it.copy(referencePanel = it.referencePanel?.copy(isLoading = false, error = error.message)) }
                }
            }
    }

    fun selectReference(id: String) = mutableState.update { it.copy(referencePanel = it.referencePanel?.copy(selectedReferenceId = id)) }

    fun backToReferences() = mutableState.update { it.copy(referencePanel = it.referencePanel?.copy(selectedReferenceId = null)) }

    fun dismissReferences() {
        referenceJob?.cancel()
        mutableState.update { it.copy(referencePanel = null) }
    }

    fun saveAnnotation(annotation: ReaderAnnotation) {
        viewModelScope.launch {
            handleErrors {
                repository.saveAnnotation(annotation)
                loadReaderData(annotation.documentId)
            }
        }
    }

    fun deleteAnnotation(id: String) {
        val document = mutableState.value.selectedDocument ?: return
        viewModelScope.launch {
            handleErrors {
                repository.deleteAnnotation(id)
                loadReaderData(document.id)
            }
        }
    }

    fun searchConcepts(query: String) {
        knowledgeJob?.cancel()
        knowledgeJob =
            viewModelScope.launch {
                handleErrors {
                    val concepts = repository.concepts(query)
                    mutableState.update { it.copy(concepts = concepts) }
                }
            }
    }

    fun selectGraphConcept(concept: KnowledgeConcept) {
        knowledgeJob?.cancel()
        mutableState.update { it.copy(graphConcept = concept, graphEdges = emptyList(), knowledgeLoading = true) }
        knowledgeJob =
            viewModelScope.launch {
                handleErrors {
                    val edges = repository.graph(concept.id)
                    mutableState.update { it.copy(graphEdges = edges) }
                }
                mutableState.update { it.copy(knowledgeLoading = false) }
            }
    }

    fun clearGraphConcept() = mutableState.update { it.copy(graphConcept = null, graphEdges = emptyList()) }

    fun askQuestion(
        question: String,
        useModel: Boolean,
    ) {
        if (question.isBlank() || mutableState.value.assistantLoading) return
        mutableState.update { it.copy(assistantLoading = true, assistantAnswer = null, error = null) }
        assistantJob =
            viewModelScope.launch {
                try {
                    handleErrors {
                        val answer = repository.answer(question, useModel)
                        mutableState.update { it.copy(assistantAnswer = answer) }
                    }
                } finally {
                    mutableState.update { it.copy(assistantLoading = false) }
                }
            }
    }

    fun cancelAnswer() {
        viewModelScope.launch(Dispatchers.IO) { cancelLocalGeneration() }
        assistantJob?.cancel()
        mutableState.update { it.copy(assistantLoading = false) }
    }

    fun importModel(uri: Uri) {
        if (mutableState.value.modelImporting || mutableState.value.assistantLoading) return
        mutableState.update { it.copy(modelImporting = true) }
        viewModelScope.launch {
            try {
                handleErrors {
                    val model = repository.importModel(uri)
                    mutableState.update { it.copy(model = model) }
                }
            } finally {
                mutableState.update { it.copy(modelImporting = false) }
            }
        }
    }

    fun removeModel() {
        cancelAnswer()
        viewModelScope.launch {
            handleErrors {
                repository.removeModel()
                mutableState.update { it.copy(model = null) }
            }
        }
    }

    private fun loadPreview(document: LibraryDocument) {
        previewJob?.cancel()
        if (document.mediaType != "application/pdf" && document.mediaType !in setOf("text/plain", "text/markdown")) {
            mutableState.update { it.copy(preview = null, previewLoading = false) }
            return
        }
        previewJob?.cancel()
        mutableState.update { it.copy(previewLoading = true, error = null) }
        previewJob =
            viewModelScope.launch {
                handleErrors {
                    val preview = loadDocumentPreview(document, document.pageIndex)
                    mutableState.update { it.copy(preview = preview) }
                }
                mutableState.update { it.copy(previewLoading = false) }
            }
    }

    fun releasePreview() {
        previewJob?.cancel()
        mutableState.update { it.copy(preview = null, previewLoading = false) }
    }

    fun handleMemoryPressure() {
        cancelAnswer()
        releasePreview()
    }

    fun resumePreview() {
        mutableState.value.selectedDocument?.let { if (mutableState.value.preview == null) loadPreview(it) }
    }

    fun dismissError() = mutableState.update { it.copy(error = null) }

    private suspend fun handleErrors(block: suspend () -> Unit) {
        try {
            block()
        } catch (
            error: CancellationException,
        ) {
            throw error
        } catch (
            error: Exception,
        ) {
            mutableState.update { it.copy(error = error.message ?: "Could not complete the local operation.") }
        }
    }
}
