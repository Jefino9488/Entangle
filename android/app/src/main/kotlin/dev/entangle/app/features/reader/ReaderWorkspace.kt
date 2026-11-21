package dev.entangle.app.features.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.entangle.app.core.AppState
import dev.entangle.app.domain.KnowledgeConcept
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.SourcePassage
import dev.entangle.app.features.references.ReferencePanel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderWorkspace(
    state: AppState,
    widthClass: WindowWidthSizeClass,
    onPageChange: (Int) -> Unit,
    onPositionChange: (Int, Int) -> Unit,
    onSelectPassage: (SourcePassage) -> Unit,
    onJumpToPassage: (SourcePassage) -> Unit,
    onFindReferences: (KnowledgeConcept) -> Unit,
    onSearch: (String) -> Unit,
    onSaveAnnotation: (ReaderAnnotation) -> Unit,
    onDeleteAnnotation: (String) -> Unit,
    onReferenceSelect: (String) -> Unit,
    onReferenceBack: () -> Unit,
    onReferenceDismiss: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val document = state.selectedDocument
    if (document == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose a source to read", style = MaterialTheme.typography.headlineMedium)
            Text("Open an imported document from your library to read, annotate, and explore related passages.")
            Button(onClick = onOpenLibrary) { Text("Open library") }
        }
        return
    }
    var dialog by rememberSaveable(document.id) { mutableStateOf<String?>(null) }
    var showPageImage by rememberSaveable(document.id) { mutableStateOf(document.mediaType == "application/pdf") }
    var readerFraction by rememberSaveable { mutableFloatStateOf(0.58f) }
    val panel = state.referencePanel
    val expanded = widthClass == WindowWidthSizeClass.Expanded
    val pdf = document.mediaType == "application/pdf"
    val currentPassages =
        if (pdf) {
            state.passages.filter { it.pageNumber == document.pageIndex + 1 }
        } else {
            state.passages.filter { it.ordinal == document.pageIndex }
        }
    val currentPassage = currentPassages.find { it.id == state.selectedPassage?.id } ?: currentPassages.firstOrNull()
    LaunchedEffect(currentPassage?.id) { currentPassage?.let(onSelectPassage) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(if (expanded && panel != null) readerFraction else 1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                currentPassage?.section?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { dialog = "search" }, modifier = Modifier.testTag("readerSearch")) { Text("Search") }
                    TextButton(onClick = { dialog = "contents" }, modifier = Modifier.testTag("readerContents")) { Text("Contents") }
                    TextButton(onClick = { dialog = "annotations" }, modifier = Modifier.testTag("readerAnnotations")) {
                        Text("Notes (${state.annotations.size})")
                    }
                    TextButton(
                        onClick = { onSaveAnnotation(newAnnotation(document.id, currentPassage, document.pageIndex, "bookmark")) },
                        modifier = Modifier.testTag("bookmarkPage"),
                    ) { Text("Bookmark") }
                    if (pdf) {
                        FilterChip(selected = showPageImage, onClick = { showPageImage = true }, label = { Text("Page") })
                        FilterChip(selected = !showPageImage, onClick = { showPageImage = false }, label = { Text("Text") })
                    }
                }
            }
            key(document.id, document.pageIndex, showPageImage) {
                ReaderContent(
                    state,
                    currentPassages,
                    showPageImage,
                    panel != null,
                    onPositionChange,
                    onSelectPassage,
                    onFindReferences,
                    onSaveAnnotation,
                    Modifier.weight(1f),
                )
            }
            val count = if (pdf) state.preview?.pageCount ?: 1 else state.passages.size.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onPageChange(document.pageIndex - 1) }, enabled = document.pageIndex > 0 && !state.previewLoading) {
                    Text("Previous")
                }
                Text(
                    "${if (pdf) "Page" else "Passage"} ${document.pageIndex + 1} of $count",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(
                    onClick = { onPageChange(document.pageIndex + 1) },
                    enabled = document.pageIndex + 1 < count && !state.previewLoading,
                ) { Text("Next") }
            }
        }
        if (expanded && panel != null) {
            VerticalDivider()
            Surface(
                Modifier.weight(1f - readerFraction).fillMaxHeight().testTag("referenceSidePanel"),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Slider(
                        value = readerFraction,
                        onValueChange = { readerFraction = it },
                        valueRange = 0.5f..0.65f,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("panelResize"),
                    )
                    ReferencePanel(panel, onReferenceSelect, onReferenceBack, onReferenceDismiss, Modifier.weight(1f))
                }
            }
        }
    }
    if (!expanded && panel != null) {
        ModalBottomSheet(
            onDismissRequest = onReferenceDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            modifier = Modifier.testTag("referenceSheet"),
        ) {
            ReferencePanel(panel, onReferenceSelect, onReferenceBack, onReferenceDismiss, Modifier.fillMaxHeight(0.92f))
        }
    }
    when (dialog) {
        "search" ->
            ReaderSearchDialog(
                state.readerSearchResults,
                state.readerSearchLoading,
                onSearch,
                {
                    onJumpToPassage(it)
                    dialog = null
                },
                { dialog = null },
            )
        "contents" ->
            ReaderContentsDialog(state.sections, state.passages, {
                onJumpToPassage(it)
                dialog = null
            }, { dialog = null })
        "annotations" ->
            ReaderAnnotationsDialog(
                state.annotations,
                onJump = { annotation ->
                    val passage = state.passages.find { it.id == annotation.passageId }
                    if (passage != null) onJumpToPassage(passage) else onPageChange(annotation.pageIndex)
                    dialog = null
                },
                onDelete = onDeleteAnnotation,
                onDismiss = { dialog = null },
            )
    }
}

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    state: AppState,
    passages: List<SourcePassage>,
    showPageImage: Boolean,
    paneOpen: Boolean,
    onPositionChange: (Int, Int) -> Unit,
    onSelectPassage: (SourcePassage) -> Unit,
    onFindReferences: (KnowledgeConcept) -> Unit,
    onSaveAnnotation: (ReaderAnnotation) -> Unit,
    modifier: Modifier,
) {
    val document = state.selectedDocument ?: return
    val scroll = rememberScrollState(document.scrollOffset)
    val positionCallback by rememberUpdatedState(onPositionChange)
    val contentReady =
        if (showPageImage && document.mediaType == "application/pdf") {
            state.preview?.bitmap != null
        } else {
            passages.isNotEmpty() || state.preview?.text != null
        }
    var anchor by rememberSaveable(document.id, document.pageIndex) { mutableIntStateOf(document.scrollOffset) }
    var paneWasOpen by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }
    // Capture before this composition's narrower pane can clamp the ScrollState during measurement.
    val enteringPaneOffset = remember(paneOpen) { scroll.value }
    val latestPaneOpen by rememberUpdatedState(paneOpen)
    val latestContentReady by rememberUpdatedState(contentReady)
    LaunchedEffect(paneOpen, contentReady) {
        if (paneOpen) {
            if (!paneWasOpen) {
                anchor = enteringPaneOffset
                paneWasOpen = true
                if (contentReady) positionCallback(document.pageIndex, anchor)
            }
            restoring = false
        } else if (contentReady) {
            restoring = true
            // Wait for the full-width page (or newly loaded bitmap) to receive its final scroll bounds.
            withFrameNanos { }
            withFrameNanos { }
            scroll.scrollTo(anchor)
            anchor = scroll.value
            paneWasOpen = false
            restoring = false
        }
    }
    LaunchedEffect(document.id, document.pageIndex, scroll, paneOpen, contentReady) {
        if (contentReady) {
            snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, restoring) }
                .filter { !it.third && (!paneOpen || it.second) }
                .map { it.first }
                .distinctUntilChanged()
                .onEach { anchor = it }
                .debounce(250)
                .collect { positionCallback(document.pageIndex, it) }
        }
    }
    DisposableEffect(document.id, document.pageIndex, scroll) {
        onDispose {
            if (latestContentReady) {
                positionCallback(document.pageIndex, if (latestPaneOpen || restoring) anchor else scroll.value)
            }
        }
    }
    Column(
        modifier.verticalScroll(scroll).padding(20.dp).testTag("primaryReaderScroll"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (showPageImage && document.mediaType == "application/pdf") {
            if (state.previewLoading) CircularProgressIndicator()
            state.preview?.bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Document page ${document.pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.preview?.message?.let { Text(it) }
            if (passages.isNotEmpty()) Text("Select Text above to annotate or explore the concepts on this page.")
        } else if (passages.isNotEmpty()) {
            passages.forEach { passage ->
                PassageContent(
                    passage,
                    document.pageIndex,
                    state.annotations.filter { it.passageId == passage.id },
                    state.selectedPassage?.id == passage.id,
                    onSelectPassage,
                    onSaveAnnotation,
                )
            }
        } else {
            if (state.previewLoading) CircularProgressIndicator()
            state.preview?.text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            when (document.ingestionState) {
                "failed", "error" -> Text(document.error ?: "Text extraction failed. Retry processing from the library.")
                "complete", "completed", "ready" -> Text("No text was extracted at this location. Image-only pages require OCR.")
                "paused" -> Text("Processing is paused. Resume from the library to extract text and discover concepts.")
                else -> {
                    CircularProgressIndicator()
                    Text("Extracting local text… ${document.indexedPassages} passages indexed.")
                }
            }
        }
        if (state.selectedConcepts.isNotEmpty()) {
            Text("Concepts in this passage", style = MaterialTheme.typography.titleSmall)
            Text("Choose a concept to find its mentions in other sources.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.selectedConcepts.forEach { concept ->
                    FilterChip(
                        selected = false,
                        onClick = { onFindReferences(concept) },
                        label = { Text(concept.label) },
                        modifier = Modifier.testTag("concept-${concept.id}"),
                    )
                }
            }
        }
    }
}
