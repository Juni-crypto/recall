package app.recall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.recall.R

/** Recall is a night app: one deliberate dark palette, matching the design mockups. */
object RC {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val Surface2 = Color(0xFF12161C)
    val Raised = Color(0xFF1C212A)
    val Line = Color(0xFF232933)
    val Line2 = Color(0xFF2A313C)
    val Text = Color(0xFFE8EAED)
    val TextHi = Color(0xFFF1F2F4)
    val Soft = Color(0xFFC9CED6)
    val Muted = Color(0xFF8A93A0)
    val Dim = Color(0xFF6B7482)
    val Accent = Color(0xFFAEB9FF)
    val AccentMid = Color(0xFF8FA2FF)
    val AccentDeep = Color(0xFF3A4690)
    val AccentSoft = Color(0xFF27305F)
    val Urgent = Color(0xFFFF6B6B)
    val UrgentSoft = Color(0xFF2A1517)
    val UrgentLine = Color(0xFF6B2C2F)
    val Wait = Color(0xFFF2B84B)
    val WaitSoft = Color(0xFF2A2112)
    val WaitLine = Color(0xFF5E4717)
    val Money = Color(0xFF4CC38A)
    val MoneySoft = Color(0xFF11251C)
    val MoneyLine = Color(0xFF1F5A3F)
}

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) = Font(
    R.font.bricolage, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun plex(weight: Int, italic: Boolean = false) = Font(
    if (italic) R.font.plex_sans_italic else R.font.plex_sans, FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Display = FontFamily(bricolage(500), bricolage(600), bricolage(700))
val Body = FontFamily(plex(400), plex(500), plex(600), plex(400, italic = true))
val Mono = FontFamily(Font(R.font.plex_mono, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))

object Type {
    val wordmark = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 38.sp, letterSpacing = (-0.8).sp, color = RC.TextHi)
    val h1 = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp, color = RC.TextHi, lineHeight = 34.sp)
    val h2 = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp, color = RC.TextHi)
    val big = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = RC.TextHi)
    val title = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = RC.TextHi)
    val body = TextStyle(fontFamily = Body, fontSize = 15.sp, lineHeight = 22.sp, color = RC.Text)
    val bodySoft = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, color = RC.Soft)
    val small = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 18.sp, color = RC.Muted)
    val italic = TextStyle(fontFamily = Body, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 23.sp, color = RC.Soft)
    val label = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = RC.Muted)
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = RC.Text)
    val button = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp)
}

private val scheme = darkColorScheme(
    primary = RC.Accent,
    onPrimary = Color(0xFF0E1116),
    primaryContainer = RC.AccentDeep,
    onPrimaryContainer = Color.White,
    secondary = RC.AccentMid,
    background = RC.Bg,
    onBackground = RC.Text,
    surface = RC.Surface,
    onSurface = RC.Text,
    surfaceVariant = RC.Raised,
    onSurfaceVariant = RC.Muted,
    surfaceContainerHigh = RC.Raised,
    surfaceContainerHighest = RC.Raised,
    surfaceContainer = RC.Surface,
    outline = RC.Line2,
    outlineVariant = RC.Line,
    error = RC.Urgent,
)

@Composable
fun RecallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            bodyLarge = Type.body, bodyMedium = Type.bodySoft, bodySmall = Type.small,
            titleLarge = Type.h2, titleMedium = Type.title, labelLarge = Type.button,
        ),
        content = content,
    )
}
