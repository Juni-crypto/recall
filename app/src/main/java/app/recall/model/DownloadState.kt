package app.recall.model

data class DownloadState(
    val running: Boolean,
    val done: Long,
    val total: Long,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}
