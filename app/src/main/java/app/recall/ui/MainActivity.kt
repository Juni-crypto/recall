package app.recall.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.recall.App
import app.recall.capture.RecallListenerService
import app.recall.capture.SmsReader
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.recall.capture.WhatsAppImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.recall.ui.theme.RC
import app.recall.ui.theme.RecallTheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val tabRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(RC.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(RC.Surface2.toArgb()),
        )
        super.onCreate(savedInstanceState)
        // Debug builds only: load made-up data for screenshots, once.
        if (app.recall.BuildConfig.DEBUG && savedInstanceState == null && intent?.getBooleanExtra("seed_demo", false) == true) {
            intent.removeExtra("seed_demo")
            app.recall.DemoData.seed()
        }
        tabRequests.value = intent?.getStringExtra(EXTRA_TAB)
        handleShare(intent)

        setContent {
            RecallTheme {
                RecallRoot(tabRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tabRequests.value = it }
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // If Android dropped the listener (it happens after updates or on aggressive OEMs), ask for it back.
        if (!RecallListenerService.connected.value) RecallListenerService.rebind(this)
        App.scope.launch {
            SmsReader.sync(applicationContext)
            app.recall.capture.CallLogReader.sync(applicationContext)
            app.recall.learn.TriageWorker.runSoon(applicationContext)
        }
    }

    /** A WhatsApp chat export shared to Recall. */
    private fun handleShare(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris = buildList {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { add(it) }
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { addAll(it) }
        }.distinct()
        val zipOrTxt = uris.filter { u ->
            val t = contentResolver.getType(u).orEmpty()
            t.contains("zip") || t.startsWith("text/")
        }
        if (zipOrTxt.isEmpty()) {
            Toast.makeText(this, "Share a WhatsApp chat export (Export chat → Recall)", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Importing chat…", Toast.LENGTH_SHORT).show()
        App.scope.launch {
            val results = zipOrTxt.map { runCatching { WhatsAppImport.import(applicationContext, it) }.getOrNull() }
            val ok = results.filterNotNull()
            val msg = if (ok.isEmpty() || ok.sumOf { it.imported } == 0) "Couldn't find any messages in that file"
            else "Imported ${ok.sumOf { it.imported }} messages from ${ok.joinToString { it.chat }}"
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
