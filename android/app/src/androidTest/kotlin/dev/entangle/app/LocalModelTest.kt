package dev.entangle.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.entangle_android.generateLocalAnswer
import uniffi.entangle_android.validateLocalModel
import java.io.File

/** Runs only when a genuine GGUF has been provisioned; never creates a synthetic model. */
class LocalModelTest {
    @Test
    fun actualModelAnswersFromImportedEvidenceAndCitesItsSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.noBackupFilesDir, "models/current.gguf")
        assumeTrue("Import a genuine instruction GGUF before running this device model test", model.isFile)
        assertTrue(validateLocalModel(model.absolutePath).sizeBytes > 0uL)
        NativeSourceFixture().use { fixture ->
            val origin = fixture.passages(fixture.primaryId).first()
            val clinical = fixture.passages(fixture.secondaryId).first()
            val evidence =
                "[1] ${origin.documentTitle} · page ${origin.pageNumber}\n${origin.text.replace('\n', ' ')}\n\n" +
                    "[2] ${clinical.documentTitle}\n${clinical.text.replace('\n', ' ')}"
            val started = android.os.SystemClock.elapsedRealtime()
            val answer = generateLocalAnswer(model.absolutePath, "Where does the radial nerve arise?", evidence, 128u)
            println("Actual device generation: ${android.os.SystemClock.elapsedRealtime() - started} ms; answer: $answer")
            assertTrue(answer.lowercase().contains("posterior cord"))
            assertTrue(answer.contains("[1]"))
        }
    }
}
