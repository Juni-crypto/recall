package app.recall.ui.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recall.App
import app.recall.data.Repo
import app.recall.data.Txn
import app.recall.learn.MerchantClassifier
import app.recall.model.Brain
import app.recall.understand.Categories
import app.recall.ui.components.FilterChip
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private enum class Period(val label: String) { Day("Day"), Week("Week"), Month("Month"), Year("Year"), All("All time") }
private enum class View(val label: String) { Spending("Spending"), Received("Received"), Invested("Invested") }
private enum class Show(val label: String) { All("All"), Spent("Spent"), Received("Received"), Savings("Savings") }

private val CAT_COLORS = mapOf(
    "Food" to Color(0xFFF2B84B), "Groceries" to Color(0xFF4CC38A), "Travel" to Color(0xFF5FB3F9),
    "Fuel" to Color(0xFFFF7A7C), "Shopping" to Color(0xFFC08BFF), "Subscriptions" to Color(0xFF8FA2FF),
    "Bills" to Color(0xFFF59E6B), "Health" to Color(0xFF5ED3C4), "Rent" to Color(0xFFE6C07B),
    "Education" to Color(0xFF9AD06B), "Entertainment" to Color(0xFFF07CC0), "People" to Color(0xFFAEB5C0),
    "Other" to Color(0xFF6B7482),
)

private fun catColor(c: String) = CAT_COLORS[c] ?: Color(0xFF6B7482)

private data class Window(val start: LocalDate, val end: LocalDate, val label: String, val prevLabel: String, val isCurrent: Boolean)

