package app.recall.learn

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recall.R
import app.recall.data.Repo
import app.recall.digest.Notifier
import app.recall.model.Brain
import app.recall.model.Safety
import androidx.work.WorkManager as WM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Learn from my history now": every learning step, run to completion as a foreground job
 * so it keeps going when the user leaves the app or locks the phone.
 */
object LearnNow {
    data class Progress(val running: Boolean, val text: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    fun start(context: Context) {
        val req = OneTimeWorkRequestBuilder<LearnWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("learn-now", ExistingWorkPolicy.KEEP, req)
        if (_progress.value?.running != true) _progress.value = Progress(true, "Starting…")
    }

    internal suspend fun execute(report: suspend (String) -> Unit) {
        suspend fun step(text: String) {
            _progress.value = Progress(true, text)
            report(text)
        }
        var waitedMs = 0L
        try {
            step("Learning your habits…")
            Learner.runNightly(forceFacts = true)

            var reviewed = 0
            var labelled = 0
            var stoppedFor: String? = null
            if (Brain.available()) {
                step("Qwen is reading your recent messages…")
                reviewed = Triage.run(maxBatches = 30).reviewed

                val total = Repo.uncategorisedMerchants(100_000).size
                var looked = 0
                while (Repo.uncategorisedMerchants(1).isNotEmpty()) {
                    val v = Safety.check(Safety.Load.Light)
                    if (!v.ok) {
                        // Heat passes; wait for it. Battery limits don't: stop and say so.
                        if (Safety.isHot() && waitedMs < 15 * 60_000) {
                            step("Paused: the phone is warm. Continuing when it cools.")
                            kotlinx.coroutines.delay(60_000)
                            waitedMs += 60_000
                            continue
                        }
                        stoppedFor = v.reason
                        break
                    }
                    val before = Repo.uncategorisedMerchants(100_000).size
                    labelled += MerchantClassifier.run(batch = 30, rounds = 1) { n ->
                        looked += n
                        step("Labelling merchants: ${looked.coerceAtMost(total)} of $total")
                    }
                    if (Repo.uncategorisedMerchants(100_000).size >= before) {
                        // The model couldn't run (e.g. not enough free memory); don't spin.
                        stoppedFor = Brain.blocked.value ?: "The model couldn't run right now"
                        break
                    }
                }
                InsightWriter.refreshFacts()
            }
            _progress.value = Progress(
                false,
                buildList {
                    add(if (stoppedFor != null) "$stoppedFor. The rest continues tonight while charging" else "Done")
                    if (labelled > 0) add("labelled $labelled merchants")
                    if (reviewed > 0) add("reviewed $reviewed conversations")
                    add("${Repo.facts().size} things learned about you")
                }.joinToString(" · "),
            )
        } catch (e: Exception) {
            _progress.value = Progress(false, "Stopped: ${e.message ?: "something went wrong"}. Tap again to continue.")
            throw e
        } finally {
            Brain.unloadNow()
        }
    }
}

class LearnWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private fun info(text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setContentTitle("Recall is learning")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openApp(applicationContext, "you", 30))
            .addAction(0, "Stop", WM.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(Notifier.ID_WORK + 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Starting…")

    override suspend fun doWork(): Result {
        try { setForeground(info("Starting…")) } catch (_: Exception) {}
        return try {
            LearnNow.execute { text ->
                try { setForeground(info(text)) } catch (_: Exception) {}
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
