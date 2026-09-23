package app.recall

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("recall", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit { putBoolean("onboarded", v) }

    var digestHour: Int
        get() = sp.getInt("digest_hour", 21)
        set(v) = sp.edit { putInt("digest_hour", v) }

    var digestMinute: Int
        get() = sp.getInt("digest_minute", 30)
        set(v) = sp.edit { putInt("digest_minute", v) }

    /** File name (inside filesDir/models) of the model Recall should use. */
    var activeModel: String?
        get() = sp.getString("active_model", null)
        set(v) = sp.edit { putString("active_model", v) }

    var learning: Boolean
        get() = sp.getBoolean("learning", true)
        set(v) = sp.edit { putBoolean("learning", v) }

    var allowMobileData: Boolean
        get() = sp.getBoolean("allow_mobile_data", false)
        set(v) = sp.edit { putBoolean("allow_mobile_data", v) }

    /** Optional: names the user goes by, so @mentions in group chats count as "to you". */
    var userNames: String
        get() = sp.getString("user_names", "") ?: ""
        set(v) = sp.edit { putString("user_names", v) }

    var firstRunAt: Long
        get() = sp.getLong("first_run_at", 0L)
        set(v) = sp.edit { putLong("first_run_at", v) }

    var lastFactsAt: Long
        get() = sp.getLong("last_facts_at", 0L)
        set(v) = sp.edit { putLong("last_facts_at", v) }

    /** The always-on status card (lock screen). */
    var lockCard: Boolean
        get() = sp.getBoolean("lock_card", true)
        set(v) = sp.edit { putBoolean("lock_card", v) }

    /** Account holder name(s) learned from bank messages, "|"-separated. */
    var ownerName: String
        get() = sp.getString("owner_name", "") ?: ""
        set(v) = sp.edit { putString("owner_name", v) }

    /** Debug-only demo data is loaded (screenshots); capture counts as on. */
    var demo: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit { putBoolean("demo", v) }

    var moneyParserVersion: Int
        get() = sp.getInt("money_parser_version", 0)
        set(v) = sp.edit { putInt("money_parser_version", v) }

    var lastListenerEventAt: Long
        get() = sp.getLong("last_listener_event_at", 0L)
        set(v) = sp.edit { putLong("last_listener_event_at", v) }
}
