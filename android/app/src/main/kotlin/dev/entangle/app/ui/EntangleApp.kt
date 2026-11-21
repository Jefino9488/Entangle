package dev.entangle.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.entangle.app.core.AppState
import dev.entangle.app.core.EntangleViewModel
import dev.entangle.app.data.LocalLibraryRepository
import dev.entangle.app.domain.LibraryDocument
import dev.entangle.app.domain.SourcePassage
import dev.entangle.app.features.reader.ReaderWorkspace

private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    Library("Library", Icons.Default.Home),
    Reader("Reader", Icons.Default.Search),
    Knowledge("Knowledge", Icons.Default.Share),
    Assistant("Assistant", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntangleApp(
    viewModel: EntangleViewModel,
    widthClass: WindowWidthSizeClass,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Destination.Library.name
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importDocument) }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importModel) }
    val navigate: (Destination) -> Unit = { destination ->
        nav.navigate(destination.name) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState =
                true
        }
    }
    val openPassage: (SourcePassage) -> Unit = { passage ->
        viewModel.jumpToPassage(passage)
        navigate(Destination.Reader)
    }
    val rail = widthClass != WindowWidthSizeClass.Compact
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Color(0xFF244E43),
                primaryContainer = Color(0xFFDAE5D4),
                onPrimaryContainer = Color(0xFF18382D),
                secondary = Color(0xFF5D6750),
                secondaryContainer = Color(0xFFE2E7D9),
                onSecondaryContainer = Color(0xFF29362A),
                background = Color(0xFFF8F7F0),
                surface = Color(0xFFF8F7F0),
                surfaceContainerLow = Color(0xFFF0F1E8),
                surfaceContainer = Color(0xFFF0F1E8),
                surfaceContainerHighest = Color(0xFFE3E7DC),
            ),
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Entangle", style = MaterialTheme.typography.headlineSmall) }) },
            bottomBar = {
                if (!rail) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.name,
                                onClick = { navigate(item) },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("nav-${item.name}"),
                            )
                        }
                    }
                }
            },
            snackbarHost = {
                state.error?.let { error ->
                    Snackbar(action = { TextButton(onClick = viewModel::dismissError) { Text("Dismiss") } }) { Text(error) }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (rail) {
                    NavigationRail {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = route == item.name,
                                onClick = { navigate(item) },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("nav-${item.name}"),
                            )
                        }
                    }
                }
                NavHost(nav, Destination.Library.name, Modifier.weight(1f)) {
                    composable(Destination.Library.name) {
                        LibraryScreen(
                            state,
                            viewModel,
                            { importLauncher.launch(LocalLibraryRepository.supportedTypes.toTypedArray()) },
                            {
                                viewModel.openDocument(it)
                                navigate(Destination.Reader)
                            },
                            openPassage,
                        )
                    }
                    composable(Destination.Reader.name) {
                        val displayedDocumentId = state.selectedDocument?.id
                        ReaderWorkspace(
                            state,
                            widthClass,
                            viewModel::changePage,
                            { page, scroll -> viewModel.saveReaderScroll(displayedDocumentId, page, scroll) },
                            viewModel::selectPassage,
                            viewModel::jumpToPassage,
                            viewModel::findReferences,
                            viewModel::searchDocument,
                            viewModel::saveAnnotation,
                            viewModel::deleteAnnotation,
                            viewModel::selectReference,
                            viewModel::backToReferences,
                            viewModel::dismissReferences,
                            { navigate(Destination.Library) },
                        )
                    }
                    composable(Destination.Knowledge.name) { KnowledgeScreen(state, viewModel, openPassage) }
                    composable(Destination.Assistant.name) { AssistantScreen(state, viewModel, openPassage) }
                    composable(Destination.Settings.name) { SettingsScreen(state, viewModel) { modelLauncher.launch(arrayOf("*/*")) } }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: AppState,
    viewModel: EntangleViewModel,
    onImport: () -> Unit,
    onOpen: (LibraryDocument) -> Unit,
    onPassage: (SourcePassage) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleting = state.documents.find { it.id == deletingId }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Remove ${deleting.title}?") },
            text = { Text("This removes Entangle's local copy, passages, notes, and indexed evidence. The original source is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDocument(deleting)
                    deletingId = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your research library", style = MaterialTheme.typography.headlineLarge)
            Text("Read and connect the sources on your device.")
        }
        item {
            Button(onClick = onImport, enabled = !state.loading && !state.importing, modifier = Modifier.testTag("importDocument")) {
                Text(if (state.importing) "Saving local copy…" else "Import document")
            }
        }
        if (state.loading || state.importing) item { CircularProgressIndicator() }
        if (state.documents.isEmpty() && !state.loading) {
            item {
                Text("Import a PDF, EPUB, DOCX, text, Markdown, or HTML document to start your library.")
            }
        }
        if (state.documents.isNotEmpty()) {
            item {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        viewModel.searchLibrary(it)
                    },
                    label = { Text("Search across your sources") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("librarySearch"),
                )
            }
        }
        if (query.isNotBlank()) {
            if (state.librarySearchResults.isEmpty()) item { Text("No indexed passages match this search.") }
            items(state.librarySearchResults, key = { it.id }) { PassageCard(it, onPassage) }
        } else {
            items(state.documents, key = { it.id }) { document ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(document) }, modifier = Modifier.testTag("document-${document.id}")) {
                            Text(document.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "${document.mediaType.substringAfterLast(
                                '/',
                            )} · ${document.indexedPassages} passages · ${document.ingestionState}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        document.error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (document.pageIndex > 0) {
                            Text(
                                if (document.mediaType ==
                                    "application/pdf"
                                ) {
                                    "Continue at page ${document.pageIndex + 1}"
                                } else {
                                    "Continue at passage ${document.pageIndex + 1}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (document.ingestionState) {
                                "pending", "extracting", "indexing" ->
                                    TextButton(
                                        onClick = { viewModel.setIngestionPaused(document, true) },
                                    ) { Text("Pause indexing") }
                                "paused" ->
                                    TextButton(
                                        onClick = { viewModel.setIngestionPaused(document, false) },
                                    ) { Text("Resume indexing") }
                                "failed" -> TextButton(onClick = { viewModel.retryIngestion(document) }) { Text("Retry indexing") }
                            }
                            TextButton(onClick = { deletingId = document.id }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeScreen(
    state: AppState,
    viewModel: EntangleViewModel,
    onPassage: (SourcePassage) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Connected knowledge", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Concepts come from your indexed text. Connections below mean the concepts occur together in a source passage.") }
        if (state.graphConcept == null) {
            item {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        viewModel.searchConcepts(it)
                    },
                    label = { Text("Find a concept") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("conceptSearch"),
                )
            }
            if (state.concepts.isEmpty()) item { Text("No concepts match. Import and index sources, or try a different term.") }
            items(state.concepts, key = { it.id }) { concept ->
                Card(
                    onClick = { viewModel.selectGraphConcept(concept) },
                    modifier = Modifier.fillMaxWidth().testTag("concept-${concept.id}"),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(concept.label, style = MaterialTheme.typography.titleMedium)
                        Text("${concept.mentionCount} source mentions", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item {
                TextButton(onClick = viewModel::clearGraphConcept) { Text("Back to concepts") }
                Text(state.graphConcept.label, style = MaterialTheme.typography.titleLarge)
            }
            if (state.knowledgeLoading) item { CircularProgressIndicator() }
            if (state.graphEdges.isEmpty() && !state.knowledgeLoading) item { Text("No source-backed connections found for this concept.") }
            items(state.graphEdges, key = { it.id }) { edge ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${edge.source.label} ↔ ${edge.target.label}", style = MaterialTheme.typography.titleMedium)
                        Text("${edge.relationshipType} · ${edge.verificationStatus}", style = MaterialTheme.typography.labelMedium)
                        Text(edge.evidence.text)
                        Text(sourceLabel(edge.evidence), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onPassage(edge.evidence) }) { Text("Read supporting passage") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantScreen(
    state: AppState,
    viewModel: EntangleViewModel,
    onPassage: (SourcePassage) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    var useModel by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ask your sources", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(
                "Compare evidence across your library. Evidence mode quotes sources; local model mode explains them.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useModel, onClick = { useModel = false }, label = { Text("Evidence") })
                FilterChip(
                    selected = useModel,
                    enabled = state.model != null,
                    onClick = { useModel = true },
                    label = { Text("Local model") },
                )
            }
        }
        item {
            OutlinedTextField(
                question,
                { question = it },
                label = { Text("Question about your library") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("assistantQuestion"),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.askQuestion(question, useModel) },
                    enabled = question.isNotBlank() && !state.assistantLoading,
                    modifier = Modifier.testTag("askQuestion"),
                ) { Text("Ask") }
                if (state.assistantLoading) TextButton(onClick = viewModel::cancelAnswer) { Text("Stop") }
            }
        }
        if (state.assistantLoading) {
            item {
                CircularProgressIndicator()
                Text("Reading local evidence…")
            }
        }
        state.assistantAnswer?.let { answer ->
            item { Text(answer.method, style = MaterialTheme.typography.labelMedium) }
            item {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        answer.text,
                        modifier = Modifier.testTag("assistantAnswer"),
                    )
                }
            }
            if (answer.sources.isNotEmpty()) item { Text("Source evidence", style = MaterialTheme.typography.titleLarge) }
            items(answer.sources, key = { it.id }) { PassageCard(it, onPassage) }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: AppState,
    viewModel: EntangleViewModel,
    onImportModel: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("On your device", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(
                "Your documents, notes, passages and model stay in private device storage. No account or Internet permission is required.",
            )
        }
        item { Text(state.engineSummary, style = MaterialTheme.typography.labelLarge) }
        item { Text("${state.documents.size} documents · ${state.documents.sumOf { it.indexedPassages }} indexed passages") }
        item { Text("Local language model", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                state.model?.let { "${it.name} · ${it.sizeBytes / (1024 * 1024)} MB" }
                    ?: "Evidence search works without a model. Import an instruction GGUF to enable on-device explanations.",
            )
        }
        item {
            Button(
                onClick = onImportModel,
                enabled = !state.modelImporting && !state.assistantLoading,
                modifier = Modifier.testTag("importModel"),
            ) {
                Text(if (state.modelImporting) "Saving and validating model…" else "Import GGUF model")
            }
        }
        if (state.modelImporting) item { CircularProgressIndicator() }
        if (state.model !=
            null
        ) {
            item { TextButton(onClick = viewModel::removeModel, enabled = !state.assistantLoading) { Text("Remove model") } }
        }
        item {
            Text(
                "Use an instruction GGUF below 512 MB. Context and output are bounded; model memory is released after each answer.",
            )
        }
        item {
            Text(
                "Deleting Entangle removes its private library. Keep your original source files.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PassageCard(
    passage: SourcePassage,
    onOpen: (SourcePassage) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(passage.documentTitle, style = MaterialTheme.typography.titleMedium)
            Text(sourceLabel(passage), style = MaterialTheme.typography.labelMedium)
            Text(passage.text.take(700))
            TextButton(onClick = { onOpen(passage) }) { Text("Read passage") }
        }
    }
}

private fun sourceLabel(passage: SourcePassage): String =
    "${passage.section ?: "Source"} · ${passage.pageNumber?.let { "Page $it" } ?: "Passage ${passage.ordinal + 1}"}"
