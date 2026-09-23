package app.recall.ui.everything

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recall.chat.QueryParser
import app.recall.data.Message
import app.recall.data.Repo
import app.recall.ui.components.rememberQuery
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Time

@Composable
fun EverythingScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by rememberQuery(emptyList<Message>(), query) {
        val fts = QueryParser.ftsQuery(query.lowercase().split(Regex("""\s+""")).filter { it.length >= 3 })
        if (query.isBlank() || fts == null) Repo.recentMessages(400) else Repo.search(fts, limit = 400)
    }

    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            Text("Everything", style = Type.h2, modifier = Modifier.weight(1f))
            Text("${results.size}${if (results.size >= 400) "+" else ""}", style = Type.label, modifier = Modifier.padding(end = 16.dp))
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(RC.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) Text("Search every notification", style = Type.body.copy(color = RC.Dim))
            BasicTextField(query, { query = it }, textStyle = Type.body, cursorBrush = SolidColor(RC.Accent), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(results, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row {
                        Text(m.app, style = Type.label.copy(color = RC.Accent), modifier = Modifier.weight(1f))
                        Text(Time.relative(m.at), style = Type.label)
                    }
                    val who = when {
                        m.isSelf -> "You → ${m.convTitle ?: ""}"
                        m.isGroup -> "${m.sender} · ${m.convTitle}"
                        else -> m.sender ?: m.convTitle ?: m.app
                    }
                    Text(who, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.text, style = Type.bodySoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = RC.Line)
            }
        }
    }
}
