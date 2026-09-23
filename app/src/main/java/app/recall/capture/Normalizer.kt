package app.recall.capture

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.recall.data.Captured
import app.recall.data.Kind
import app.recall.understand.AppKinds
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Turns an Android notification into one or more [Captured] messages. */
object Normalizer {
    private val labels = ConcurrentHashMap<String, String>()
    private const val TWELVE_HOURS = 12 * 60 * 60 * 1000L

    fun appLabel(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
        }
    }

    fun convKey(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val extras = n.extras
        val id = n.shortcutId
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: sbn.tag
            ?: sbn.id.toString()
        return "${sbn.packageName}|$id"
    }

    fun normalize(context: Context, sbn: StatusBarNotification): List<Captured> {
        val pkg = sbn.packageName
        if (pkg == context.packageName) return emptyList()
        val n = sbn.notification ?: return emptyList()
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()

        val extras = n.extras
        // Progress bars (downloads, uploads) update constantly and carry nothing to remember.
        if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            return emptyList()
        }

        // With SMS permission, SmsReader reads SMS directly (Android can't hide those), so the
        // SMS app's own notifications would only be duplicates.
        if (AppKinds.isSms(pkg) && SmsReader.granted(context)) return emptyList()
        // Same for calls: with call-log access, the call log is the source of truth.
        if (AppKinds.isDialer(pkg) && CallLogReader.granted(context)) return emptyList()

        val app = appLabel(context, pkg)
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        val category = n.category
        val convKey = convKey(sbn)

        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        }

        if (style != null && style.messages.isNotEmpty()) {
            val selfName = style.user.name?.toString()
            val convTitle = style.conversationTitle?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val isGroup = style.isGroupConversation
            val kind = kindOf(pkg, category, true)
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val person = m.person
                val isSelf = person == null ||
                    (person.key != null && person.key == style.user.key) ||
                    (selfName != null && person.name?.toString() == selfName && person.key == null)
                val sender = if (isSelf) "You" else person?.name?.toString()
                val at = if (m.timestamp > 0) m.timestamp else sbn.postTime
                Captured(
                    hash = sha1("$pkg|$convKey|$sender|$text|$at"),
                    pkg = pkg, app = app, convKey = convKey,
                    convTitle = convTitle ?: sender,
                    sender = sender, text = text, isSelf = isSelf, isGroup = isGroup,
                    kind = kind, category = category, at = at, sbnKey = sbn.key, ongoing = ongoing,
                )
            }
        }

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        val text = listOf(big, body, lines).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        if (text.isEmpty() && title.isNullOrEmpty()) return emptyList()

        // Android 15+ hides OTP-like notifications from third-party listeners.
        if (REDACTED.containsMatchIn(text)) return emptyList()
        val kind = kindOf(pkg, category, false, title, text)
        val bucket = sbn.postTime / TWELVE_HOURS
        return listOf(
            Captured(
                hash = sha1("$pkg|${sbn.key}|$title|$text|$bucket"),
                pkg = pkg, app = app, convKey = convKey,
                convTitle = title, sender = title, text = text.ifEmpty { title.orEmpty() },
                isSelf = false, isGroup = false, kind = kind, category = category, at = sbn.postTime,
                sbnKey = sbn.key, ongoing = ongoing,
            ),
        )
    }

    private val REDACTED = Regex("^Sensitive notification content hidden$", RegexOption.IGNORE_CASE)

    private val MISSED_CALL = Regex("""missed (voice |video )?call""", RegexOption.IGNORE_CASE)

    private fun kindOf(pkg: String, category: String?, messaging: Boolean, title: String? = null, text: String? = null): String = when {
        category == Notification.CATEGORY_MISSED_CALL -> Kind.CALL
        AppKinds.isDialer(pkg) && (MISSED_CALL.containsMatchIn(title.orEmpty()) || MISSED_CALL.containsMatchIn(text.orEmpty())) -> Kind.CALL
        category == Notification.CATEGORY_EMAIL || AppKinds.isEmail(pkg) -> Kind.EMAIL
        AppKinds.isSms(pkg) -> Kind.SMS
        messaging || category == Notification.CATEGORY_MESSAGE || AppKinds.isChat(pkg) -> Kind.CHAT
        else -> Kind.OTHER
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
