package app.recall.digest

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.recall.R
import app.recall.capture.SmsReader
import app.recall.data.Repo
import app.recall.learn.InsightWriter
import app.recall.learn.Learner
import app.recall.learn.MerchantClassifier
import app.recall.model.Brain
import app.recall.util.Time
import java.time.DayOfWeek

/** Nightly job: learn from today, build the digest, post it, tidy up. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setContentTitle("Recall is going over your day")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return ForegroundInfo(Notifier.ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val notify = inputData.getBoolean(KEY_NOTIFY, true)
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // Expedited jobs may run without a foreground service; that's fine.
        }
        val now = Time.now()
        return try {
            SmsReader.sync(applicationContext)
            app.recall.capture.CallLogReader.sync(applicationContext)
            Learner.runNightly(now)
            // Big catch-up work only while charging; on battery just a quick review.
            val charging = app.recall.model.Safety.power().charging
            app.recall.learn.Triage.run(maxBatches = if (charging) 6 else 2)
            if (charging) MerchantClassifier.run()
            val digest = DigestBuilder.build(Time.today(), useModel = true)
            if (notify) {
                Notifier.postDigest(applicationContext, digest)
                if (Time.today().dayOfWeek == DayOfWeek.SUNDAY) {
                    val lines = InsightWriter.weekly(now)
                    if (lines.isNotEmpty()) Notifier.postWeekly(applicationContext, lines)
                }
            }
            Repo.retention(now)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            Brain.unloadNow()
        }
    }

    companion object {
        const val KEY_NOTIFY = "notify"
    }
}
