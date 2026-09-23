package app.recall.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.recall.data.Captured
import app.recall.data.Repo
import app.recall.understand.Understand

/** Stores each captured message and hands incoming ones to [Understand]. */
object Ingestor {

    fun onPosted(context: Context, sbn: StatusBarNotification, hasChildren: Boolean = true) {
        for (c in Normalizer.normalize(context, sbn, hasChildren)) handle(c)
    }

    fun handle(c: Captured, trackRequests: Boolean = true) {
        val id = Repo.insertMessage(c) ?: return
        process(c, id, trackRequests)
    }

    /** Everything that happens after a new message is stored. */
    fun process(c: Captured, id: Long, trackRequests: Boolean = true) {
        if (c.ongoing) return
        if (c.isSelf) {
            onSelfMessage(c)
        } else {
            Understand.onIncoming(c, id, trackRequests)
        }
    }

    /** The user replied in this conversation: anything waiting there is answered. */
    private fun onSelfMessage(c: Captured) {
        val lastIn = Repo.lastIncomingBefore(c.convKey, c.at)
        val latencyS = lastIn?.let { ((c.at - it) / 1000.0).coerceAtLeast(0.0) }
        Repo.logSignal("reply", c.pkg, c.convKey, latencyS, at = c.at)
        Repo.closeLoopsForConv(c.convKey, "reply", label = 1)
    }

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val kind = when (reason) {
            NotificationListenerService.REASON_CLICK -> "opened"
            NotificationListenerService.REASON_CANCEL,
            NotificationListenerService.REASON_CANCEL_ALL -> "dismissed"
            // Chat apps usually cancel their notification once you read the chat in the app.
            NotificationListenerService.REASON_APP_CANCEL,
            NotificationListenerService.REASON_APP_CANCEL_ALL -> "app_cancel"
            else -> return
        }
        val convKey = Normalizer.convKey(sbn)
        Repo.logSignal(kind, sbn.packageName, convKey)
        if (kind != "dismissed") Repo.markSeen(convKey, System.currentTimeMillis())
    }
}
