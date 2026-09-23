package app.recall.ui.learned

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.App
import app.recall.capture.Normalizer
import app.recall.data.Fact
import app.recall.data.Profile
import app.recall.data.Repo
import app.recall.learn.Learner
import app.recall.learn.MerchantClassifier
import app.recall.understand.LearnedModel
import app.recall.understand.UrgencyScorer
import app.recall.ui.components.Label
import app.recall.ui.components.Pill
import app.recall.ui.components.RCard
import app.recall.ui.components.SmallButton
import app.recall.ui.components.Tone
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Fmt
import app.recall.util.Time
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class AppHabit(val app: String, val openRate: Double, val total: Int)

private data class Learned(
    val facts: List<Fact> = emptyList(),
    val people: List<Profile> = emptyList(),
    val apps: List<AppHabit> = emptyList(),
    val weights: DoubleArray? = null,
    val labelled: Int = 0,
    val merchants: List<Triple<String, String, String>> = emptyList(),
    val saved: Int = 0,
    val since: Long? = null,
)

private val FEATURE_NAMES = mapOf(
    UrgencyScorer.F_STRONG to "Words like \"urgent\", \"asap\", \"blocking\"",
    UrgencyScorer.F_WEAK to "Words like \"quick\", \"now\"",
    UrgencyScorer.F_DEADLINE to "Deadlines (\"by today\", \"EOD\")",
    UrgencyScorer.F_REPEAT to "Someone pinging again and again",
    UrgencyScorer.F_IMPORTANCE to "People you're close to",
    UrgencyScorer.F_QUESTION to "Questions",
    UrgencyScorer.F_GROUP to "Group chats",
    UrgencyScorer.F_EMAIL to "Emails",
    UrgencyScorer.F_CALL to "Missed calls",
    UrgencyScorer.F_EVENING to "Messages after 6 PM",
    UrgencyScorer.F_APP_OPEN_RATE to "Apps you usually open",
)

@Composable
fun LearnedScreen(onBack: () -> Unit, onHistory: () -> Unit) {
    val d by rememberQuery(Learned()) {
        val apps = Repo.signalCountsByPkg(Time.now() - 30 * Repo.DAY).mapNotNull { (pkg, k) ->
            val opened = (k["opened"] ?: 0) + (k["app_cancel"] ?: 0) + (k["reply"] ?: 0)
            val total = opened + (k["dismissed"] ?: 0)
            if (total < 5) null else AppHabit(Normalizer.appLabel(App.ctx, pkg), opened.toDouble() / total, total)
        }.sortedByDescending { it.total }
        Learned(
            facts = Repo.facts(),
            people = Repo.profiles().take(15),
            apps = apps.take(12),
            weights = Repo.loadWeights()?.first,
            labelled = Repo.labelledLoops(),
            merchants = Repo.merchantCategories(),
            saved = Repo.countAllMessages(),
            since = Repo.firstMessageAt(),
        )
    }
    val learn by app.recall.learn.LearnNow.progress.collectAsStateWithLifecycle()
    val days = d.since?.let { ((Time.now() - it) / Repo.DAY + 1).toInt() } ?: 0

    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            Text("What Recall learnt", style = Type.h2)
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RCard {
                    Text("From $days day${if (days == 1) "" else "s"} and ${d.saved} notifications", style = Type.title)
                    Text(
                        "All of this was worked out on this phone from what you open, answer, ignore and mark. Nothing here has left the phone.",
                        style = Type.small,
                    )
                    SmallButton("Bring in more history", onClick = onHistory)
                    val running = learn?.running == true
                    SmallButton(if (running) "Learning…" else "Learn from my history now", primary = true, enabled = !running) {
                        app.recall.learn.LearnNow.start(App.ctx)
                    }
                    learn?.let { Text(it.text, style = Type.small.copy(color = if (it.running) RC.Accent else RC.Money)) }
                }
            }

            if (d.facts.isNotEmpty()) item {
                RCard {
                    Label("In plain words")
                    d.facts.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = RC.Line)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(f.text, style = Type.bodySoft, modifier = Modifier.weight(1f))
                            Text("✕", style = Type.body.copy(color = RC.Dim), modifier = Modifier.clickable { App.scope.launch { Repo.removeFact(f.id) } }.padding(start = 12.dp))
                        }
                    }
                }
            }

            item {
                RCard {
                    Label("People", trailing = "usual reply")
                    if (d.people.isEmpty()) {
                        Text("Needs a few conversations with replies before it knows your pace with anyone.", style = Type.small)
                    }
                    d.people.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.display, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${p.msgsIn} messages · replied ${p.replies}×" + if (p.misses > 0) " · missed ${p.misses}" else "",
                                    style = Type.small,
                                )
                                Meter(p.importance, if (p.importance >= 0.7) RC.Money else RC.AccentMid)
                            }
                            Text(p.medianReplyS?.let { Fmt.duration(it * 1000) } ?: "—", style = Type.mono.copy(color = RC.Accent), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    Text("The bar is how important Recall thinks each person is to you.", style = Type.small.copy(color = RC.Dim))
                }
            }

            item {
                RCard {
                    Label("Apps", trailing = "you open")
                    if (d.apps.isEmpty()) Text("Learns this as you open or clear notifications.", style = Type.small)
                    d.apps.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.app, style = Type.bodySoft, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).padding(horizontal = 10.dp)) { Meter(a.openRate, if (a.openRate < 0.2) RC.Dim else RC.AccentMid) }
                            Text("${(a.openRate * 100).toInt()}%", style = Type.mono)
                        }
                    }
                }
            }

            item {
                RCard {
                    Label("What counts as urgent for you")
                    val w = d.weights
                    if (w == null) {
                        Text(
                            "Using the starting rules. Recall trains its own urgency model once you've reacted to ${LearnedModel.MIN_EXAMPLES} requests (${d.labelled} so far). Replying, Done and Not for me all count.",
                            style = Type.small,
                        )
                    } else {
                        Text("Trained on your reactions. Longer bars mean it matters more to you.", style = Type.small)
                        val maxW = FEATURE_NAMES.keys.maxOf { abs(w.getOrElse(it) { 0.0 }) }.coerceAtLeast(0.01)
                        FEATURE_NAMES.entries.sortedByDescending { w.getOrElse(it.key) { 0.0 } }.forEach { (idx, name) ->
                            val v = w.getOrElse(idx) { 0.0 }
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = Type.small.copy(color = RC.Soft), modifier = Modifier.weight(1f))
                                Box(Modifier.width(90.dp)) { Meter(abs(v) / maxW, if (v >= 0) RC.Urgent else RC.Dim) }
                            }
                        }
                    }
                }
            }

            item {
                RCard {
                    Label("Merchant labels", trailing = "${d.merchants.size}")
                    if (d.merchants.isEmpty()) Text("Merchants Recall's rules don't know get a category from the on-device model, or from you in Money.", style = Type.small)
                    d.merchants.take(40).forEach { (m, cat, source) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, style = Type.bodySoft, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cat, style = Type.small.copy(color = RC.Accent), modifier = Modifier.padding(horizontal = 8.dp))
                            Pill(if (source == "user") "you" else "model", if (source == "user") Tone.Money else Tone.Neutral)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Meter(value: Double, color: Color) {
    Box(Modifier.fillMaxWidth().height(5.dp).padding(top = 1.dp).clip(RoundedCornerShape(3.dp)).background(RC.Raised)) {
        Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}
