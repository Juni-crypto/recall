package app.recall.status

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.recall.R
import app.recall.ui.MainActivity
import app.recall.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home-screen widget: the orb, what needs you, and today's spend. */
class RecallWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = withContext(Dispatchers.IO) { Status.snapshot() }
        provideContent { Content(context, s) }
    }

    @Composable
    private fun Content(context: Context, s: StatusSnapshot) {
        val size = LocalSize.current
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, if (s.items.isEmpty()) "home" else "waiting")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val orb = when (s.mood) {
            "urgent" -> R.drawable.orb_urgent
            "calm" -> R.drawable.orb_calm
            "off" -> R.drawable.orb_off
            else -> R.drawable.orb_needs
        }
        val accent = when (s.mood) {
            "urgent" -> Color(0xFFFF8A8A)
            "calm" -> Color(0xFF7FD8E6)
            "off" -> Color(0xFF9AA1AD)
            else -> Color(0xFFF2B84B)
        }

        Box(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Color(0xFF12162A)).padding(14.dp).clickable(open),
        ) {
            if (size.width < WIDE.width) {
                // Small: orb and one line.
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(orb), "Recall", GlanceModifier.size(54.dp))
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        if (s.items.isEmpty()) "All clear" else "${s.items.size} waiting",
                        style = TextStyle(color = ColorProvider(accent), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                }
            } else {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(orb), "Recall", GlanceModifier.size(34.dp))
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text("Recall", style = TextStyle(color = ColorProvider(Color(0xFFF1F2F4)), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                            Text(s.headline, style = TextStyle(color = ColorProvider(accent), fontSize = 12.sp), maxLines = 1)
                        }
                    }
                    Spacer(GlanceModifier.height(10.dp))
                    val rows = if (size.height >= TALL.height) 4 else 2
                    if (s.items.isEmpty()) {
                        Text(
                            "Nobody's waiting on you. Digest at ${s.digestAt}.",
                            style = TextStyle(color = ColorProvider(Color(0xFFC9CED6)), fontSize = 13.sp),
                        )
                    }
                    s.items.take(rows).forEach { item ->
                        Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            Text(
                                if (item.urgent) "●" else "○",
                                style = TextStyle(color = ColorProvider(if (item.urgent) Color(0xFFFF6B6B) else Color(0xFFF2B84B)), fontSize = 12.sp),
                            )
                            Spacer(GlanceModifier.width(8.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    "${item.person} · ${item.app}",
                                    style = TextStyle(color = ColorProvider(Color(0xFFF1F2F4)), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                )
                                Text(item.text, style = TextStyle(color = ColorProvider(Color(0xFFA9B0BC)), fontSize = 12.sp), maxLines = 1)
                            }
                        }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        (if (s.spentToday > 0) "Spent today ${Fmt.rupees(s.spentToday)} · " else "") + "Digest ${s.digestAt}",
                        style = TextStyle(color = ColorProvider(Color(0xFF7F8895)), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 220.dp)
    }
}

class RecallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecallWidget()
}
