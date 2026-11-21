package dev.entangle.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.entangle.app.data.IngestionWorker
import org.junit.Rule
import org.junit.Test
import uniffi.entangle_android.Engine
import java.io.File
import java.util.UUID

class KnowledgeAssistantTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun importedSourcesDriveGraphAndAssistantAndOpenEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val run = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "knowledge-$run").apply { mkdirs() }
        val ids = listOf("origin-$run", "clinical-$run")
        val titles = listOf("Nerve anatomy $run", "Clinical findings $run")
        val texts =
            listOf(
                "Radial nerve arises from the posterior cord. Radial nerve supplies the triceps brachii. Radial nerve anatomy describes the posterior cord.",
                "Radial nerve injury causes wrist drop. Radial nerve supplies the triceps brachii. Radial nerve injury has clinical findings of wrist drop.",
            )
        val database = File(context.noBackupFilesDir, "knowledge/library.sqlite").apply { parentFile!!.mkdirs() }
        Engine.open(database.absolutePath).use { engine ->
            try {
                ids.forEachIndexed { index, id ->
                    val file = File(directory, "$id.txt").apply { writeText(texts[index]) }
                    engine.registerDocument(id, titles[index], file.absolutePath, "text/plain")
                    check(engine.processDocument(id, 32u).state == "ready")
                    IngestionWorker.enqueue(context, id)
                }
                compose.waitUntil(20_000) { compose.onAllNodesWithText(titles.first()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("nav-Assistant").performClick()
                compose.onNodeWithTag("assistantQuestion").performTextInput("Compare radial nerve across these sources")
                compose.onNodeWithTag("askQuestion").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("assistantAnswer").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("assistantAnswer").assertTextContains(titles[0], substring = true)
                compose.onNodeWithTag("assistantAnswer").assertTextContains(titles[1], substring = true)
                compose.onNodeWithTag("nav-Knowledge").performClick()
                compose.onNodeWithTag("conceptSearch").performTextInput("radial nerve")
                val concept = engine.concepts("radial nerve", 100u).first { it.label.equals("radial nerve", ignoreCase = true) }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("concept-${concept.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("concept-${concept.id}").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("CO_OCCURS_WITH", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodesWithText("Read supporting passage").onFirst().performClick()
                compose.waitUntil(
                    15_000,
                ) { compose.onAllNodesWithText("Radial nerve", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("readerSearch").assertExists()
            } finally {
                ids.forEach { engine.removeDocument(it) }
                directory.deleteRecursively()
            }
        }
    }
}
