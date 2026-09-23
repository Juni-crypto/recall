package app.recall

import android.app.Application
import android.content.Context
import app.recall.data.Db
import app.recall.digest.DigestScheduler
import app.recall.digest.Notifier
import app.recall.status.Status
import app.recall.data.Repo
import app.recall.understand.MoneyParser
import app.recall.understand.Understand
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RecallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}

/** Process-wide singletons. Recall is small enough that a DI framework would be noise. */
object App {
    lateinit var ctx: Context
        private set

    val prefs by lazy { Prefs(ctx) }
    val db by lazy { Db(ctx) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        ctx = context.applicationContext
        if (prefs.firstRunAt == 0L) prefs.firstRunAt = System.currentTimeMillis()
        Notifier.createChannels(ctx)
        DigestScheduler.scheduleNext(ctx)
        Status.start(ctx)
        app.recall.learn.TriageWorker.schedule(ctx)
        app.recall.learn.OwnerName.load()
        if (prefs.moneyParserVersion < MoneyParser.VERSION) {
            scope.launch {
                app.recall.learn.OwnerName.detect()
                Understand.reparseMoney()
                prefs.moneyParserVersion = MoneyParser.VERSION
            }
        }
        if (prefs.emailRereadVersion < EMAIL_REREAD_VERSION) {
            scope.launch {
                Repo.splitEmailThreads(System.currentTimeMillis() - Repo.DAY)
                prefs.emailRereadVersion = EMAIL_REREAD_VERSION
                app.recall.learn.TriageWorker.runSoon(ctx)
            }
        }
    }

    /** Bump when email understanding changes, so the last day of email is read again. */
    private const val EMAIL_REREAD_VERSION = 2
}
