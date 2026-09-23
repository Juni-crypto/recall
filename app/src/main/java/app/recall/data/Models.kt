package app.recall.data

/** What the notification listener turns every notification into. */
data class Captured(
    val hash: String,
    val pkg: String,
    val app: String,
    val convKey: String,
    val convTitle: String?,
    val sender: String?,
    val text: String,
    val isSelf: Boolean,
    val isGroup: Boolean,
    val kind: String,
    val category: String?,
    val at: Long,
    val sbnKey: String?,
    val ongoing: Boolean,
)

object Kind {
    const val CHAT = "chat"
    const val SMS = "sms"
    const val EMAIL = "email"
    const val CALL = "call"
    const val OTHER = "other"
}

data class Message(
    val id: Long,
    val pkg: String,
    val app: String,
    val convKey: String,
    val convTitle: String?,
    val sender: String?,
    val text: String,
    val isSelf: Boolean,
    val isGroup: Boolean,
    val kind: String,
    val at: Long,
    val ongoing: Boolean,
)

data class OpenLoop(
    val id: Long,
    val convKey: String,
    val pkg: String,
    val app: String,
    val person: String,
    val askText: String,
    val firstAt: Long,
    val lastAt: Long,
    val asks: Int,
    val urgency: Int,
    val reasons: List<String>,
    val dueHint: String?,
    val isCall: Boolean,
    val state: String,
    val seenAt: Long?,
    val snoozeUntil: Long?,
    val firstMsgId: Long? = null,
    /** Reviewed by the on-device model. */
    val ai: Boolean = false,
) {
    val isUrgent get() = urgency >= URGENT_THRESHOLD

    companion object {
        const val URGENT_THRESHOLD = 70
    }
}

data class Txn(
    val id: Long,
    val amountPaise: Long,
    val direction: String,
    val merchant: String?,
    val category: String,
    val account: String?,
    val sources: List<String>,
    val at: Long,
    /** The message this payment was read from. */
    val raw: String? = null,
) {
    val isOut get() = direction == "out"
}

data class Digest(
    val date: String,
    val createdAt: Long,
    val summary: String,
    val noticed: List<String>,
    val urgent: Int,
    val waiting: Int,
    val spentPaise: Long,
    val receivedPaise: Long,
    val total: Int,
    val apps: List<Pair<String, Int>>,
    /** Short lines for the notification (people + asks). */
    val lines: List<String>,
    val model: String?,
)

data class Fact(
    val id: Long,
    val key: String,
    val kind: String,
    val text: String,
    val createdAt: Long,
)

data class ChatMsg(
    val id: Long,
    val role: String,
    val text: String,
    val cites: List<String>,
    val at: Long,
)

data class NetEntry(
    val id: Long,
    val host: String,
    val path: String,
    val bytes: Long,
    val status: Int,
    val at: Long,
)

data class Profile(
    val personKey: String,
    val display: String,
    val pkg: String,
    val msgsIn: Int,
    val replies: Int,
    val medianReplyS: Long?,
    val misses: Int,
    val importance: Double,
)

data class AppCount(val app: String, val pkg: String, val count: Int)
