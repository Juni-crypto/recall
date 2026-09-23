package app.recall.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.recall.App
import app.recall.data.AppCount
import app.recall.data.Digest
import app.recall.data.OpenLoop
import app.recall.data.Repo
import app.recall.digest.DigestScheduler
import app.recall.learn.InsightWriter
import app.recall.ui.components.ItemRow
import app.recall.ui.components.Label
import app.recall.ui.components.RCard
import app.recall.ui.components.ScreenHeader
import app.recall.ui.components.SmallButton
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Fmt
import app.recall.util.Time
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TodayData(
    val total: Int = 0,
    val apps: List<AppCount> = emptyList(),
    val loops: List<OpenLoop> = emptyList(),
    val spent: Long = 0,
    val received: Long = 0,
    val digest: Digest? = null,
    val noticed: List<String> = emptyList(),
)

@Composable
fun TodayScreen(onWaiting: () -> Unit, onMoney: () -> Unit, onEverything: () -> Unit) {
    val context = LocalContext.current
    val d by rememberQuery(TodayData()) {
        val (start, end) = Time.bounds(Time.today())
        val (spent, received) = Repo.moneyTotals(start, end)
        TodayData(
            total = Repo.countMessages(start, end),
            apps = Repo.appCounts(start, end),
            loops = Repo.openLoops(),
            spent = spent, received = received,
            digest = Repo.digest(Time.key(Time.today())),
            noticed = InsightWriter.noticed(),
        )
    }
    val urgent = d.loops.filter { it.isUrgent }
    val waiting = d.loops.filterNot { it.isUrgent }
    val digestTime = LocalTime.of(App.prefs.digestHour, App.prefs.digestMinute)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Today", "${Time.shortDate(Time.today())} · ${d.total} notifications · ${d.apps.size} apps")
        }
        item {
            RCard {
                Label("Your day", trailing = if (d.digest?.model != null) "written on-device" else null)
                val summary = d.digest?.summary ?: liveSummary(d, urgent.size, waiting.size)
                Text(summary, style = if (d.digest?.model != null) Type.italic else Type.bodySoft)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        if (d.digest != null) "Built at ${Time.clock(d.digest!!.createdAt)}" else "Your digest arrives at $digestTime",
                        style = Type.small, modifier = Modifier.weight(1f),
                    )
                    SmallButton(if (d.digest != null) "Rebuild" else "Build now") { DigestScheduler.runNow(context, notify = false) }
                }
            }
        }
        if (urgent.isNotEmpty()) item {
            RCard(onClick = onWaiting) {
                Label("Urgent", trailing = urgent.size.toString())
                urgent.take(4).forEach { l ->
                    ItemRow(RC.Urgent, "${l.person} · ${l.app}", Time.clock(l.lastAt), if (l.isCall) l.askText else "\"${l.askText.take(120)}\"")
                }
            }
        }
        if (d.noticed.isNotEmpty()) item {
            RCard {
                Label("Noticed")
                d.noticed.forEach { ItemRow(RC.AccentMid, it, null, null) }
            }
        }
        item {
            RCard(onClick = onWaiting) {
                Label("Waiting on you", trailing = if (waiting.isEmpty()) "none" else "${waiting.size} →")
                if (waiting.isEmpty()) {
                    Text("Nobody else is waiting on a reply.", style = Type.small)
                } else {
                    waiting.take(3).forEach { l ->
                        ItemRow(RC.Wait, "${l.person} · ${l.app}", Time.relative(l.firstAt), l.askText.take(100))
                    }
                }
            }
        }
        item {
            RCard(onClick = onMoney) {
                Label("Money", trailing = "→")
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Spent", style = Type.small)
                        Text(Fmt.rupees(d.spent), style = Type.h2)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Received", style = Type.small)
                        Text(Fmt.rupees(d.received), style = Type.h2.copy(color = RC.Money))
                    }
                }
            }
        }
        item {
            RCard(onClick = onEverything) {
                Label("Everything", trailing = "${d.total} →")
                val apps = d.apps.take(6).joinToString(" · ") { "${it.app} ${it.count}" } +
                    if (d.apps.size > 6) " · +${d.apps.size - 6} apps" else ""
                Text(apps.ifEmpty { "Nothing yet today." }, style = Type.small)
            }
        }
    }
}

private fun liveSummary(d: TodayData, urgent: Int, waiting: Int): String {
    if (d.total == 0) return "A quiet day so far. Nothing has come in yet."
    val needs = when {
        urgent > 0 -> "$urgent urgent and $waiting more waiting on you."
        waiting > 0 -> "$waiting ${if (waiting == 1) "person is" else "people are"} waiting on you."
        else -> "Nobody is waiting on you."
    }
    val busiest = d.apps.firstOrNull()?.let { " Busiest so far: ${it.app} (${it.count})." } ?: ""
    return "$needs ${d.total} notifications from ${d.apps.size} apps.$busiest"
}
