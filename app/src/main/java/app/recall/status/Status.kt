package app.recall.status

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import app.recall.App
import app.recall.R
import app.recall.capture.RecallListenerService
import app.recall.data.Repo
import app.recall.digest.Notifier
import app.recall.ui.LoopActions
import app.recall.util.Fmt
import app.recall.util.Time
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What needs the user right now, in the shape the widget and the lock-screen card show. */
data class StatusSnapshot(
    val mood: String,
    val headline: String,
    val items: List<Item>,
    val spentToday: Long,
    val digestAt: String,
) {
    data class Item(val id: Long, val person: String, val app: String, val text: String, val urgent: Boolean)
}

object Status {
    const val CHANNEL = "status"
    const val ID = 1010

    fun snapshot(): StatusSnapshot {
        val loops = Repo.openLoops()
        val urgent = loops.count { it.isUrgent }
        val capturing = RecallListenerService.isEnabled(App.ctx) || App.prefs.demo
        val headline = when {
            !capturing -> "Recall isn't capturing"
            urgent > 0 && loops.size > urgent -> "$urgent urgent · ${loops.size - urgent} waiting on you"
            urgent > 0 -> if (urgent == 1) "1 urgent thing needs you" else "$urgent urgent things need you"
            loops.isNotEmpty() -> if (loops.size == 1) "1 person is waiting on you" else "${loops.size} people are waiting on you"
            else -> "Nothing needs you right now"
        }
        val mood = when {
            !capturing -> "off"
            urgent > 0 -> "urgent"
            loops.isNotEmpty() -> "needs"
            else -> "calm"
        }
        val (start, end) = Time.bounds(Time.today())
        return StatusSnapshot(
            mood = mood,
            headline = headline,
            items = loops.take(4).map {
                StatusSnapshot.Item(
                    it.id, it.person, it.app,
                    it.askText.replace('\n', ' ').let { t -> if (t.length > 80) t.take(78) + "…" else t },
                    it.isUrgent,
                )
            },
            spentToday = Repo.moneyTotals(start, end).first,
            digestAt = LocalTime.of(App.prefs.digestHour, App.prefs.digestMinute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
        )
    }

    /** Keeps the widget and the lock-screen card in step with the database. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        createChannel(context)
        App.scope.launch {
            Repo.watch { snapshot() }
                .debounce(1500)
                .distinctUntilChanged()
                .collect { s ->
                    postCard(context, s)
                    try {
                        RecallWidget().updateAll(context)
                    } catch (_: Exception) {
                    }
                }
        }
    }

    fun refresh(context: Context) {
        App.scope.launch {
            val s = snapshot()
            postCard(context, s)
            try { RecallWidget().updateAll(context) } catch (_: Exception) {}
        }
    }

    private fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "On your lock screen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A quiet card showing who's waiting on you"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun postCard(context: Context, s: StatusSnapshot) {
        val nm = NotificationManagerCompat.from(context)
        if (!App.prefs.lockCard) {
            nm.cancel(ID)
            return
        }
        if (!nm.areNotificationsEnabled()) return
        val top = s.items.firstOrNull()
        val body = buildString {
            s.items.take(3).forEach { appendLine("${if (it.urgent) "●" else "○"} ${it.person}: ${it.text}") }
            if (s.spentToday > 0) appendLine("₹ Spent today ${Fmt.rupees(s.spentToday)}")
            if (s.items.isEmpty()) append("Digest at ${s.digestAt}")
        }.trim()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setColor(0xFF8FA2FF.toInt())
            .setContentTitle(s.headline)
            .setContentText(top?.let { "${it.person}: ${it.text}" } ?: "Digest at ${s.digestAt}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(Notifier.openApp(context, "waiting", 20))
            // Android 16 Live Updates: ask to be shown as a promoted ongoing card.
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        if (top != null) {
            builder.setShortCriticalText(if (s.items.size > 1) "${s.items.size} waiting" else top.person.take(12))
            val done = PendingIntent.getBroadcast(
                context, top.id.toInt(),
                Intent(context, StatusReceiver::class.java).setAction(StatusReceiver.ACTION_DONE).putExtra(StatusReceiver.EXTRA_ID, top.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Done: ${top.person.take(18)}", done)
        }
        try {
            nm.notify(ID, builder.build())
        } catch (_: SecurityException) {
        }
    }
}

/** "Done" tapped on the lock-screen card. */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val pending = goAsync()
        App.scope.launch {
            try {
                Repo.loop(id)?.let { LoopActions.done(it) }
                Status.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "app.recall.status.DONE"
        const val EXTRA_ID = "loop_id"
    }
}
