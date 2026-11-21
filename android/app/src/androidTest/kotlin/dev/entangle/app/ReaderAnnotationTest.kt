package dev.entangle.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import dev.entangle.app.domain.ReaderAnnotation
import dev.entangle.app.domain.SourcePassage
import dev.entangle.app.features.reader.PassageContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderAnnotationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedSourceQuoteIsRetainedInHighlightAndNote() {
        val annotations = mutableStateListOf<ReaderAnnotation>()
        val passage =
            SourcePassage(
                id = "source-passage",
                documentId = "source-document",
                documentTitle = "Anatomy source",
                editionId = "source-edition",
                section = "Upper limb",
                pageNumber = 7,
                ordinal = 0,
                text = "Radial nerve supplies the triceps.",
                startOffset = 0,
                endOffset = 33,
                extractionMethod = "test-source",
                processingVersion = "test-v1",
            )
        compose.setContent {
            MaterialTheme { PassageContent(passage, 6, annotations, true, {}, { annotations.add(it) }) }
        }
        compose.onNodeWithTag("passage-source-passage").performTextInputSelection(TextRange(0, 12))
        compose.onNodeWithTag("highlight-source-passage").performClick()
        compose.onNodeWithTag("saveAnnotation").performClick()
        compose.runOnIdle {
            assertEquals("Radial nerve", annotations.single().quote)
            assertEquals("highlight", annotations.single().kind)
            assertEquals("source-passage", annotations.single().passageId)
            assertEquals(6, annotations.single().pageIndex)
        }
        compose.onNodeWithTag("note-source-passage").performClick()
        compose.onNodeWithTag("annotationNote").performTextInput("Compare the clinical explanation.")
        compose.onNodeWithTag("saveAnnotation").performClick()
        compose.runOnIdle {
            assertEquals("Radial nerve", annotations.last().quote)
            assertEquals("note", annotations.last().kind)
            assertEquals("Compare the clinical explanation.", annotations.last().note)
        }
    }
}
