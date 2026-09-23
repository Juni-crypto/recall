package app.recall.model

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Offline edition: this build has no INTERNET permission at all, so Android itself guarantees
 * nothing leaves the phone. Models are downloaded in a browser and imported from a file.
 */
object Edition {
    const val CAN_DOWNLOAD = false

    fun download(context: Context, spec: ModelSpec) = Unit
    fun downloadCustom(context: Context, url: String): Boolean = false
    fun cancel(context: Context, spec: ModelSpec) = Unit
    fun state(context: Context, spec: ModelSpec): Flow<DownloadState?> = flowOf(null)
}
