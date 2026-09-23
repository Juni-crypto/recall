package app.recall.ui.history

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.recall.App
import app.recall.capture.CallLogReader
import app.recall.capture.SmsReader
import app.recall.capture.WhatsAppImport
import app.recall.data.Repo
import app.recall.ui.components.Label
import app.recall.ui.components.Pill
import app.recall.ui.components.RCard
import app.recall.ui.components.SmallButton
import app.recall.ui.components.Tone
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import kotlinx.coroutines.launch

private data class Counts(val all: Int = 0, val sms: Int = 0, val calls: Int = 0, val chats: Int = 0)

/** One place to bring older history into Recall so it has context from day one. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val counts by rememberQuery(Counts()) {
        Counts(Repo.countAllMessages(), Repo.countByPkg(SmsReader.PKG), Repo.countByPkg(CallLogReader.PKG), Repo.countImportedChats())
    }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val smsOk = remember(refresh) { SmsReader.granted(context) }
    val callsOk = remember(refresh) { CallLogReader.granted(context) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        App.scope.launch { SmsReader.importAll(context) }
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        App.scope.launch { CallLogReader.sync(context) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = "chat"
        App.scope.launch {
            val results = uris.mapNotNull { runCatching { WhatsAppImport.import(context, it) }.getOrNull() }
            message = if (results.sumOf { it.imported } == 0) "No messages found. Pick the .txt or .zip from WhatsApp's Export chat."
            else "Imported ${results.sumOf { it.imported }} messages from ${results.joinToString { it.chat }}"
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            Text("Bring in your history", style = Type.h2)
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "The more history Recall has, the sooner it knows who matters, how fast you usually reply, and where your money goes. " +
                        "Everything is read and kept on this phone. Recall has ${counts.all} messages so far.",
                    style = Type.small,
                )
            }
            item {
                RCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SMS", style = Type.title, modifier = Modifier.weight(1f))
                        Pill("${counts.sms} saved", if (smsOk) Tone.Money else Tone.Neutral)
                    }
                    Text("Every SMS on the phone, years back: bank alerts, bills, deliveries and personal texts.", style = Type.small)
                    SmallButton(
                        when { busy == "sms" -> "Reading…"; smsOk -> "Read all SMS"; else -> "Allow SMS access" },
                        primary = true, enabled = busy == null,
                    ) {
                        if (!smsOk) smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        else {
                            busy = "sms"
                            App.scope.launch { SmsReader.importAll(context); busy = null; message = "SMS history is in." }
                        }
                    }
                }
            }
            item {
                RCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Calls", style = Type.title, modifier = Modifier.weight(1f))
                        Pill("${counts.calls} saved", if (callsOk) Tone.Money else Tone.Neutral)
                    }
                    Text("The last 90 days of calls. Missed calls show up in Waiting, and calling someone back marks theirs as handled.", style = Type.small)
                    if (!callsOk) SmallButton("Allow call history", primary = true) { callPermission.launch(Manifest.permission.READ_CALL_LOG) }
                }
            }
            item {
                RCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("WhatsApp chats", style = Type.title, modifier = Modifier.weight(1f))
                        Pill("${counts.chats} chats", if (counts.chats > 0) Tone.Money else Tone.Neutral)
                    }
                    Text(
                        "Bring in whole conversations with the people who matter. In WhatsApp, open a chat → ⋮ → More → Export chat → Without media → Recall.",
                        style = Type.small,
                    )
                    SmallButton(if (busy == "chat") "Importing…" else "Or pick an exported file", enabled = busy == null) {
                        picker.launch(arrayOf("text/plain", "application/zip"))
                    }
                }
            }
            item {
                RCard {
                    Label("Android's notification history")
                    Text(
                        "Android keeps its own notification history (Settings → Notifications → Notification history), but only lets system apps read it, " +
                            "so no app can import it. From now on Recall keeps every notification itself for a year.",
                        style = Type.small,
                    )
                }
            }
            message?.let { item { Text(it, style = Type.body.copy(color = RC.Accent)) } }
        }
    }
}
