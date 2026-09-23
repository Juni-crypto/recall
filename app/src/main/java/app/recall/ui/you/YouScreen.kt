package app.recall.ui.you

import android.Manifest
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.recall.capture.SmsReader
import app.recall.status.Status
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.App
import app.recall.BuildConfig
import app.recall.data.Fact
import app.recall.data.Repo
import app.recall.digest.DigestScheduler
import app.recall.learn.Learner
import app.recall.model.Edition
import app.recall.model.ModelStore
import app.recall.ui.Health
import app.recall.ui.components.Label
import app.recall.ui.components.RCard
import app.recall.ui.components.ScreenHeader
import app.recall.ui.components.SettingRow
import app.recall.ui.components.SmallButton
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Time
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class YouData(val facts: List<Fact> = emptyList(), val saved: Int = 0, val since: Long? = null)

@Composable
fun YouScreen(onModels: () -> Unit, onNetLog: () -> Unit, onLearned: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val data by rememberQuery(YouData()) { YouData(Repo.facts(), Repo.countAllMessages(), Repo.firstMessageAt()) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val health = remember(refresh) {
        listOf(
            Health.notificationAccess(context), Health.batteryUnrestricted(context),
            Health.exactAlarms(context), Health.postNotifications(context), SmsReader.granted(context),
        )
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        App.scope.launch { SmsReader.sync(context) }
    }
    val modelName = remember(refresh) { ModelStore.active()?.name ?: "None" }
    var learning by remember { mutableStateOf(App.prefs.learning) }
    var lockCard by remember { mutableStateOf(App.prefs.lockCard) }
    var confirmWipe by remember { mutableStateOf(false) }
    val learn by app.recall.learn.LearnNow.progress.collectAsStateWithLifecycle()
    var digestAt by remember { mutableStateOf(LocalTime.of(App.prefs.digestHour, App.prefs.digestMinute)) }
    var names by remember { mutableStateOf(App.prefs.userNames) }
    val focus = LocalFocusManager.current

    val days = data.since?.let { ((Time.now() - it) / Repo.DAY + 1).toInt() } ?: 0

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                "What Recall knows",
                if (days > 0) "Learned over $days day${if (days == 1) "" else "s"} · stays on this phone" else "Stays on this phone",
            )
        }

        item {
            RCard(onClick = onLearned, highlight = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("See everything Recall learnt", style = Type.title)
                        Text("Your people, your apps, what counts as urgent for you, merchant labels", style = Type.small)
                    }
                    Text("→", style = Type.h2.copy(color = RC.Accent))
                }
            }
        }
        item {
            RCard(onClick = onHistory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bring in your history", style = Type.title)
                        Text("All SMS, call history, WhatsApp chats", style = Type.small)
                    }
                    Text("→", style = Type.h2.copy(color = RC.Accent))
                }
            }
        }
        val groups = data.facts.groupBy { it.kind }
        if (data.facts.isEmpty()) item {
            RCard {
                Text("Still learning", style = Type.title)
                Text(
                    "Recall needs about two weeks of your notifications to learn who you answer quickly, what you tend to miss and where your money goes. " +
                        "So far it has saved ${data.saved} notifications.",
                    style = Type.small,
                )
            }
        }
        listOf("people" to "People", "habits" to "Habits", "money" to "Money").forEach { (kind, title) ->
            val facts = groups[kind].orEmpty()
            if (facts.isNotEmpty()) item {
                RCard {
                    Label(title)
                    facts.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = RC.Line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(f.text, style = Type.bodySoft, modifier = Modifier.weight(1f))
                            Text(
                                "✕", style = Type.body.copy(color = RC.Dim),
                                modifier = Modifier.clickable { App.scope.launch { Repo.removeFact(f.id) } }.padding(start = 12.dp, end = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            RCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep learning", style = Type.title)
                        Text("Tap ✕ on anything that's wrong. Recall forgets it and won't bring it back.", style = Type.small)
                    }
                    Switch(checked = learning, onCheckedChange = { learning = it; App.prefs.learning = it })
                }
                val running = learn?.running == true
                SmallButton(if (running) "Learning…" else "Learn from my history now", enabled = !running) {
                    app.recall.learn.LearnNow.start(context)
                }
                learn?.let { Text(it.text, style = Type.small.copy(color = if (it.running) RC.Accent else RC.Money)) }
            }
        }

        item { Label("Settings", Modifier.padding(top = 8.dp)) }
        item {
            RCard {
                SettingRow(
                    "Daily digest", digestAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            App.prefs.digestHour = h
                            App.prefs.digestMinute = m
                            digestAt = LocalTime.of(h, m)
                            DigestScheduler.scheduleNext(context)
                        }, digestAt.hour, digestAt.minute, false).show()
                    },
                )
                HorizontalDivider(color = RC.Line)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show on lock screen", style = Type.title)
                        Text("A quiet card with who's waiting on you", style = Type.small)
                    }
                    Switch(checked = lockCard, onCheckedChange = {
                        lockCard = it
                        App.prefs.lockCard = it
                        Status.refresh(context)
                    })
                }
                HorizontalDivider(color = RC.Line)
                SettingRow("Send today's digest now", onClick = { DigestScheduler.runNow(context, notify = true) }, subtitle = "Builds it and posts the notification")
                HorizontalDivider(color = RC.Line)
                SettingRow("Model", modelName, onClick = onModels)
                HorizontalDivider(color = RC.Line)
                val aiMin = remember(refresh) { app.recall.model.Safety.usedTodayMs() / 60_000 }
                SettingRow(
                    "AI on battery today", "$aiMin of ${app.recall.model.Safety.DAILY_BATTERY_BUDGET_MS / 60_000} min",
                    subtitle = "Background AI pauses when the phone is warm, below 30% battery, or over this limit. Big jobs wait for the charger.",
                )
                HorizontalDivider(color = RC.Line)
                if (Edition.CAN_DOWNLOAD) {
                    SettingRow("Network activity", onClick = onNetLog, subtitle = "Every request Recall has made")
                } else {
                    SettingRow("Network", "none", subtitle = "This edition has no internet permission")
                }
            }
        }
        item {
            RCard {
                Label("Names you go by")
                Text("For @mentions in group chats, e.g. \"Sam, SJ\". Optional.", style = Type.small)
                OutlinedTextField(
                    value = names, onValueChange = { names = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { App.prefs.userNames = names; focus.clearFocus() }),
                )
                if (names != App.prefs.userNames) SmallButton("Save", primary = true) { App.prefs.userNames = names; focus.clearFocus() }
            }
        }
        item {
            RCard {
                Label("Keeps Recall running")
                SettingRow("Notification access", ok = health[0], onClick = { Health.openNotificationAccess(context) })
                SettingRow("Battery: unrestricted", ok = health[1], onClick = { Health.openBattery(context) })
                SettingRow("Exact alarm for the digest", ok = health[2], onClick = { Health.openExactAlarms(context) })
                SettingRow("Notifications", ok = health[3], onClick = { Health.openAppDetails(context) })
                SettingRow(
                    "Bank SMS", ok = health[4], subtitle = "Reads alerts Android hides from notifications",
                    onClick = {
                        if (!health[4]) smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        else Health.openAppDetails(context)
                    },
                )
            }
        }
        item {
            RCard {
                SettingRow("Delete everything Recall has saved", onClick = { confirmWipe = true }, subtitle = "Notifications, money, learned facts, chat. Models stay.")
            }
        }
        item {
            Text(
                "Recall ${BuildConfig.VERSION_NAME} · ${BuildConfig.EDITION} edition · runs entirely on this phone",
                style = Type.small.copy(color = RC.Dim), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = { Text("All saved notifications, money records, learned facts and chat history will be erased from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    App.scope.launch { App.db.wipe() }
                }) { Text("Delete", color = RC.Urgent) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}
