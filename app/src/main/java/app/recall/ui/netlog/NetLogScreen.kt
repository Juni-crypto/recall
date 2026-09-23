package app.recall.ui.netlog

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recall.data.NetEntry
import app.recall.data.Repo
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Fmt
import app.recall.util.Time

@Composable
fun NetLogScreen(onBack: () -> Unit) {
    val log by rememberQuery(emptyList<NetEntry>()) { Repo.netLog() }
    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            Text("Network activity", style = Type.h2)
        }
        Text(
            "Every network request Recall has ever made. Only model downloads from Hugging Face should appear here. " +
                "Your notifications, money and learned facts are never sent anywhere.",
            style = Type.small, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (log.isEmpty()) item { Text("No requests yet.", style = Type.bodySoft) }
            items(log, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(e.host, style = Type.title, modifier = Modifier.weight(1f))
                        Text(if (e.status > 0) "HTTP ${e.status}" else "failed", style = Type.label)
                    }
                    Text(e.path, style = Type.mono.copy(color = RC.Muted), maxLines = 1)
                    Text("${Time.relative(e.at)} · ${Fmt.bytes(e.bytes)} downloaded", style = Type.small)
                }
                HorizontalDivider(color = RC.Line)
            }
        }
    }
}
