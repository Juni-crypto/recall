package app.recall.capture

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
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
        // Shortcut ids name a chat. Other apps (Gmail) can use one id for a whole account,
        // which would merge every email into one conversation, so there the title (sender) wins.
        val chat = n.category == Notification.CATEGORY_MESSAGE || extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!chat && (n.category == Notification.CATEGORY_EMAIL || AppKinds.isEmail(sbn.packageName))) {
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val subject = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
            if (sender != null && !subject.isNullOrBlank()) return MailThread.key(sbn.packageName, sender, subject)
        }
        val id = (if (chat) n.shortcutId ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() else null)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: sbn.tag
            ?: sbn.id.toString()
        return "${sbn.packageName}|$id"
    }

    /**
     * [hasChildren]: for a group summary, whether the group's own notifications are showing.
     * If they aren't (Gmail stops posting one per email after a few), the summary's lines are
     * the only copy of those messages.
     */
    fun normalize(context: Context, sbn: StatusBarNotification, hasChildren: Boolean = true): List<Captured> {
        val pkg = sbn.packageName
        if (pkg == context.packageName) return emptyList()
        val n = sbn.notification ?: return emptyList()
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return summaryLines(context, sbn, hasChildren)

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

    /** One message per line of a bundled summary ("Sender  Subject"). */
    private fun summaryLines(context: Context, sbn: StatusBarNotification, hasChildren: Boolean): List<Captured> {
        val pkg = sbn.packageName
        val n = sbn.notification
        val extras = n.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.filter { !it.isNullOrBlank() }.orEmpty()
        if (lines.isEmpty()) return emptyList()
        // Apps repeat their messages in the summary in another format; only read the lines
        // when nothing else carries them.
        if (hasChildren) return emptyList()
        val kind = kindOf(pkg, n.category, false)
        if (AppKinds.isSms(pkg) && SmsReader.granted(context)) return emptyList()
        val app = appLabel(context, pkg)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val bucket = sbn.postTime / TWELVE_HOURS
        return lines.mapNotNull { line ->
            val (sender, text) = splitLine(line)
            if (text.isEmpty() || REDACTED.containsMatchIn(text)) return@mapNotNull null
            val who = sender ?: title
            Captured(
                hash = sha1("$pkg|line|$who|$text|$bucket"), pkg = pkg, app = app,
                convKey = if (kind == Kind.EMAIL) MailThread.key(pkg, who, text) else "$pkg|${who ?: sbn.key}",
                convTitle = who, sender = who, text = text, isSelf = false, isGroup = false,
                kind = kind, category = n.category, at = sbn.postTime, sbnKey = sbn.key, ongoing = false,
            )
        }
    }

    /** Gmail puts the sender in bold at the start of each line; otherwise fall back to separators. */
    private fun splitLine(line: CharSequence): Pair<String?, String> {
        if (line is Spanned) {
            val bold = line.getSpans(0, line.length, StyleSpan::class.java)
                .firstOrNull { it.style and Typeface.BOLD != 0 && line.getSpanStart(it) == 0 }
            if (bold != null) {
                val end = line.getSpanEnd(bold)
                val sender = line.subSequence(0, end).toString().trim()
                val text = line.subSequence(end, line.length).toString().trim().trimStart(':', '-', '·', '|').trim()
                if (sender.isNotEmpty() && text.isNotEmpty()) return sender to text
            }
        }
        return SummaryLine.split(line.toString())
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
