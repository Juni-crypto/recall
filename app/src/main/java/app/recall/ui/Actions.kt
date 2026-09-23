package app.recall.ui

import android.Manifest
import android.app.ActivityOptions
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.recall.capture.RecallListenerService
import app.recall.data.OpenLoop
import app.recall.data.Repo
import app.recall.util.Time
import java.time.LocalTime

/** Permission checks and the system screens that fix them. */
object Health {
    fun notificationAccess(c: Context) = RecallListenerService.isEnabled(c)

    fun batteryUnrestricted(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun exactAlarms(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun postNotifications(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openNotificationAccess(c: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, RecallListenerService::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        launch(c, intent) || launch(c, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    @Suppress("BatteryLife")
    fun openBattery(c: Context) {
        launch(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))) ||
            openAppDetails(c)
    }

    fun openAppDetails(c: Context): Boolean =
        launch(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))

    fun openExactAlarms(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launch(c, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}")))
        }
    }

    private fun launch(c: Context, intent: Intent): Boolean = try {
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

object LoopActions {
    /** Opens the chat the request came from (or at least its app). */
    fun open(c: Context, loop: OpenLoop) {
        Repo.logSignal("item_open", loop.pkg, loop.convKey, refId = loop.id)
        val pi = RecallListenerService.contentIntents[loop.convKey]
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                    @Suppress("DEPRECATION")
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(c, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return
            } catch (_: Exception) {
            }
        }
        c.packageManager.getLaunchIntentForPackage(loop.pkg)?.let {
            try {
                c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    fun done(loop: OpenLoop) {
        Repo.setLoopState(loop.id, "closed", "done", label = 1)
        Repo.logSignal("done", loop.pkg, loop.convKey, refId = loop.id)
    }

    fun notForMe(loop: OpenLoop) {
        Repo.setLoopState(loop.id, "dismissed", "not_for_me", label = 0)
        Repo.logSignal("not_for_me", loop.pkg, loop.convKey, refId = loop.id)
    }

    /** Hidden until 9 AM tomorrow. */
    fun snooze(loop: OpenLoop) {
        val at = Time.today().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(Time.zone).toInstant().toEpochMilli()
        Repo.snoozeLoop(loop.id, at)
        Repo.logSignal("snooze", loop.pkg, loop.convKey, refId = loop.id)
    }
}
