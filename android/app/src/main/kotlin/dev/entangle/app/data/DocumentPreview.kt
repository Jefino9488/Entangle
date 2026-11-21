package dev.entangle.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.entangle.app.domain.LibraryDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

data class DocumentPreview(
    val bitmap: Bitmap? = null,
    val text: String? = null,
    val pageCount: Int = 1,
    val message: String? = null,
)

/** One bounded PDF bitmap or a temporary text preview while native extraction runs. */
suspend fun loadDocumentPreview(
    document: LibraryDocument,
    pageIndex: Int,
): DocumentPreview =
    withContext(Dispatchers.IO) {
        when (document.mediaType) {
            "application/pdf" -> {
                ParcelFileDescriptor.open(File(document.localPath), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount > 0) { "This PDF has no pages." }
                        renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                            val scale = min(1200.0 / page.width, sqrt(2_000_000.0 / (page.width.toDouble() * page.height)))
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            DocumentPreview(bitmap = bitmap, pageCount = renderer.pageCount)
                        }
                    }
                }
            }
            "text/plain", "text/markdown" -> {
                File(document.localPath).bufferedReader().use { reader ->
                    val buffer = CharArray(64 * 1024)
                    var size = 0
                    while (size < buffer.size) {
                        val read = reader.read(buffer, size, buffer.size - size)
                        if (read < 0) break
                        size += read
                    }
                    val truncated = size == buffer.size && reader.read() >= 0
                    DocumentPreview(
                        text = String(buffer, 0, size),
                        message = if (truncated) "Preview limited to the first 64 KiB of characters." else null,
                    )
                }
            }
            else -> error("Use the extracted passage reader for this document format.")
        }
    }
