package dev.entangle.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.entangle.app.core.AppState
import dev.entangle.app.domain.KnowledgeConcept
import dev.entangle.app.domain.ReferencePanelState
import dev.entangle.app.features.reader.ReaderWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReferenceLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactSheetPreviewAndDismissPreserveReaderScroll() = verifyLayout(WindowWidthSizeClass.Compact, "referenceSheet")

    @Test fun expandedSidePanelPreviewAndCollapsePreserveReaderScroll() = verifyLayout(WindowWidthSizeClass.Expanded, "referenceSidePanel")

    private fun verifyLayout(
        width: WindowWidthSizeClass,
        panelTag: String,
    ) {
        NativeSourceFixture().use { fixture ->
            val concept = fixture.radialConcept().let { KnowledgeConcept(it.id, it.label, it.mentionCount.toInt()) }
            val references = fixture.references()
            assertTrue(references.isNotEmpty())
            val passages = fixture.passages(fixture.primaryId)
            val state =
                mutableStateOf(
                    AppState(
                        loading = false,
                        selectedDocument = fixture.document(fixture.primaryId),
                        passages = passages,
                        preview = fixture.preview(),
                        selectedPassage = passages.first(),
                        selectedConcepts = listOf(concept),
                    ),
                )
            compose.setContent {
                MaterialTheme {
                    ReaderWorkspace(state.value, width, {}, { page, offset ->
                        state.value =
                            state.value.copy(
                                selectedDocument = state.value.selectedDocument!!.copy(pageIndex = page, scrollOffset = offset),
                            )
                    }, {}, {}, {
                        state.value = state.value.copy(referencePanel = ReferencePanelState(concept.label, references, null))
                    }, {}, {}, {}, { id ->
                        state.value = state.value.copy(referencePanel = state.value.referencePanel!!.copy(selectedReferenceId = id))
                    }, {
                        state.value = state.value.copy(referencePanel = state.value.referencePanel!!.copy(selectedReferenceId = null))
                    }, { state.value = state.value.copy(referencePanel = null) }, {})
                }
            }
            compose.onNodeWithTag("primaryReaderScroll").performTouchInput { swipeUp() }
            compose.onNodeWithTag("concept-${concept.id}").performScrollTo()
            compose.mainClock.advanceTimeBy(500)
            val before = scrollOffset()
            assertTrue("The real PDF should have scrolled", before > 0)
            compose.onNodeWithTag("concept-${concept.id}").performClick()
            compose.onNodeWithTag(panelTag).assertExists()
            compose.onNodeWithTag("open-${references.first().id}").performClick()
            compose.onNodeWithTag("referencePreview").assertExists()
            compose.onNodeWithTag("referenceBack").performClick()
            compose.onNodeWithTag("referenceList").assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag(panelTag).assertDoesNotExist()
            assertEquals(before, scrollOffset(), 0.1f)
        }
    }

    private fun scrollOffset() =
        compose
            .onNodeWithTag("primaryReaderScroll")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
}
