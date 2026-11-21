package dev.entangle.app.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.entangle.app.domain.DocumentSection
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.SourcePassage

@Composable
internal fun ReaderSearchDialog(
    results: List<SourcePassage>,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onJump: (SourcePassage) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val search = {
        submitted = true
        onSearch(query.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search this document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        submitted = false
                    },
                    label = { Text("Words or phrase") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().testTag("readerSearchQuery"),
                )
                TextButton(
                    onClick = search,
                    enabled = query.isNotBlank(),
                    modifier = Modifier.testTag("submitReaderSearch"),
                ) { Text("Search") }
                if (loading) CircularProgressIndicator()
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (submitted && !loading && results.isEmpty()) item { Text("No matching passages in the indexed text.") }
                    items(results, key = { it.id }) { passage ->
                        Card(onClick = { onJump(passage) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(sourceLabel(passage), style = MaterialTheme.typography.labelMedium)
                                Text(passage.text, maxLines = 4)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ReaderContentsDialog(
    sections: List<DocumentSection>,
    passages: List<SourcePassage>,
    onJump: (SourcePassage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document contents") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sections.isEmpty()) item { Text("No section headings were extracted from this document.") }
                items(sections) { section ->
                    val passage = passages.find { it.ordinal == section.ordinal } ?: passages.find { it.ordinal >= section.ordinal }
                    TextButton(onClick = { passage?.let(onJump) }, enabled = passage != null, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(section.title)
                            Text(
                                section.pageNumber?.let { "Page $it" } ?: "Passage ${section.ordinal + 1}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ReaderAnnotationsDialog(
    annotations: List<ReaderAnnotation>,
    onJump: (ReaderAnnotation) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlights, notes and bookmarks") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (annotations.isEmpty()) {
                    item {
                        Text(
                            "Select text to highlight it or add a note. Bookmark a page to return to it later.",
                        )
                    }
                }
                items(annotations, key = { it.id }) { annotation ->
                    Card(Modifier.fillMaxWidth().testTag("annotation-${annotation.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(annotation.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium)
                            if (annotation.quote.isNotBlank()) Text(annotation.quote, maxLines = 4)
                            if (annotation.note.isNotBlank()) Text(annotation.note)
                            Text("Reading location ${annotation.pageIndex + 1}", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onJump(annotation) }) { Text("Open source") }
                                TextButton(
                                    onClick = { onDelete(annotation.id) },
                                    modifier = Modifier.testTag("delete-${annotation.id}"),
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun sourceLabel(passage: SourcePassage): String =
    listOfNotNull(passage.section, passage.pageNumber?.let { "Page $it" }).joinToString(" · ").ifBlank {
        "Passage ${passage.ordinal + 1}"
    }
