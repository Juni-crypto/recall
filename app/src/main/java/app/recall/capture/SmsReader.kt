package app.recall.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.recall.App
import app.recall.data.Captured
import app.recall.data.Kind
import app.recall.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Reads SMS straight from the phone's SMS store.
 *
 * Android 15+ hides notifications it thinks contain one-time codes from apps like Recall, and
 * that often catches bank alerts too. The SMS itself is still readable with SMS permission, so
 * when it's granted this becomes the source for all SMS (incoming and sent), and SMS-app
 * notifications are ignored to avoid double counting.
 */
object SmsReader {
    const val PKG = "android.sms"
    private const val APP = "SMS"
    private const val BACKFILL_DAYS = 30L
    private const val KEY_LAST = "sms_last_sync"

    fun granted(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private val prefs get() = App.ctx.getSharedPreferences("recall", Context.MODE_PRIVATE)

    /** Re-reads the whole SMS store (years back), not just the last 30 days. */
    fun importAll(c: Context) {
        prefs.edit { putLong(KEY_LAST, 0L) }
        sync(c, backfillDays = 3650)
    }

    @Synchronized
    fun sync(c: Context, backfillDays: Long = BACKFILL_DAYS) {
        if (!granted(c)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        val since = if (last == 0L) now - backfillDays * Repo.DAY else last - 60_000
        var newest = last
        val cols = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
        )
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI, cols,
                "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.TYPE} IN (?, ?)",
                arrayOf(since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                "${Telephony.Sms.DATE} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val address = cur.getString(0)?.trim().orEmpty()
                    val body = cur.getString(1)?.trim().orEmpty()
                    val date = cur.getLong(2)
                    val type = cur.getInt(4)
                    val thread = cur.getLong(5)
                    if (body.isEmpty()) continue
                    newest = maxOf(newest, date)
                    val self = type == Telephony.Sms.MESSAGE_TYPE_SENT
                    val c2 = Captured(
                        hash = sha1("sms|$address|$body|$date|$type"),
                        pkg = PKG, app = APP, convKey = "$PKG|$thread",
                        convTitle = address, sender = if (self) "You" else address, text = body,
                        isSelf = self, isGroup = false, kind = Kind.SMS, category = null,
                        at = date, sbnKey = null, ongoing = false,
                    )
                    // Old SMS feed money history and learning, but shouldn't reopen stale requests.
                    Ingestor.handle(c2, trackRequests = now - date < 3 * Repo.DAY)
                }
            }
        } catch (_: SecurityException) {
            return
        }
        if (newest > last) prefs.edit { putLong(KEY_LAST, newest) }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** New SMS arrived: give the SMS app a moment to store it, then sync. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        App.scope.launch {
            try {
                for (wait in listOf(1_500L, 4_000L)) {
                    delay(wait)
                    SmsReader.sync(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
