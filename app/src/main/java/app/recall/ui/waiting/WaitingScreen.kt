package app.recall.ui.waiting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recall.App
import app.recall.data.OpenLoop
import app.recall.data.Profile
import app.recall.data.Repo
import app.recall.understand.AppKinds
import app.recall.ui.LoopActions
import app.recall.ui.components.FilterChip
import app.recall.ui.components.Pill
import app.recall.ui.components.RCard
import app.recall.ui.components.ScreenHeader
import app.recall.ui.components.SmallButton
import app.recall.ui.components.Tone
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.Type
import app.recall.util.Fmt
import app.recall.util.Time
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { All("All"), Urgent("Urgent"), Work("Work"), People("People") }

@Composable
fun WaitingScreen() {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val data by rememberQuery(emptyList<Pair<OpenLoop, Profile?>>()) {
        Repo.openLoops().map { it to Repo.profile(it.convKey) }
    }
    val shown = data.filter { (l, _) ->
        when (filter) {
            Filter.All -> true
            Filter.Urgent -> l.isUrgent
            Filter.Work -> AppKinds.isWork(l.pkg)
            Filter.People -> !AppKinds.isWork(l.pkg)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Waiting on you", "Requests with no reply from you yet") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    val n = if (f == Filter.All) " ${data.size}" else ""
                    FilterChip(f.label + n, filter == f) { filter = f }
                }
            }
        }
        if (shown.isEmpty()) item {
            RCard {
                Text(if (data.isEmpty()) "Nobody's waiting on you." else "Nothing here.", style = Type.title)
                Text(
                    "When someone asks you something and you haven't replied, it shows up here until you do.",
                    style = Type.small,
                )
            }
        }
        items(shown, key = { it.first.id }) { (loop, profile) ->
            LoopCard(loop, profile, onOpen = { LoopActions.open(context, loop) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoopCard(loop: OpenLoop, profile: Profile?, onOpen: () -> Unit) {
    val age = Time.now() - loop.firstAt
    RCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(loop.person, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (loop.ai) Pill("Qwen", Tone.Accent)
            when {
                loop.isUrgent -> Pill(
                    listOfNotNull("urgent", loop.asks.takeIf { it > 1 && !loop.isCall }?.let { "asked $it×" }).joinToString(" · "),
                    Tone.Urgent,
                )
                loop.dueHint != null -> Pill("due ${loop.dueHint}", Tone.Wait)
                profile?.medianReplyS != null && age > 60_000 -> Pill(
                    "${Fmt.duration(age)} · normal ${Fmt.duration(profile.medianReplyS * 1000)}",
                )
                else -> Pill(Fmt.duration(age))
            }
        }
        val times = if (loop.asks > 1) "${Time.relative(loop.firstAt)}, ${Time.clock(loop.lastAt)}" else Time.relative(loop.firstAt)
        Text("${loop.app} · $times", style = Type.small)
        Text(if (loop.isCall) loop.askText else "\"${loop.askText.take(280)}\"", style = Type.bodySoft)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            SmallButton("Open ${loop.app}", primary = true, onClick = onOpen)
            SmallButton("Done") { App.scope.launch { LoopActions.done(loop) } }
            SmallButton("Snooze") { App.scope.launch { LoopActions.snooze(loop) } }
            SmallButton("Not for me") { App.scope.launch { LoopActions.notForMe(loop) } }
        }
    }
}
