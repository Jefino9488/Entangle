package dev.entangle.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.entangle_android.AnnotationRecord
import uniffi.entangle_android.Engine

@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    @Test
    fun kotlinProcessesSourcesAndReadsPersistentEvidence() {
        NativeSourceFixture().use { fixture ->
            val engine = fixture.engine
            assertTrue(engine.status().fts5Enabled)
            assertEquals(2u, engine.status().schemaVersion)
            assertEquals(2u, engine.status().documentCount)
            val primary = engine.passages(fixture.primaryId, null)
            assertTrue(primary.isNotEmpty())
            assertTrue(primary.all { it.pageNumber == 1u && it.documentId == fixture.primaryId })
            assertFalse(engine.search("radial nerve", null, 20u).isEmpty())
            val concept = fixture.radialConcept()
            val references = engine.referencesFor(concept.id, fixture.primaryId, 20u)
            assertTrue(references.isNotEmpty())
            assertTrue(references.all { it.passage.documentId == fixture.secondaryId })
            assertTrue(engine.graph(concept.id).all { it.evidence.text.isNotBlank() && it.relationshipType == "CO_OCCURS_WITH" })
            val passage = primary.first()
            engine.saveAnnotation(
                AnnotationRecord(
                    "note",
                    fixture.primaryId,
                    passage.id,
                    0u,
                    "note",
                    "Radial nerve",
                    "Compare the clinical source.",
                    "#FFF2AB",
                    "2026-11-21T00:00:00Z",
                ),
            )
            engine.savePosition(fixture.primaryId, 0u, 123u)
            Engine.open(java.io.File(fixture.directory, "library.sqlite").absolutePath).use { reopened ->
                assertEquals(123u, reopened.documents().first { it.id == fixture.primaryId }.scrollOffset)
                assertEquals("Radial nerve", reopened.annotations(fixture.primaryId).single().quote)
            }
            engine.deleteAnnotation("note")
            assertTrue(engine.annotations(fixture.primaryId).isEmpty())
        }
    }
}
