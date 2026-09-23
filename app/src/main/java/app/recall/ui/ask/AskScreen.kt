package app.recall.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.recall.chat.ChatEngine
import app.recall.data.ChatMsg
import app.recall.data.Repo
import app.recall.model.Brain
import app.recall.ui.components.FilterChip
import app.recall.ui.components.Pill
import app.recall.ui.components.Tone
import app.recall.ui.components.rememberQuery
import app.recall.ui.orb.OrbMood
import app.recall.ui.orb.RecallOrb
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(prefill: String?, onBack: () -> Unit, onModels: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages by rememberQuery(emptyList<ChatMsg>()) { Repo.chat() }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    val hasModel = remember { Brain.available() }
    val list = rememberLazyListState()

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        pending = q
        partial = ""
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ChatEngine.ask(q) { partial = it }
                } catch (_: Exception) {
                    Repo.addChat("assistant", "Something went wrong answering that. Try again.")
                }
            }
            pending = null
            partial = null
            busy = false
        }
    }

    LaunchedEffect(prefill) { if (!prefill.isNullOrBlank()) send(prefill) }
    LaunchedEffect(messages.size, partial) {
        val count = messages.size + (if (pending != null) 2 else 0)
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            RecallOrb(if (busy) OrbMood.Thinking else OrbMood.Needs, Modifier.size(34.dp), animate = busy)
            Text("Recall", style = Type.h2, modifier = Modifier.padding(start = 6.dp).weight(1f))
            Box(Modifier.clickable(onClick = onModels)) {
                if (hasModel) Pill("on-device", Tone.Money) else Pill("no model", Tone.Wait)
            }
            IconButton(onClick = { scope.launch(Dispatchers.IO) { Repo.clearChat() } }) {
                Icon(Icons.Outlined.DeleteOutline, "Clear chat", tint = RC.Dim)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && pending == null) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ask about your day, your people, or your money.", style = Type.bodySoft)
                    if (!hasModel) {
                        Text(
                            "No model yet, so answers are basic lists. Get one in Models for full answers.",
                            style = Type.small.copy(color = RC.Wait),
                            modifier = Modifier.clickable(onClick = onModels),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "What did I miss today?", "Who is waiting on my reply?", "How much did I spend this week?",
                            "Who do I keep missing?", "What did I spend on food this month?",
                        ).forEach { s -> FilterChip(s, false) { send(s) } }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m.role == "user", m.text, m.cites) }
            pending?.let { q ->
                item(key = "pending-q") { Bubble(true, q, emptyList()) }
                item(key = "pending-a") {
                    Bubble(false, partial?.takeIf { it.isNotBlank() } ?: if (hasModel) "Thinking…" else "Looking…", emptyList(), dim = partial.isNullOrBlank())
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(RC.Surface)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (input.isEmpty()) Text("Ask about your day…", style = Type.body.copy(color = RC.Dim))
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = Type.body, cursorBrush = SolidColor(RC.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (input.isNotBlank() && !busy) RC.Accent else RC.Dim)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(mine: Boolean, text: String, cites: List<String>, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp))
                .background(if (mine) RC.AccentDeep else RC.Surface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text, style = Type.body.copy(color = if (mine) Color.White else if (dim) RC.Muted else RC.Text))
            if (cites.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cites.forEach { Pill(it) }
                }
            }
        }
    }
    Spacer(Modifier.size(0.dp))
}
