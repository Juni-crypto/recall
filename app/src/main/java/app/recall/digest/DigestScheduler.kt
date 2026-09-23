package app.recall.digest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.recall.App
import app.recall.data.Repo
import app.recall.util.Time
import kotlinx.coroutines.launch
import java.time.LocalTime

object DigestScheduler {
    const val ACTION_DIGEST = "app.recall.DIGEST"
    const val ACTION_REMIND_LATER = "app.recall.REMIND_LATER"
    const val ACTION_REMIND = "app.recall.REMIND"

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun nextDigestAt(now: Long = Time.now()): Long {
        val t = LocalTime.of(App.prefs.digestHour, App.prefs.digestMinute)
        var at = Time.today().atTime(t).atZone(Time.zone).toInstant().toEpochMilli()
        if (at <= now + 30_000) at = Time.today().plusDays(1).atTime(t).atZone(Time.zone).toInstant().toEpochMilli()
        return at
    }

    fun scheduleNext(context: Context) = setAlarm(context, ACTION_DIGEST, nextDigestAt(), 1)

    /** "Remind me 8 AM": re-post tonight's digest tomorrow morning. */
    fun scheduleReminder(context: Context) {
        val t = LocalTime.of(8, 0)
        var at = Time.today().atTime(t).atZone(Time.zone).toInstant().toEpochMilli()
        if (at <= Time.now()) at = Time.today().plusDays(1).atTime(t).atZone(Time.zone).toInstant().toEpochMilli()
        setAlarm(context, ACTION_REMIND, at, 2)
    }

    private fun setAlarm(context: Context, action: String, at: Long, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, DigestReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Builds (or rebuilds) today's digest now. [notify] posts the notification too. */
    fun runNow(context: Context, notify: Boolean) {
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(DigestWorker.KEY_NOTIFY to notify))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("digest", ExistingWorkPolicy.REPLACE, request)
    }
}

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DigestScheduler.ACTION_DIGEST -> {
                DigestScheduler.runNow(context, notify = true)
                DigestScheduler.scheduleNext(context)
            }
            DigestScheduler.ACTION_REMIND_LATER -> {
                NotificationManagerCompat.from(context).cancel(Notifier.ID_DIGEST)
                DigestScheduler.scheduleReminder(context)
            }
            DigestScheduler.ACTION_REMIND -> {
                val pending = goAsync()
                App.scope.launch {
                    try {
                        Repo.latestDigest()?.let { Notifier.postDigest(context, it, reminder = true) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DigestScheduler.scheduleNext(context)
    }
}
