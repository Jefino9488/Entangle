package dev.entangle.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

class IngestionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(DOCUMENT_ID) ?: return Result.failure()
        val repository = LocalLibraryRepository(applicationContext)
        return try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val progress = repository.processDocument(id)
                setProgress(
                    workDataOf(
                        DOCUMENT_ID to id,
                        "state" to progress.state,
                        "processed" to progress.processed.toInt(),
                        "total" to progress.total.toInt(),
                    ),
                )
                when (progress.state) {
                    "ready", "paused" -> return Result.success()
                    "failed" -> return Result.failure(workDataOf("error" to (progress.error ?: "Document processing failed")))
                }
                yield()
            }
            @Suppress("UNREACHABLE_CODE")
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Document processing failed")))
        }
    }

    companion object {
        const val DOCUMENT_ID = "documentId"
        const val TAG = "entangle-ingestion"

        fun enqueue(
            context: Context,
            documentId: String,
            replace: Boolean = false,
        ) {
            val request =
                OneTimeWorkRequestBuilder<IngestionWorker>()
                    .setInputData(Data.Builder().putString(DOCUMENT_ID, documentId).build())
                    .addTag(TAG)
                    .build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork("ingest-$documentId", policy, request)
        }
    }
}
