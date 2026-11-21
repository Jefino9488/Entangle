package dev.entangle.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import dev.entangle.app.data.loadDocumentPreview
import dev.entangle.app.domain.ContextualReference
import dev.entangle.app.domain.LibraryDocument
import dev.entangle.app.domain.SourceLocation
import dev.entangle.app.domain.SourcePassage
import kotlinx.coroutines.runBlocking
import uniffi.entangle_android.Engine
import uniffi.entangle_android.PassageRecord
import java.io.File
import java.util.UUID

/** Real generated source documents used only by instrumented tests. */
internal class NativeSourceFixture : AutoCloseable {
    val directory =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "source-test-${UUID.randomUUID()}").apply {
            mkdirs()
        }
    val engine = Engine.open(File(directory, "library.sqlite").absolutePath)
    val primaryId = "anatomy"
    val secondaryId = "clinical"

    init {
        val pdf = File(directory, "anatomy.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(1000, 2400, 1).create())
            val paint = Paint().apply { textSize = 20f }
            repeat(65) { line ->
                page.canvas.drawText("Radial nerve arises from posterior cord and supplies triceps brachii.", 40f, 60f + line * 30f, paint)
            }
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val clinical =
            File(directory, "clinical.txt").apply {
                writeText(
                    "Radial nerve injury causes wrist drop. Radial nerve supplies the triceps brachii. Posterior cord gives rise to the radial nerve.",
                )
            }
        engine.registerDocument(primaryId, "Anatomy source", pdf.absolutePath, "application/pdf")
        engine.registerDocument(secondaryId, "Clinical source", clinical.absolutePath, "text/plain")
        listOf(primaryId, secondaryId).forEach { id ->
            var progress = engine.processDocument(id, 32u)
            repeat(100) { if (progress.state != "ready" && progress.state != "failed") progress = engine.processDocument(id, 32u) }
            check(progress.state == "ready") { progress.error ?: progress.state }
        }
    }

    fun document(id: String): LibraryDocument =
        engine.documents().first { it.id == id }.let {
            LibraryDocument(
                it.id,
                it.title,
                it.localPath,
                it.mediaType,
                it.pageIndex.toInt(),
                it.scrollOffset.toInt(),
                it.ingestionState,
                it.indexedPassages.toInt(),
                it.error,
            )
        }

    fun passages(id: String): List<SourcePassage> = engine.passages(id, null).map { it.domain() }

    fun preview() = runBlocking { loadDocumentPreview(document(primaryId), 0) }

    fun radialConcept() = engine.concepts("radial nerve", 100u).first { it.label.equals("radial nerve", ignoreCase = true) }

    fun references(): List<ContextualReference> =
        engine.referencesFor(radialConcept().id, primaryId, 40u).map {
            val p = it.passage
            ContextualReference(
                p.id,
                p.documentTitle,
                SourceLocation(
                    p.documentId,
                    p.editionId,
                    p.section,
                    p.pageNumber?.toInt(),
                    p.startOffset?.toInt(),
                    p.endOffset?.toInt(),
                    p.extractionMethod,
                    p.processingVersion,
                ),
                p.text,
                it.explanation,
                it.relationshipType,
                it.verificationStatus,
            )
        }

    override fun close() {
        engine.close()
        directory.deleteRecursively()
    }
}

internal fun PassageRecord.domain() =
    SourcePassage(
        id,
        documentId,
        documentTitle,
        editionId,
        section,
        pageNumber?.toInt(),
        ordinal.toInt(),
        text,
        startOffset?.toInt(),
        endOffset?.toInt(),
        extractionMethod,
        processingVersion,
    )
