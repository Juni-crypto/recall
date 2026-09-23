package app.recall.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.recall.data.OpenLoop
import app.recall.learn.InsightWriter
import app.recall.ui.components.FilterChip
import app.recall.ui.components.SmallButton
import app.recall.ui.components.rememberQuery
import app.recall.ui.orb.OrbMood
import app.recall.ui.orb.RecallOrb
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Time
import app.recall.data.Repo

data class HomeData(val loops: List<OpenLoop>, val noticed: List<String>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(mood: OrbMood, capturing: Boolean, onAsk: (String?) -> Unit, onFixCapture: () -> Unit, onLearned: () -> Unit) {
    val data by rememberQuery(HomeData(emptyList(), emptyList())) {
        HomeData(Repo.openLoops(), InsightWriter.noticed())
    }
    val urgent = data.loops.count { it.isUrgent }
    val waiting = data.loops.size

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF171B3A), RC.Bg), radius = 1400f)),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            Text("${greeting()} · ${Time.shortDate(Time.today())}", style = Type.small)
            Spacer(Modifier.height(8.dp))
            RecallOrb(mood, Modifier.size(290.dp), onClick = { onAsk(null) })
            Text("Recall", style = Type.wordmark, modifier = Modifier.padding(top = 0.dp))
            Spacer(Modifier.height(10.dp))

            val status = buildAnnotatedString {
                when {
                    !capturing -> append("Recall isn't capturing notifications")
                    urgent > 0 -> {
                        withStyle(SpanStyle(color = RC.Urgent, fontWeight = FontWeight.SemiBold)) {
                            append(if (urgent == 1) "1 urgent thing" else "$urgent urgent things")
                        }
                        append(if (urgent == 1) " needs you" else " need you")
                    }
                    waiting > 0 -> {
                        withStyle(SpanStyle(color = RC.Wait, fontWeight = FontWeight.SemiBold)) {
                            append(if (waiting == 1) "1 person" else "$waiting people")
                        }
                        append(if (waiting == 1) " is waiting on you" else " are waiting on you")
                    }
                    else -> append("Nothing needs you right now")
                }
            }
            Text(status, style = Type.body.copy(color = Color(0xFFDCE1FF)), textAlign = TextAlign.Center)
            if (!capturing) {
                Spacer(Modifier.height(10.dp))
                SmallButton("Turn capture back on", primary = true, onClick = onFixCapture)
            }
            data.noticed.firstOrNull()?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.9f))
            }
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("What did I miss today?", false) { onAsk("What did I miss today?") }
                FilterChip("Who's waiting on me?", false) { onAsk("Who is waiting on my reply?") }
                FilterChip("Money today", false) { onAsk("How much did I spend today?") }
                FilterChip("What you know about me", false, onLearned)
            }
            Spacer(Modifier.height(24.dp))
            Text("Tap the orb to ask", style = Type.small.copy(color = RC.Dim))
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun greeting(): String = when (Time.hour(Time.now())) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}
