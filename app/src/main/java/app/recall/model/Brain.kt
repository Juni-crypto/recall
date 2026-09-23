package app.recall.model

import app.recall.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** JNI entry points, implemented in src/main/cpp/recall_jni.cpp. */
object LlamaNative {
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray)
    }

    init {
        System.loadLibrary("recall")
    }

    external fun init(nativeLibDir: String)
    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    // Text crosses JNI as UTF-8 bytes: JNI's own string encoding mangles emoji.
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun generate(
        handle: Long, system: ByteArray, user: ByteArray, maxTokens: Int, temperature: Float, thinking: Boolean,
        callback: TokenCallback?,
    ): ByteArray
    external fun setThreads(handle: Long, n: Int)
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}

enum class BrainState { Idle, Loading, Thinking }

/**
 * The on-device language model. Loads lazily, runs one request at a time on its own
 * thread, and unloads after a short idle period to give memory back to the phone.
 */
object Brain {
    const val CONTEXT = 4096
    private const val IDLE_UNLOAD_MS = 90_000L

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "recall-brain").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(thread)
    private val watchScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(BrainState.Idle)
    val state: StateFlow<BrainState> = _state

    /** Why the last request didn't run (heat, battery, memory), for the UI. */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked

    @Volatile private var handle = 0L
    @Volatile private var loadedPath: String? = null
    private var unloadJob: Job? = null
    private var initialized = false

    fun available(): Boolean = ModelStore.active() != null

    fun activeName(): String? = ModelStore.active()?.name

    private fun ensureLoaded(file: File, load: Safety.Load): Boolean {
        if (handle != 0L && loadedPath == file.absolutePath) return true
        freeNow()
        val mem = Safety.memoryFor(file.length())
        if (!mem.ok) {
            _blocked.value = mem.reason
            return false
        }
        if (!initialized) {
            LlamaNative.init(App.ctx.applicationInfo.nativeLibraryDir)
            initialized = true
        }
        _state.value = BrainState.Loading
        handle = LlamaNative.load(file.absolutePath, CONTEXT, Safety.threads(load))
        loadedPath = if (handle != 0L) file.absolutePath else null
        return handle != 0L
    }

    private fun freeNow() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
            loadedPath = null
        }
    }

    /**
     * Returns the model's answer, or null when no model is installed or it failed to load.
     * [onText] receives the visible text so far (thinking blocks removed) as it streams.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int = 256,
        temperature: Float = 0.4f,
        load: Safety.Load = Safety.Load.Light,
        onText: (String) -> Unit = {},
    ): String? = mutex.withLock {
        withContext(thread) {
            unloadJob?.cancel()
            val model = ModelStore.active() ?: return@withContext null
            val verdict = Safety.check(load)
            if (!verdict.ok) {
                _blocked.value = verdict.reason
                return@withContext null
            }
            // Background work yields to whatever the user is doing.
            android.os.Process.setThreadPriority(
                if (load == Safety.Load.Interactive) android.os.Process.THREAD_PRIORITY_DEFAULT
                else android.os.Process.THREAD_PRIORITY_BACKGROUND,
            )
            val started = System.currentTimeMillis()
            var watchdog: Job? = null
            try {
                if (!ensureLoaded(model.file, load)) return@withContext null
                _blocked.value = null
                LlamaNative.setThreads(handle, Safety.threads(load))
                // Stop mid-answer if the phone heats up (not for answers the user is waiting on).
                if (load != Safety.Load.Interactive) {
                    val h = handle
                    watchdog = watchScope.launch {
                        while (true) {
                            delay(5_000)
                            if (Safety.isHot()) {
                                _blocked.value = "Paused: the phone is warm"
                                LlamaNative.cancel(h)
                                break
                            }
                        }
                    }
                }
                _state.value = BrainState.Thinking
                val raw = StringBuilder()
                val bytes = LlamaNative.generate(handle, system.toByteArray(), user.toByteArray(), maxTokens, temperature, false) { piece ->
                    raw.append(String(piece, Charsets.UTF_8))
                    onText(visible(raw.toString()))
                }
                visible(String(bytes, Charsets.UTF_8)).trim()
            } catch (e: Throwable) {
                null
            } finally {
                watchdog?.cancel()
                Safety.record(System.currentTimeMillis() - started, load)
                _state.value = BrainState.Idle
                scheduleUnload()
            }
        }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    fun unloadNow() {
        scope.launch { mutex.withLock { freeNow() } }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock { freeNow() }
        }
    }

    private val THINK = Regex("""<think>.*?(</think>|$)""", RegexOption.DOT_MATCHES_ALL)
    private val SPECIAL = Regex("""<\|[^|>]{1,40}\|>""")

    /** Strips reasoning blocks and stray template tokens from model output. */
    fun visible(s: String): String = s.replace(THINK, "").replace(SPECIAL, "").trimStart()
}
