package dev.entangle.app.features.references

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.entangle.app.domain.ReferencePanelState

/** Shared panel contract for phone sheets and expanded-window side panels. */
@Composable
fun ReferencePanel(
    state: ReferencePanelState,
    onSelectReference: (String) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Contextual references", style = MaterialTheme.typography.titleLarge)
                Text(state.concept, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
        val reference = state.references.find { it.id == state.selectedReferenceId }
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.padding(24.dp))
            state.error != null -> Text(state.error, Modifier.padding(24.dp))
            reference != null -> {
                TextButton(onClick = onBackToList, modifier = Modifier.testTag("referenceBack")) { Text("Back to references") }
                LazyColumn(
                    Modifier.testTag("referencePreview"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Text(reference.documentTitle, style = MaterialTheme.typography.titleLarge) }
                    item {
                        Text(
                            "${reference.location.section ?: "Section unavailable"} · ${reference.location.pageNumber?.let {
                                "Page $it"
                            } ?: "Page unavailable"}",
                        )
                    }
                    item { SelectionContainer { Text(reference.excerpt, style = MaterialTheme.typography.bodyLarge) } }
                    item { Text("Why this is related", style = MaterialTheme.typography.titleMedium) }
                    item { Text(reference.explanation) }
                    item {
                        Text(
                            "${reference.relationshipType ?: "Relationship unspecified"} · ${reference.verificationStatus}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    item {
                        Text(
                            "Source: ${reference.location.documentId}\nEdition: ${reference.location.editionId ?: "Unspecified"}\nExtraction: ${reference.location.extractionMethod}\nProcessing: ${reference.location.processingVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            else ->
                LazyColumn(
                    Modifier.testTag("referenceList"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.references.isEmpty()) item { Text("No local references found for this concept.") }
                    items(state.references, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.documentTitle, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${item.location.section ?: "Section unavailable"} · ${item.location.pageNumber?.let {
                                        "Page $it"
                                    } ?: "Page unavailable"}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(item.excerpt)
                                Text(item.explanation, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${item.relationshipType ?: "Related passage"} · ${item.verificationStatus}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Button(
                                    onClick = { onSelectReference(item.id) },
                                    modifier = Modifier.testTag("open-${item.id}"),
                                ) { Text("Inspect passage") }
                            }
                        }
                    }
                }
        }
    }
}
