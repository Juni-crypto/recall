package app.recall.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.data.Repo
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type

/** Live query: re-runs [block] on a background thread after every database write. */
@Composable
fun <T> rememberQuery(initial: T, vararg keys: Any?, block: () -> T): State<T> {
    val flow = remember(*keys) { Repo.watch(block) }
    return flow.collectAsStateWithLifecycle(initial)
}

@Composable
fun RCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) Color(0xFF161B33) else RC.Surface)
            .border(1.dp, if (highlight) RC.AccentDeep else RC.Line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.label, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing.uppercase(), style = Type.label)
    }
}

enum class Tone { Neutral, Urgent, Wait, Money, Accent }

@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val (fg, bg, line) = when (tone) {
        Tone.Neutral -> Triple(RC.Soft, Color.Transparent, Color(0xFF333A45))
        Tone.Urgent -> Triple(Color(0xFFFF9A9A), RC.UrgentSoft, RC.UrgentLine)
        Tone.Wait -> Triple(Color(0xFFF4C872), RC.WaitSoft, RC.WaitLine)
        Tone.Money -> Triple(Color(0xFF7FDCB0), RC.MoneySoft, RC.MoneyLine)
        Tone.Accent -> Triple(Color(0xFFC3CBFF), Color(0xFF1A1F3D), Color(0xFF343E7A))
    }
    Text(
        text,
        style = Type.label.copy(color = fg, letterSpacing = 0.3.sp),
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.small.copy(color = if (selected) Color(0xFFDCE1FF) else RC.Soft),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) RC.AccentSoft else Color.Transparent)
            .border(1.dp, if (selected) RC.AccentDeep else RC.Line2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = Type.button.copy(color = if (!enabled) RC.Dim else if (primary) Color.White else Color(0xFFDCE1FF)),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary && enabled) RC.AccentDeep else RC.Raised)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) RC.AccentDeep else RC.Raised)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(fontSize = 15.sp, color = if (enabled) Color.White else RC.Dim))
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.h1)
            if (subtitle != null) Text(subtitle, style = Type.small)
        }
        trailing()
    }
}

/** One row inside a card: coloured dot, who, when, what. */
@Composable
fun ItemRow(dot: Color, who: String, meta: String?, text: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(dot)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(who, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (meta != null) Text(meta, style = Type.small, maxLines = 1)
            }
            if (!text.isNullOrBlank()) Text(text, style = Type.bodySoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SettingRow(title: String, value: String? = null, ok: Boolean? = null, onClick: (() -> Unit)? = null, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title)
            if (subtitle != null) Text(subtitle, style = Type.small)
        }
        when {
            ok == true -> Text("ON", style = Type.label.copy(color = RC.Money))
            ok == false -> Text("FIX", style = Type.label.copy(color = RC.Wait))
            value != null -> Text(value, style = Type.mono.copy(color = RC.Accent))
        }
    }
}
