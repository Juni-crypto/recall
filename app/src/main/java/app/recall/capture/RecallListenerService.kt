package app.recall.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.recall.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives every notification posted on the phone. No filtering happens here: everything
 * is handed to [Ingestor], which stores it and decides what matters.
 */
class RecallListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        _connected.value = true
        App.prefs.lastListenerEventAt = System.currentTimeMillis()
        // Pick up whatever is already in the shade (e.g. right after install).
        val active = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        App.scope.launch {
            active.forEach { handlePosted(it) }
        }
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        requestRebind(ComponentName(this, RecallListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        App.prefs.lastListenerEventAt = System.currentTimeMillis()
        App.scope.launch { handlePosted(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        App.scope.launch {
            try {
                Ingestor.onRemoved(sbn, reason)
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification
            n?.contentIntent?.let { contentIntents[Normalizer.convKey(sbn)] = it }
            val summary = n != null && n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val hasChildren = !summary || try {
                activeNotifications?.any {
                    it.groupKey == sbn.groupKey && it.key != sbn.key &&
                        it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                } ?: false
            } catch (_: Exception) {
                true
            }
            Ingestor.onPosted(this, sbn, hasChildren)
        } catch (_: Exception) {
            // A malformed notification from one app must never stop capture.
        }
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected

        /** Tap targets of notifications seen this session, so "Open" jumps to the exact chat. */
        val contentIntents = ConcurrentHashMap<String, PendingIntent>()

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun rebind(context: Context) {
            if (isEnabled(context)) {
                requestRebind(ComponentName(context, RecallListenerService::class.java))
            }
        }
    }
}
