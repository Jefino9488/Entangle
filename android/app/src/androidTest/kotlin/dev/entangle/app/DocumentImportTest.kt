package dev.entangle.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import uniffi.entangle_android.Engine
import java.io.File
import java.util.UUID

class DocumentImportTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun documentPickerCopiesSourceAndPreviewWorksAfterSourceDeletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val filename = "Entangle smoke ${UUID.randomUUID()}.txt"
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            resolver.openOutputStream(uri)!!.use { it.write("Evidence stays on this device.".toByteArray()) }
            compose.waitUntil(15_000) {
                !compose
                    .onNodeWithTag("importDocument")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("importDocument").performClick()
            val device = UiDevice.getInstance(instrumentation)
            var file = device.wait(Until.findObject(By.text(filename)), 5_000)
            if (file == null) {
                device.findObject(UiSelector().description("Show roots")).click()
                device.wait(Until.findObject(By.text("Downloads")), 5_000)?.click()
                file = device.wait(Until.findObject(By.text(filename)), 10_000)
            }
            assertNotNull("The system document picker should expose the test source", file)
            file!!.click()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(filename).fetchSemanticsNodes().isNotEmpty() }
            resolver.delete(uri, null, null)
            compose.onNodeWithText(filename).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Evidence stays on this device.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Evidence stays on this device.").assertExists()
        } finally {
            resolver.delete(uri, null, null)
            val context = instrumentation.targetContext
            Engine.open(File(context.noBackupFilesDir, "knowledge/library.sqlite").absolutePath).use { engine ->
                engine.documents().filter { it.title == filename }.forEach { document ->
                    engine.removeDocument(document.id)
                    File(document.localPath).delete()
                }
            }
        }
    }
}
