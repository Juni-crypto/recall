package app.recall.model

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.recall.App
import app.recall.R
import app.recall.data.Repo
import app.recall.digest.Notifier
import app.recall.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only code in Recall that uses the network. Downloads one model file from Hugging Face,
 * resuming if interrupted, then checks its SHA-256 before it's ever loaded.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val file = inputData.getString(KEY_FILE)!!
    private val notificationId = 2000 + (file.hashCode() and 0xfff)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, inputData.getLong(KEY_BYTES, -1))

    private fun foregroundInfo(done: Long, total: Long): ForegroundInfo {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setContentTitle("Downloading Recall's model")
            .setContentText(if (total > 0) "${Fmt.bytes(done)} of ${Fmt.bytes(total)}" else Fmt.bytes(done))
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)!!
        val sha = inputData.getString(KEY_SHA)
        val expected = inputData.getLong(KEY_BYTES, -1)
        val dir = ModelStore.dir
        val dest = File(dir, file)
        val part = File(dir, "$file.part")

        if (dest.exists() && (expected <= 0 || dest.length() == expected)) return@withContext Result.success()
        if (expected > 0 && expected - part.length() + 300_000_000 > ModelStore.freeBytes()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Not enough free space"))
        }

        try {
            setForeground(foregroundInfo(part.length(), expected))
        } catch (_: Exception) {
        }

        var attempts = 0
        while (true) {
            try {
                val total = fetch(url, part, expected)
                if (total <= 0 || part.length() >= total) break
            } catch (e: Exception) {
                if (isStopped) return@withContext Result.failure()
                attempts++
                if (attempts >= 5) {
                    return@withContext Result.retry()
                }
                kotlinx.coroutines.delay(2000L * attempts)
            }
            if (isStopped) return@withContext Result.failure()
        }

        if (sha != null) {
            val actual = ModelStore.sha256(part)
            if (!actual.equals(sha, ignoreCase = true)) {
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "The file didn't match its checksum, so it was deleted. Try again."))
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Couldn't save the model"))
        if (App.prefs.activeModel == null || ModelStore.active()?.file?.name != App.prefs.activeModel) {
            App.prefs.activeModel = dest.name
        }
        Result.success()
    }

    /** Downloads (or resumes) into [part]. Returns the full file size. */
    private suspend fun fetch(url: String, part: File, expected: Long): Long {
        val start = part.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Recall-Android/1.0")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        val origin = URL(url)
        var received = 0L
        var code = -1
        try {
            code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code == 416) return start
            if (code != HttpURLConnection.HTTP_OK && !append) throw IllegalStateException("HTTP $code")
            val length = conn.contentLengthLong
            val total = when {
                append -> start + length
                length > 0 -> length
                else -> expected
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = if (append) start else 0L
                    var lastReport = 0L
                    while (true) {
                        if (isStopped) break
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 700) {
                            lastReport = now
                            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                            try { setForeground(foregroundInfo(done, total)) } catch (_: Exception) {}
                        }
                    }
                }
            }
            return total
        } finally {
            val finalHost = conn.url.host
            Repo.logNet(origin.host, origin.path, if (finalHost == origin.host) received else 0, code)
            if (finalHost != origin.host) Repo.logNet(finalHost, conn.url.path.take(80), received, code)
            conn.disconnect()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_SHA = "sha"
        const val KEY_BYTES = "bytes"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }
}
