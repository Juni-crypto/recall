package app.recall.model

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.edit
import app.recall.App
import app.recall.util.Time

/**
 * Guards every model run so Recall never cooks, drains or starves the phone.
 *
 * - Heat: pauses as soon as Android reports the phone is warming up.
 * - Battery: heavy work only while charging; light work not below 30% unless charging.
 * - Memory: won't load a model without enough free RAM to spare.
 * - Budget: background model time on battery is capped per day.
 */
object Safety {
    /** Background model time allowed per day while on battery. */
    const val DAILY_BATTERY_BUDGET_MS = 20 * 60 * 1000L
    private const val MIN_BATTERY = 30

    enum class Load { Interactive, Light, Heavy }

    data class Verdict(val ok: Boolean, val reason: String? = null) {
        companion object { val OK = Verdict(true) }
    }

    data class Power(val charging: Boolean, val level: Int)

    fun power(c: Context = App.ctx): Power {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = i?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else 100
        } ?: 100
        return Power(charging, level)
    }

    /** 0 none … 7 shutdown (PowerManager.THERMAL_STATUS_*). */
    fun thermal(c: Context = App.ctx): Int = c.getSystemService(PowerManager::class.java).currentThermalStatus

    fun isHot(c: Context = App.ctx) = thermal(c) >= PowerManager.THERMAL_STATUS_MODERATE

    fun check(load: Load, c: Context = App.ctx): Verdict {
        val t = thermal(c)
        if (t >= PowerManager.THERMAL_STATUS_SEVERE) return Verdict(false, "Paused: the phone is hot")
        if (load == Load.Interactive) return Verdict.OK
        if (t >= PowerManager.THERMAL_STATUS_MODERATE) return Verdict(false, "Paused: the phone is warm")
        val p = power(c)
        if (!p.charging) {
            if (load == Load.Heavy) return Verdict(false, "Waiting for the charger")
            if (p.level < MIN_BATTERY) return Verdict(false, "Paused: battery below $MIN_BATTERY%")
            if (usedTodayMs() >= DAILY_BATTERY_BUDGET_MS) return Verdict(false, "Paused: today's on-battery AI time is used up")
        }
        return Verdict.OK
    }

    /** Enough free RAM to load a model of [bytes] and still leave the phone comfortable. */
    fun memoryFor(bytes: Long, c: Context = App.ctx): Verdict {
        val am = c.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val need = bytes + 800L * 1024 * 1024
        return if (info.lowMemory || info.availMem < need) {
            Verdict(false, "Not enough free memory for the model right now (needs %.1f GB free)".format(need / 1e9))
        } else {
            Verdict.OK
        }
    }

    /** Cores to use: all fast ones while charging or when the user is waiting, fewer otherwise. */
    fun threads(load: Load): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val max = (cores - 4).coerceIn(2, 4)
        return if (load == Load.Interactive || power().charging) max else 2
    }

    // ---- daily budget (only counted while on battery)

    private val prefs get() = App.ctx.getSharedPreferences("recall", Context.MODE_PRIVATE)

    fun usedTodayMs(): Long {
        val day = Time.key(Time.today())
        return if (prefs.getString("ai_day", null) == day) prefs.getLong("ai_ms", 0) else 0
    }

    fun record(ms: Long, load: Load) {
        if (load == Load.Interactive || power().charging) return
        val day = Time.key(Time.today())
        val used = usedTodayMs()
        prefs.edit { putString("ai_day", day); putLong("ai_ms", used + ms) }
    }
}
