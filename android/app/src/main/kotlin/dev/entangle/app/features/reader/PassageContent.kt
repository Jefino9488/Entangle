package dev.entangle.app.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.SourcePassage
import java.time.Instant
import java.util.UUID

internal fun newAnnotation(
    documentId: String,
    passage: SourcePassage?,
    pageIndex: Int,
    kind: String,
    quote: String = "",
    note: String = "",
    color: String = "#FFF2AB",
) = ReaderAnnotation(
    id = UUID.randomUUID().toString(),
    documentId = documentId,
    passageId = passage?.id,
    pageIndex = pageIndex,
    kind = kind,
    quote = quote,
    note = note,
    color = color,
    createdAt = Instant.now().toString(),
)

@Composable
internal fun PassageContent(
    passage: SourcePassage,
    pageIndex: Int,
    annotations: List<ReaderAnnotation>,
    selected: Boolean,
    onSelectPassage: (SourcePassage) -> Unit,
    onSaveAnnotation: (ReaderAnnotation) -> Unit,
) {
    var selectionStart by rememberSaveable(passage.id) { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable(passage.id) { mutableIntStateOf(0) }
    var annotationKind by rememberSaveable(passage.id) { mutableStateOf<String?>(null) }
    val text =
        buildAnnotatedString {
            append(passage.text)
            annotations.filter { it.kind == "highlight" && it.quote.isNotBlank() }.forEach { annotation ->
                var start = passage.text.indexOf(annotation.quote)
                while (start >= 0) {
                    val color = runCatching { Color(android.graphics.Color.parseColor(annotation.color)) }.getOrDefault(Color(0xFFFFF2AB))
                    addStyle(SpanStyle(background = color), start, start + annotation.quote.length)
                    start = passage.text.indexOf(annotation.quote, start + annotation.quote.length)
                }
            }
        }
    val selection = TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
    val quote = if (selection.collapsed) passage.text else passage.text.substring(selection.min, selection.max)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = TextFieldValue(text, selection),
            onValueChange = {
                selectionStart = it.selection.start
                selectionEnd = it.selection.end
                if (!selected) onSelectPassage(passage)
            },
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().testTag("passage-${passage.id}"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { annotationKind = "highlight" },
                modifier = Modifier.testTag("highlight-${passage.id}"),
            ) { Text("Highlight") }
            TextButton(onClick = { annotationKind = "note" }, modifier = Modifier.testTag("note-${passage.id}")) { Text("Add note") }
            if (!selected) TextButton(onClick = { onSelectPassage(passage) }) { Text("Concepts") }
        }
        if (!selection.collapsed) Text("${selection.length} characters selected", style = MaterialTheme.typography.labelSmall)
    }
    annotationKind?.let { kind ->
        AnnotationDialog(
            kind,
            quote,
            onSave = { note, color ->
                onSaveAnnotation(newAnnotation(passage.documentId, passage, pageIndex, kind, quote, note, color))
                annotationKind = null
            },
            onDismiss = { annotationKind = null },
        )
    }
}

@Composable
private fun AnnotationDialog(
    kind: String,
    quote: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("#FFF2AB") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == "highlight") "Save highlight" else "Add source note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    quote.take(240) + if (quote.length > 240) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                )
                Text("The exact quote is linked to this source passage.", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (kind == "note") "Your note" else "Note (optional)") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("annotationNote"),
                    minLines = 2,
                )
                if (kind == "highlight") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("#FFF2AB" to "Yellow", "#C7EDCF" to "Green", "#CBE2FA" to "Blue").forEach { (value, label) ->
                            FilterChip(selected = color == value, onClick = { color = value }, label = { Text(label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(note.trim(), color)
            }, enabled = kind == "highlight" || note.isNotBlank(), modifier = Modifier.testTag("saveAnnotation")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
