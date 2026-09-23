package app.recall.digest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.recall.R
import app.recall.data.Digest
import app.recall.ui.MainActivity
import app.recall.util.Fmt

object Notifier {
    const val CH_DIGEST = "digest"
    const val CH_WEEKLY = "weekly"
    const val CH_WORK = "work"
    const val CH_DOWNLOAD = "download"

    const val ID_DIGEST = 1001
    const val ID_WEEKLY = 1002
    const val ID_WORK = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_DIGEST, "Daily digest", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tonight's summary: who's waiting on you, what was urgent, what you spent"
                },
                NotificationChannel(CH_WEEKLY, "Your week", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_DOWNLOAD, "Model download", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_WORK, "Background work", NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(context: Context, tab: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun digestTitle(d: Digest): String {
        val parts = buildList {
            if (d.urgent > 0) add("${d.urgent} urgent")
            if (d.waiting > 0) add("${d.waiting} waiting on you")
            if (d.spentPaise > 0) add("${Fmt.rupees(d.spentPaise)} spent")
        }
        return if (parts.isEmpty()) "A quiet day" else parts.joinToString(" · ")
    }

    fun postDigest(context: Context, d: Digest, reminder: Boolean = false) {
        if (!canPost(context)) return
        val body = buildString {
            append(d.summary)
            if (d.lines.isNotEmpty()) {
                append("\n\n")
                append(d.lines.joinToString("\n"))
            }
        }
        val later = PendingIntent.getBroadcast(
            context, 10, Intent(context, DigestReceiver::class.java).setAction(DigestScheduler.ACTION_REMIND_LATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setColor(0xFF8FA2FF.toInt())
            .setContentTitle(digestTitle(d))
            .setContentText(d.summary)
            .setSubText(if (reminder) "From last night" else "Daily digest")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, "today", 1))
            .addAction(0, "Open digest", openApp(context, "today", 2))
            .apply { if (!reminder) addAction(0, "Remind me 8 AM", later) }
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(context).notify(ID_DIGEST, n)
    }

    fun postWeekly(context: Context, lines: List<String>) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_WEEKLY)
            .setSmallIcon(R.drawable.ic_stat_recall)
            .setColor(0xFF8FA2FF.toInt())
            .setContentTitle("Your week")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp(context, "you", 3))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_WEEKLY, n)
    }
}
