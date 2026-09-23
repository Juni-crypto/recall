package app.recall.util

import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Fmt {
    private val inr = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"))

    fun rupees(paise: Long): String {
        val whole = paise / 100
        val frac = paise % 100
        return if (frac == 0L || whole >= 1000) "₹" + inr.format(Math.round(paise / 100.0)) else "₹" + inr.format(whole) + "." + "%02d".format(frac)
    }

    fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "a minute"
            min < 60 -> "$min min"
            min < 60 * 24 -> {
                val h = min / 60.0
                if (h < 10) "%.1f h".format(h).replace(".0 h", " h") else "${Math.round(h)} h"
            }
            else -> {
                val d = Math.round(min / 1440.0)
                if (d == 1L) "1 day" else "$d days"
            }
        }
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f KB".format(b / 1e3)
        else -> "$b B"
    }
}

object Time {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val longDateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

    fun now() = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now(zone)
    fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun hour(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone).hour
    fun startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun bounds(d: LocalDate): Pair<Long, Long> = startOf(d) to startOf(d.plusDays(1))
    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun key(d: LocalDate): String = d.toString()

    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)

    /** "3:10 PM", "Yesterday 7:12 PM", "Mon 7:12 PM", "Sun 14 Sep" */
    fun relative(ms: Long): String {
        val d = date(ms)
        val t = today()
        return when {
            d == t -> clock(ms)
            d == t.minusDays(1) -> "Yesterday ${clock(ms)}"
            d.isAfter(t.minusDays(7)) -> "${Instant.ofEpochMilli(ms).atZone(zone).format(dayFmt)} ${clock(ms)}"
            else -> Instant.ofEpochMilli(ms).atZone(zone).format(dateFmt)
        }
    }

    fun shortDate(d: LocalDate): String = d.format(dateFmt)
    fun longDate(d: LocalDate): String = d.format(longDateFmt)
}