private data class MoneyData(
    val window: Window? = null,
    val spent: Long = 0,
    val received: Long = 0,
    val savings: Long = 0,
    val prevSpent: Long = 0,
    val current: List<Long> = emptyList(),   // cumulative spend per bucket, up to now
    val previous: List<Long> = emptyList(),  // cumulative spend per bucket, whole previous period
    val ticks: List<String> = emptyList(),
    val categories: List<Triple<String, Long, Long>> = emptyList(), // name, now, before
    val merchants: List<Triple<String, Long, Int>> = emptyList(),
    val txns: List<Txn> = emptyList(),
    val facts: List<String> = emptyList(),
    val unsorted: Int = 0,
    val payers: List<Triple<String, Long, Int>> = emptyList(),
    val types: List<Triple<String, Long, Long>> = emptyList(), // type, put in, came back
    val invested: Long = 0,
    val returned: Long = 0,
    val ownMoves: Long = 0,
    val allInvested: Long = 0,
    val allReturned: Long = 0,
    val salary: Long = 0,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoneyScreen() {
    var view by rememberSaveable { mutableStateOf(View.Spending) }
    var period by rememberSaveable { mutableStateOf(Period.Month) }
    var detail by remember { mutableStateOf<Txn?>(null) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    var show by rememberSaveable { mutableStateOf(Show.All) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var sorting by remember { mutableStateOf(false) }
    val hasModel = remember { Brain.available() }
    val d by rememberQuery(MoneyData(), period, back, view) { load(period, back, view) }
    fun money(p: Long) = if (hidden) "₹ ••••" else Fmt.rupees(p)

    editing?.let { t -> CategoryPicker(t, onDismiss = { editing = null }) }
    detail?.let { t -> PaymentSheet(t, hidden, onDismiss = { detail = null }, onCategory = { editing = t }) }

    val shown = d.txns.filter { t ->
        val kind = if (view != View.Spending) true else when (show) {
            Show.All -> true
            Show.Spent -> t.isOut && !Categories.isMovement(t.category)
            Show.Received -> !t.isOut && !Categories.isMovement(t.category)
            Show.Savings -> Categories.isMovement(t.category)
        }
        kind && (category == null || t.category == category)
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title, privacy, period
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Money", style = Type.h1, modifier = Modifier.weight(1f))
                IconButton(onClick = { hidden = !hidden }) {
                    Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (hidden) "Show amounts" else "Hide amounts", tint = RC.Muted)
                }
            }
            ViewSwitch(view) { view = it; category = null; show = Show.All }
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Period.entries.forEach { p -> FilterChip(p.label, p == period) { period = p; back = 0; category = null } }
            }
        }
        if (period != Period.All) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back++; category = null }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Earlier", tint = RC.Soft) }
                Text(d.window?.label ?: "", style = Type.title, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (back > 0) { back--; category = null } }, enabled = back > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Later", tint = if (back > 0) RC.Soft else RC.Line2)
                }
            }
        }

        // Headline numbers
        item {
            RCard {
                val (big, label) = when (view) {
                    View.Spending -> d.spent to "Spent"
                    View.Received -> d.received to "Received"
                    View.Invested -> d.invested to "Put into savings & investments"
                }
                val before = if (view == View.Spending) d.prevSpent else -1
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = Type.small)
                        Text(money(big), style = Type.big)
                    }
                    val change = if (before >= 0) pct(big, before) else null
                    if (change != null && !hidden) {
                        Pill(
                            (if (change >= 0) "↑ " else "↓ ") + "${abs(change)}% vs ${d.window?.prevLabel}",
                            if (change > 10) Tone.Urgent else if (change < -10) Tone.Money else Tone.Neutral,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    when (view) {
                        View.Spending -> {
                            Stat("Received", money(d.received), RC.Money, Modifier.weight(1f))
                            val net = d.received - d.spent
                            Stat("Net", if (hidden) "₹ ••••" else (if (net >= 0) "+" else "−") + Fmt.rupees(abs(net)), if (net >= 0) RC.Money else RC.Urgent, Modifier.weight(1f))
                            Stat("To savings", money(d.invested), RC.Accent, Modifier.weight(1f))
                        }
                        View.Received -> {
                            Stat("From", "${d.payers.size} ${if (d.payers.size == 1) "source" else "sources"}", RC.Soft, Modifier.weight(1f))
                            Stat("Salary", money(d.salary), RC.Money, Modifier.weight(1f))
                            Stat("Payments in", "${d.txns.size}", RC.Soft, Modifier.weight(1f))
                        }
                        View.Invested -> {
                            Stat("Came back", money(d.returned), RC.Money, Modifier.weight(1f))
                            Stat("Net flow", signed(d.invested - d.returned, hidden), RC.Accent, Modifier.weight(1f))
                            Stat("Self transfers", money(d.ownMoves), RC.Soft, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (view == View.Invested) item {
            RCard {
                Label("All time, from your messages")
                Row(Modifier.fillMaxWidth()) {
                    Stat("Put in", money(d.allInvested), RC.Text, Modifier.weight(1f))
                    Stat("Came back", money(d.allReturned), RC.Money, Modifier.weight(1f))
                    Stat("Net flow*", signed(d.allInvested - d.allReturned, hidden), RC.Accent, Modifier.weight(1f))
                }
                Text(
                    "*Put in minus came back, as seen in your bank messages since ${d.window?.start?.year ?: ""}. " +
                        "It goes negative when deposits renew, earn interest, or were opened before your messages start. It isn't today's market value.",
                    style = Type.small.copy(color = RC.Dim),
                )
            }
        }

        // Comparison chart
        item {
            RCard {
                val top = max(d.current.maxOrNull() ?: 0, d.previous.maxOrNull() ?: 0)
                val what = when (view) { View.Spending -> "Spent"; View.Received -> "Received"; View.Invested -> "Put in" }
                Label(
                    if (period == Period.All) "$what over time" else "$what · this ${period.label.lowercase()} vs ${d.window?.prevLabel ?: "before"}",
                    trailing = if (!hidden && top > 0) "max ${Fmt.rupees(top)}" else null,
                )
                CompareChart(d.current, d.previous, d.ticks)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(RC.Accent, d.window?.let { if (it.isCurrent) "So far" else it.label } ?: "")
                    if (period != Period.All) Legend(RC.Dim, d.window?.prevLabel?.replaceFirstChar(Char::uppercase) ?: "", dashed = true)
                }
                if (!hidden) d.facts.forEach { Text(it, style = Type.small.copy(color = RC.Soft)) }
            }
        }

        // Received: from who
        if (view == View.Received && d.payers.isNotEmpty()) item {
            RCard {
                Label("From who", trailing = "tap for all payments")
                val top = d.payers.first().second.coerceAtLeast(1)
                d.payers.forEach { (who, amount, count) ->
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { d.txns.firstOrNull { it.merchant == who }?.let { detail = it } }.padding(vertical = 5.dp, horizontal = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(who, style = Type.bodySoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$count×  ", style = Type.small)
                            Text(money(amount), style = Type.mono.copy(color = RC.Money))
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp).clip(RoundedCornerShape(2.dp)).background(RC.Raised)) {
                            Box(Modifier.fillMaxWidth((amount.toFloat() / top).coerceIn(0.02f, 1f)).fillMaxHeight().background(RC.Money))
                        }
                    }
                }
            }
        }

        // Invested: by type
        if (view == View.Invested && d.types.isNotEmpty()) item {
            RCard {
                Label("By type", trailing = "put in · came back")
                d.types.forEach { (type, put, back2) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(type, style = Type.bodySoft, modifier = Modifier.weight(1f))
                        Text(money(put), style = Type.mono)
                        Text("  ·  ", style = Type.small)
                        Text(money(back2), style = Type.mono.copy(color = RC.Money))
                    }
                }
            }
        }

        // Categories
        if (view == View.Spending && d.categories.isNotEmpty()) item {
            RCard {
                Label("Where it went", trailing = if (category != null) "tap again to clear" else "tap to filter")
                ShareBar(d.categories)
                d.categories.forEach { (cat, now, before) ->
                    val selected = category == cat
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected) RC.AccentSoft else Color.Transparent)
                            .clickable { category = if (selected) null else cat; show = Show.All }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(cat)))
                        Text(cat, style = Type.bodySoft, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        val c = pct(now, before)
                        if (!hidden) when {
                            before < 50_000 && now > 0 -> Text("new", style = Type.small.copy(color = RC.Dim), modifier = Modifier.padding(end = 10.dp))
                            c != null && abs(c) <= 999 -> Text((if (c >= 0) "↑" else "↓") + "${abs(c)}%", style = Type.small.copy(color = if (c > 0) RC.Urgent else RC.Money), modifier = Modifier.padding(end = 10.dp))
                        }
                        Text(money(now), style = Type.mono)
                    }
                }
            }
        }

        // Top merchants
        if (view == View.Spending && d.merchants.isNotEmpty()) item {
            RCard {
                Label("Top places")
                val top = d.merchants.first().second.coerceAtLeast(1)
                d.merchants.forEach { (m, amount, count) ->
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { d.txns.firstOrNull { it.merchant == m }?.let { detail = it } }.padding(vertical = 3.dp, horizontal = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m, style = Type.bodySoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$count×  ", style = Type.small)
                            Text(money(amount), style = Type.mono)
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp).clip(RoundedCornerShape(2.dp)).background(RC.Raised)) {
                            Box(Modifier.fillMaxWidth((amount.toFloat() / top).coerceIn(0.02f, 1f)).fillMaxHeight().background(RC.AccentMid))
                        }
                    }
                }
            }
        }

        if (view == View.Spending && d.unsorted > 0 && hasModel) item {
            RCard(highlight = true) {
                Text("${d.unsorted} merchant${if (d.unsorted == 1) "" else "s"} without a category", style = Type.title)
                Text("Recall can sort them with its on-device model. Only the label changes, never the amount.", style = Type.small)
                SmallButton(if (sorting) "Sorting…" else "Sort with ${Brain.activeName() ?: "the model"}", primary = true, enabled = !sorting) {
                    sorting = true
                    App.scope.launch { MerchantClassifier.run(); sorting = false }
                }
            }
        }

        // Transactions
        if (view == View.Spending) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Show.entries.forEach { s -> FilterChip(s.label, s == show) { show = s } }
                if (category != null) FilterChip("$category ✕", true) { category = null }
            }
        }
        if (shown.isEmpty()) item {
            RCard {
                Text("No payments here.", style = Type.title)
                Text("Recall reads bank SMS, UPI apps and card alerts. When the same payment arrives twice, it's counted once.", style = Type.small)
            }
        } else item {
            RCard {
                shown.take(200).forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = RC.Line)
                    TxnRow(t, hidden, onClick = { detail = t })
                }
                if (shown.size > 200) Text("Showing the latest 200 of ${shown.size}.", style = Type.small)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = Type.small)
        Text(value, style = Type.mono.copy(color = color), maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null, cap = StrokeCap.Round,
            )
        }
        Text(label, style = Type.small, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Running total this period (solid, filled) against the previous one (dashed). */
@Composable
private fun CompareChart(current: List<Long>, previous: List<Long>, ticks: List<String>) {
    val n = max(current.size, previous.size).coerceAtLeast(2)
    val top = max(current.maxOrNull() ?: 0, previous.maxOrNull() ?: 0).coerceAtLeast(1L).toFloat()
    val accent = RC.Accent
    val grid = RC.Line
    val dim = RC.Dim
    val bg = RC.Bg
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * i / (n - 1).toFloat()
        fun y(v: Long) = h - (v / top) * h * 0.92f
        for (k in 1..3) {
            val gy = h * k / 4f
            drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }
        if (previous.size >= 2) {
            val p = Path().apply { previous.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            drawPath(p, dim, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)), cap = StrokeCap.Round))
        }
        if (current.isNotEmpty()) {
            val line = Path().apply { current.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val area = Path().apply {
                addPath(line)
                lineTo(x(current.lastIndex), h)
                lineTo(x(0), h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f))))
            drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            val end = Offset(x(current.lastIndex), y(current.last()))
            drawCircle(bg, 6.dp.toPx(), end)
            drawCircle(accent, 4.dp.toPx(), end)
        }
    }
    if (ticks.isNotEmpty()) {
        Row(Modifier.fillMaxWidth()) {
            ticks.forEachIndexed { i, t ->
                Text(
                    t, style = Type.label.copy(color = RC.Dim),
                    textAlign = when (i) { 0 -> TextAlign.Start; ticks.lastIndex -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShareBar(categories: List<Triple<String, Long, Long>>) {
    val total = categories.sumOf { it.second }.coerceAtLeast(1L).toFloat()
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(RC.Raised)) {
        categories.forEach { (cat, now, _) ->
            val f = now / total
            if (f > 0.005f) Box(Modifier.weight(f).fillMaxHeight().background(catColor(cat)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(t: Txn, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.merchant ?: "Payment", style = Type.h2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pick a category. Recall will use it for every payment to this merchant.", style = Type.small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (Categories.ALL + Categories.SAVINGS + Categories.SELF).forEach { cat ->
                        FilterChip(cat, cat == t.category) {
                            App.scope.launch { Repo.setMerchantCategory(t.merchant!!, cat, "user") }
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TxnRow(t: Txn, hidden: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (Categories.isMovement(t.category)) RC.AccentDeep else catColor(t.category)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                t.merchant ?: t.category.takeIf { it != "Other" && it != "Received" } ?: if (t.isOut) "Payment" else "Money in",
                style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${t.category} · ${t.sources.joinToString(" + ")} · ${Time.relative(t.at)}",
                    style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (t.sources.size > 1) Pill("merged")
            }
        }
        Text(
            if (hidden) "••••" else (if (t.isOut) "−" else "+") + Fmt.rupees(t.amountPaise),
            style = Type.mono.copy(color = if (t.isOut) RC.Text else RC.Money),
        )
    }
}

// ------------------------------------------------------------------ data

private fun pct(now: Long, before: Long): Int? =
    if (before <= 0 || now <= 0) null else ((now - before) * 100 / before).toInt()

private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun window(period: Period, back: Int, today: LocalDate): Window = when (period) {
    Period.Day -> {
        val d = today.minusDays(back.toLong())
        Window(
            d, d.plusDays(1),
            when (back) { 0 -> "Today"; 1 -> "Yesterday"; else -> Time.shortDate(d) },
            if (back == 0) "yesterday" else "the day before", back == 0,
        )
    }
    Period.Week -> {
        val s = today.with(DayOfWeek.MONDAY).minusWeeks(back.toLong())
        Window(s, s.plusWeeks(1), if (back == 0) "This week" else "${s.format(SHORT)} – ${s.plusDays(6).format(SHORT)}", "last week", back == 0)
    }
    Period.Month -> {
        val s = today.withDayOfMonth(1).minusMonths(back.toLong())
        Window(s, s.plusMonths(1), s.format(MONTH), s.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), back == 0)
    }
    Period.Year -> {
        val s = today.withDayOfYear(1).minusYears(back.toLong())
        Window(s, s.plusYears(1), s.year.toString(), (s.year - 1).toString(), back == 0)
    }
    Period.All -> {
        val first = Repo.firstTxnAt()?.let { Time.date(it) } ?: today.minusMonths(1)
        Window(first.withDayOfMonth(1), today.plusDays(1), "All time", "", true)
    }
}

private fun previousStart(period: Period, start: LocalDate): LocalDate = when (period) {
    Period.Day -> start.minusDays(1)
    Period.Week -> start.minusWeeks(1)
    Period.Month -> start.minusMonths(1)
    Period.Year -> start.minusYears(1)
    Period.All -> start
}

/** Buckets for the chart: hours of a day, days of a week/month, months of a year or of all time. */
private fun buckets(period: Period, start: LocalDate, end: LocalDate): List<Pair<Long, Long>> = when (period) {
    Period.Day -> (0 until 24).map { h -> Time.startOf(start) + h * 3_600_000L to Time.startOf(start) + (h + 1) * 3_600_000L }
    Period.Week, Period.Month -> (0 until ChronoUnit.DAYS.between(start, end).toInt()).map { i ->
        Time.startOf(start.plusDays(i.toLong())) to Time.startOf(start.plusDays(i + 1L))
    }
    Period.Year -> (0 until 12).map { m -> Time.startOf(start.plusMonths(m.toLong())) to Time.startOf(start.plusMonths(m + 1L)) }
    Period.All -> (0..ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1)).toInt()).map { m ->
        Time.startOf(start.plusMonths(m.toLong())) to Time.startOf(start.plusMonths(m + 1L))
    }
}

private fun cumulative(txns: List<Txn>, b: List<Pair<Long, Long>>, upTo: Long): List<Long> {
    var run = 0L
    val out = mutableListOf<Long>()
    for ((s, e) in b) {
        if (s > upTo) break
        run += txns.filter { it.at in s until e }.sumOf { it.amountPaise }
        out += run
    }
    return out
}

private val YEAR_MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

private fun ticks(period: Period, start: LocalDate, count: Int): List<String> = when (period) {
    Period.Day -> listOf("12 AM", "6 AM", "12 PM", "6 PM", "11 PM")
    Period.Week -> DayOfWeek.entries.map { it.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) }
    Period.Month -> listOf("1", "8", "15", "22", count.toString())
    Period.Year -> listOf("Jan", "Apr", "Jul", "Oct", "Dec")
    Period.All -> (0 until 4).map { i -> start.plusMonths((count - 1L) * i / 3).format(YEAR_MONTH) }
}

private fun isSpend(t: Txn) = t.isOut && !Categories.isMovement(t.category)
private fun isIncome(t: Txn) = !t.isOut && !Categories.isMovement(t.category)
private fun isInvest(t: Txn) = t.isOut && t.category == Categories.SAVINGS
private fun isReturn(t: Txn) = !t.isOut && t.category == Categories.SAVINGS

private fun load(period: Period, back: Int, view: View): MoneyData {
    val today = Time.today()
    val now = Time.now()
    val w = window(period, back, today)
    val start = Time.startOf(w.start)
    val end = Time.startOf(w.end)
    val upTo = if (w.isCurrent) now else end
    val prevStartDate = previousStart(period, w.start)
    val prevStart = Time.startOf(prevStartDate)
    // Compare like with like: the previous period up to the same point.
    val prevCut = prevStart + (minOf(upTo, end) - start)

    val all = Repo.txnsBetween(start, end)
    val prevAll = if (period == Period.All) emptyList() else Repo.txnsBetween(prevStart, start)
    val focus: (Txn) -> Boolean = when (view) {
        View.Spending -> ::isSpend
        View.Received -> ::isIncome
        View.Invested -> { t -> Categories.isMovement(t.category) }
    }
    val series: (Txn) -> Boolean = when (view) {
        View.Spending -> ::isSpend
        View.Received -> ::isIncome
        View.Invested -> ::isInvest
    }

    val spent = all.filter(::isSpend).sumOf { it.amountPaise }
    val received = all.filter(::isIncome).sumOf { it.amountPaise }
    val invested = all.filter(::isInvest).sumOf { it.amountPaise }
    val returned = all.filter(::isReturn).sumOf { it.amountPaise }
    val ownMoves = all.filter { it.isOut && it.category == Categories.SELF }.sumOf { it.amountPaise }
    val prevSpent = prevAll.filter(::isSpend).filter { it.at < prevCut }.sumOf { it.amountPaise }

    val bNow = buckets(period, w.start, w.end)
    val current = cumulative(all.filter(series), bNow, upTo)
    val previous = if (period == Period.All) emptyList() else cumulative(prevAll.filter(series), buckets(period, prevStartDate, w.start), Long.MAX_VALUE)

    val spends = all.filter(::isSpend)
    val cats = spends.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }
    val prevCats = prevAll.filter(::isSpend).filter { it.at < prevCut }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }
    val categories = cats.entries.sortedByDescending { it.value }.map { Triple(it.key, it.value, prevCats[it.key] ?: 0L) }
    val merchants = spends.filter { it.merchant != null }.groupBy { it.merchant!! }
        .map { (m, l) -> Triple(m, l.sumOf { it.amountPaise }, l.size) }.sortedByDescending { it.second }.take(8)

    val payers = all.filter(::isIncome).groupBy { it.merchant ?: "Unknown sender" }
        .map { (m, l) -> Triple(m, l.sumOf { it.amountPaise }, l.size) }.sortedByDescending { it.second }.take(25)
    val salary = all.filter { isIncome(it) && it.category == "Salary" }.sumOf { it.amountPaise }

    val savings = all.filter { Categories.isMovement(it.category) }
    val types = savings.groupBy { if (it.category == Categories.SELF) "Between your accounts" else Categories.investmentType(it.raw, it.merchant) }
        .map { (type, l) -> Triple(type, l.filter { it.isOut }.sumOf { it.amountPaise }, l.filter { !it.isOut }.sumOf { it.amountPaise }) }
        .sortedByDescending { it.second + it.third }

    val lifetime = if (view == View.Invested) Repo.txnsBetween(0, Long.MAX_VALUE) else emptyList()

    val facts = mutableListOf<String>()
    val chosen = all.filter(series)
    val total = chosen.sumOf { it.amountPaise }
    if (chosen.isNotEmpty() && period != Period.Day) {
        val daysSoFar = max(1L, ChronoUnit.DAYS.between(w.start, if (w.isCurrent) today.plusDays(1) else w.end))
        val verb = when (view) { View.Spending -> "payments"; View.Received -> "payments in"; View.Invested -> "transfers" }
        facts += "Daily average ${Fmt.rupees(total / daysSoFar)} · ${chosen.size} $verb"
        if (view == View.Spending && period == Period.Month && w.isCurrent) {
            val days = ChronoUnit.DAYS.between(w.start, w.end)
            if (daysSoFar in 5 until days) facts += "On pace for ${Fmt.rupees(total * days / daysSoFar)} this month"
        }
        chosen.maxByOrNull { it.amountPaise }?.let { t ->
            facts += "Largest: ${Fmt.rupees(t.amountPaise)}${t.merchant?.let { " · $it" } ?: ""} on ${Time.date(t.at).format(SHORT)}"
        }
    }

    return MoneyData(
        window = w, spent = spent, received = received, savings = invested, prevSpent = prevSpent,
        current = current, previous = previous, ticks = ticks(period, w.start, bNow.size),
        categories = categories, merchants = merchants, txns = all.filter(focus).take(500), facts = facts,
        unsorted = if (view == View.Spending) Repo.uncategorisedMerchants(500).size else 0,
        payers = payers, types = types, invested = invested, returned = returned, ownMoves = ownMoves,
        allInvested = lifetime.filter(::isInvest).sumOf { it.amountPaise },
        allReturned = lifetime.filter(::isReturn).sumOf { it.amountPaise },
        salary = salary,
    )
}

// ------------------------------------------------------------------ pieces

@Composable
private fun ViewSwitch(view: View, onSelect: (View) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(RC.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        View.entries.forEach { v ->
            val on = v == view
            Text(
                v.label,
                style = Type.button.copy(color = if (on) Color.White else RC.Muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) RC.AccentDeep else Color.Transparent)
                    .clickable { onSelect(v) }.padding(vertical = 9.dp),
            )
        }
    }
}

private data class Counterpart(
    val all: List<Txn> = emptyList(),
    val paid: Long = 0, val paidCount: Int = 0,
    val got: Long = 0, val gotCount: Int = 0,
    val months: List<Pair<String, Long>> = emptyList(),
    val recurring: String? = null,
)

/** Everything with one person or merchant: totals, a year of months, every payment, the message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(t: Txn, hidden: Boolean, onDismiss: () -> Unit, onCategory: () -> Unit) {
    fun money(p: Long) = if (hidden) "₹ ••••" else Fmt.rupees(p)
    val name = t.merchant
    val c by rememberQuery(Counterpart(), name, t.id) {
        val list = if (name != null) Repo.txnsForMerchant(name) else listOf(t)
        val today = Time.today().withDayOfMonth(1)
        val months = (11 downTo 0).map { i ->
            val m = today.minusMonths(i.toLong())
            val s = Time.startOf(m); val e = Time.startOf(m.plusMonths(1))
            m.month.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) to list.filter { it.at in s until e }.sumOf { it.amountPaise }
        }
        Counterpart(
            recurring = recurring(list),
            all = list,
            paid =
            list.filter { it.isOut }.sumOf { it.amountPaise }, paidCount = list.count { it.isOut },
            got = list.filter { !it.isOut }.sumOf { it.amountPaise }, gotCount = list.count { !it.isOut },
            months = months,
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = RC.Surface) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(name ?: (if (t.isOut) "Payment" else "Money in"), style = Type.h1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(t.category)))
                    Text(t.category, style = Type.bodySoft)
                    if (name != null) SmallButton("Change category", onClick = onCategory)
                    if (name != null && t.category != Categories.SELF) SmallButton("This is me") {
                        App.scope.launch { app.recall.learn.OwnerName.add(name) }
                        onDismiss()
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Stat("You paid", money(c.paid), RC.Text, Modifier.weight(1f))
                    Stat("You got", money(c.got), RC.Money, Modifier.weight(1f))
                    Stat("Payments", "${c.paidCount + c.gotCount}", RC.Soft, Modifier.weight(1f))
                }
                c.recurring?.let {
                    Text("↻ $it", style = Type.body.copy(color = RC.Accent), modifier = Modifier.padding(top = 8.dp))
                }
                if (c.all.isNotEmpty()) {
                    Text(
                        "Since ${Time.date(c.all.last().at).format(SHORT)} ${Time.date(c.all.last().at).year} · last ${Time.relative(c.all.first().at)}",
                        style = Type.small, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (name != null) item {
                Label("Last 12 months")
                val top = (c.months.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
                Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                    c.months.forEach { (_, v) ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight((v.toFloat() / top).coerceIn(0.03f, 1f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (v > 0) RC.AccentMid else RC.Line),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    c.months.forEach { (m, _) -> Text(m, style = Type.label.copy(color = RC.Dim), textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                }
            }
            item {
                Label("This payment")
                Text(
                    (if (hidden) "••••" else (if (t.isOut) "−" else "+") + Fmt.rupees(t.amountPaise)) + " · " + Time.relative(t.at) + " · " + t.sources.joinToString(" + "),
                    style = Type.title, modifier = Modifier.padding(vertical = 4.dp),
                )
                t.raw?.let {
                    Text(
                        if (hidden) "Message hidden" else it,
                        style = Type.mono.copy(color = RC.Soft),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(RC.Raised).padding(12.dp),
                    )
                }
            }
            if (c.all.size > 1) {
                item { Label("All payments", trailing = "${c.all.size}") }
                items(c.all.take(150), key = { it.id }) { x ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${Time.date(x.at).format(SHORT)} ${Time.date(x.at).year}", style = Type.small, modifier = Modifier.weight(1f))
                        Text(
                            if (hidden) "••••" else (if (x.isOut) "−" else "+") + Fmt.rupees(x.amountPaise),
                            style = Type.mono.copy(color = if (x.isOut) RC.Text else RC.Money),
                        )
                    }
                }
            }
        }
    }
}

/** "Repeats monthly, usually around the 5th, about ₹22,500" when payments look like an EMI, rent or SIP. */
private fun recurring(list: List<Txn>): String? {
    for (out in listOf(true, false)) {
        val xs = list.filter { it.isOut == out }.sortedBy { it.at }
        if (xs.size < 3) continue
        val gaps = xs.zipWithNext { a, b -> (b.at - a.at) / Repo.DAY }
        val monthly = gaps.count { it in 25..35 }
        val amounts = xs.map { it.amountPaise }.sorted()
        val median = amounts[amounts.size / 2]
        val steady = xs.count { abs(it.amountPaise - median) <= median / 5 }
        if (monthly >= gaps.size * 0.6 && steady >= xs.size * 0.7) {
            val day = xs.map { Time.date(it.at).dayOfMonth }.sorted().let { it[it.size / 2] }
            val verb = if (out) "You pay" else "You receive"
            return "$verb this monthly, usually around the ${Fmt.ordinal(day)}, about ${Fmt.rupees(median)}"
        }
    }
    return null
}

/** "+₹1,200" / "−₹1,200" */
private fun signed(p: Long, hidden: Boolean) =
    if (hidden) "₹ ••••" else (if (p >= 0) "+" else "−") + Fmt.rupees(abs(p))
