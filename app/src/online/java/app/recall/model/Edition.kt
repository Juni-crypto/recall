package app.recall.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.recall.App
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Standard edition: models can be downloaded from Hugging Face inside the app. */
object Edition {
    const val CAN_DOWNLOAD = true

    private fun workName(id: String) = "model-$id"

    fun download(context: Context, spec: ModelSpec) = enqueue(context, spec.id, spec.url, spec.file, spec.sha256, spec.bytes)

    /** Any direct GGUF link from Hugging Face (advanced). */
    fun downloadCustom(context: Context, url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("https://huggingface.co/") || !clean.substringBefore('?').endsWith(".gguf")) return false
        val file = clean.substringBefore('?').substringAfterLast('/')
        val resolved = clean.replace("/blob/", "/resolve/")
        enqueue(context, "custom-${file.hashCode()}", resolved, file, null, -1)
        return true
    }

    private fun enqueue(context: Context, id: String, url: String, file: String, sha: String?, bytes: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (App.prefs.allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url, DownloadWorker.KEY_FILE to file,
                    DownloadWorker.KEY_SHA to sha, DownloadWorker.KEY_BYTES to bytes,
                ),
            )
            .addTag("model-download")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, spec: ModelSpec) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(spec.id))
    }

    fun state(context: Context, spec: ModelSpec): Flow<DownloadState?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(spec.id)).map { infos ->
            val info = infos.lastOrNull() ?: return@map null
            val p = info.progress
            val total = p.getLong(DownloadWorker.KEY_TOTAL, spec.bytes)
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    DownloadState(running = true, done = 0, total = spec.bytes, error = "Waiting for Wi-Fi")
                WorkInfo.State.RUNNING -> DownloadState(true, p.getLong(DownloadWorker.KEY_DONE, 0), total)
                WorkInfo.State.SUCCEEDED -> DownloadState(false, spec.bytes, spec.bytes, finished = true)
                WorkInfo.State.FAILED ->
                    DownloadState(false, 0, spec.bytes, error = info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed")
                WorkInfo.State.CANCELLED -> null
            }
        }
}
