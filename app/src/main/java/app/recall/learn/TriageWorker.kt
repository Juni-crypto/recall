package app.recall.learn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recall.model.Brain
import java.util.concurrent.TimeUnit

/** Runs the model's review of new messages in the background. */
class TriageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Brain.available()) return Result.success()
        // Skip this round if the phone is warm, low on battery or over today's on-battery AI time.
        if (!app.recall.model.Safety.check(app.recall.model.Safety.Load.Light).ok) return Result.success()
        return try {
            Triage.run()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

        /** Every 15 minutes (Android's minimum), whenever the battery isn't low. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TriageWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("triage", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Right now, e.g. when the app opens. */
        fun runSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<TriageWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("triage-now", ExistingWorkPolicy.KEEP, req)
        }
    }
}
