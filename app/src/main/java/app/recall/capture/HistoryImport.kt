package app.recall.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.recall.App
import app.recall.data.Captured
import app.recall.data.Kind
import app.recall.data.Repo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Imports a WhatsApp "Export chat" file (.txt, or .zip when media was included).
 * Android's own notification history is off-limits to apps, so exports are how old
 * conversations get in.
 */
object WhatsAppImport {
    const val PKG = "com.whatsapp"

    data class Result(val chat: String, val imported: Int, val skipped: Int)

    // "23/09/26, 3:10 pm - Arjun: hi"  or  "[23/09/26, 3:10:22 PM] Arjun: hi"
    private val LINE = Regex(
        """^‎?\[?(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4}),?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([ap]\.?\s?m\.?)?\]?\s*(?:-\s*)?([^:]{1,60}?):\s(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP = Regex("""^(<media omitted>|<attached:|this message was deleted|you deleted this message|null)""", RegexOption.IGNORE_CASE)

    private data class Raw(val a: Int, val b: Int, val y: Int, val h: Int, val m: Int, val s: Int, val ampm: String?, val who: String, val text: StringBuilder)

    fun import(context: Context, uri: Uri): Result {
        val name = displayName(context, uri)
        val chat = name.substringAfter("WhatsApp Chat with ", name).substringBeforeLast('.').substringBefore(".txt").trim().ifEmpty { "WhatsApp chat" }
        val text = readText(context, uri, name)
        return importText(chat, text)
    }

    data class Parsed(val sender: String, val text: String, val at: Long, val mine: Boolean, val isGroup: Boolean)

    /** Pure parser, separate from storage so it can be tested. */
    fun parse(chat: String, text: String, selfNames: List<String>, zone: ZoneId = ZoneId.systemDefault()): List<Parsed> {
        val raws = mutableListOf<Raw>()
        for (line in text.lineSequence()) {
            val m = LINE.find(line)
            if (m != null) {
                val g = m.groupValues
                raws += Raw(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0, g[7].ifEmpty { null }, g[8].trim(), StringBuilder(g[9]))
            } else if (raws.isNotEmpty() && line.isNotBlank()) {
                raws.last().text.append('\n').append(line)
            }
        }
        if (raws.isEmpty()) return emptyList()

        // Day/month order differs by locale; the data tells us which one it is.
        val monthFirst = raws.any { it.b > 12 } && raws.none { it.a > 12 }
        val senders = raws.map { it.who }.distinct()
        val names = selfNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val self: String? = when {
            senders.size == 2 && senders.any { it.equals(chat, ignoreCase = true) } -> senders.first { !it.equals(chat, ignoreCase = true) }
            else -> senders.firstOrNull { it.lowercase() in names }
        }
        val isGroup = senders.size > 2
        return raws.mapNotNull { r ->
            val body = r.text.toString().trim()
            if (body.isEmpty() || SKIP.containsMatchIn(body)) return@mapNotNull null
            val day = if (monthFirst) r.b else r.a
            val month = if (monthFirst) r.a else r.b
            val year = if (r.y < 100) 2000 + r.y else r.y
            var hour = r.h
            r.ampm?.lowercase()?.let { ap ->
                if (ap.startsWith("p") && hour < 12) hour += 12
                if (ap.startsWith("a") && hour == 12) hour = 0
            }
            val at = try {
                LocalDateTime.of(year, month, day, hour, r.m, r.s).atZone(zone).toInstant().toEpochMilli()
            } catch (e: Exception) {
                return@mapNotNull null
            }
            Parsed(r.who, body, at, self != null && r.who == self, isGroup)
        }
    }

    fun importText(chat: String, text: String): Result {
        val parsed = parse(chat, text, App.prefs.userNames.split(','))
        val convKey = "$PKG|import|${chat.lowercase()}"
        val recent = System.currentTimeMillis() - 3 * Repo.DAY
        var imported = 0
        for (p in parsed) {
            val c = Captured(
                hash = sha1("wa-import|$chat|${p.sender}|${p.text}|${p.at}"),
                pkg = PKG, app = "WhatsApp", convKey = convKey, convTitle = chat,
                sender = if (p.mine) "You" else p.sender, text = p.text, isSelf = p.mine, isGroup = p.isGroup,
                kind = Kind.CHAT, category = null, at = p.at, sbnKey = null, ongoing = false,
            )
            val id = Repo.insertMessage(c) ?: continue
            imported++
            // Only the last few days can still be waiting on a reply.
            if (p.at > recent) Ingestor.process(c, id)
        }
        return Result(chat, imported, parsed.size - imported)
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "chat.txt"
    }

    private fun readText(context: Context, uri: Uri, name: String): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        input.use { stream ->
            if (name.endsWith(".zip", true) || context.contentResolver.getType(uri)?.contains("zip") == true) {
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name.endsWith(".txt", true)) return BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText()
                    }
                }
                return ""
            }
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
    }
}

/**
 * Reads the phone's call history. Outgoing calls count as a reply: calling someone back
 * closes their missed call in Waiting.
 */
object CallLogReader {
    const val PKG = "android.calls"
    private const val KEY_LAST = "calls_last_sync"

    fun granted(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private val prefs get() = App.ctx.getSharedPreferences("recall", Context.MODE_PRIVATE)

    @Synchronized
    fun sync(c: Context, backfillDays: Long = 90) {
        if (!granted(c)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST, 0L)
        val since = if (last == 0L) now - backfillDays * Repo.DAY else last - 60_000
        var newest = last
        try {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val number = cur.getString(0)?.filter { it.isDigit() || it == '+' }.orEmpty()
                    val name = cur.getString(1)?.takeIf { it.isNotBlank() }
                    val type = cur.getInt(2)
                    val date = cur.getLong(3)
                    val secs = cur.getLong(4)
                    newest = maxOf(newest, date)
                    val who = name ?: number.ifEmpty { "Unknown" }
                    val mins = if (secs >= 60) "${secs / 60} min" else "${secs}s"
                    val (text, self) = when (type) {
                        CallLog.Calls.OUTGOING_TYPE -> "You called ($mins)" to true
                        CallLog.Calls.INCOMING_TYPE -> "Call ($mins)" to false
                        CallLog.Calls.MISSED_TYPE -> "Missed call" to false
                        CallLog.Calls.REJECTED_TYPE -> "Declined call" to false
                        else -> continue
                    }
                    val cap = Captured(
                        hash = sha1("call|$number|$type|$date"),
                        pkg = PKG, app = "Phone", convKey = "$PKG|${number.takeLast(10).ifEmpty { who }}",
                        convTitle = who, sender = if (self) "You" else who, text = text, isSelf = self, isGroup = false,
                        kind = if (type == CallLog.Calls.MISSED_TYPE) Kind.CALL else Kind.OTHER,
                        category = null, at = date, sbnKey = null, ongoing = false,
                    )
                    val id = Repo.insertMessage(cap) ?: continue
                    if (self) Repo.closeLoopsForPerson(who, "call_back")
                    if (date > now - 3 * Repo.DAY) Ingestor.process(cap, id)
                }
            }
        } catch (_: SecurityException) {
            return
        }
        if (newest > last) prefs.edit { putLong(KEY_LAST, newest) }
    }
}
