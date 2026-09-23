package app.recall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.App
import app.recall.capture.RecallListenerService
import app.recall.data.Repo
import app.recall.model.Brain
import app.recall.model.BrainState
import app.recall.ui.ask.AskScreen
import app.recall.ui.components.rememberQuery
import app.recall.ui.everything.EverythingScreen
import app.recall.ui.home.HomeScreen
import app.recall.ui.learned.LearnedScreen
import app.recall.ui.history.HistoryScreen
import app.recall.ui.models.ModelsScreen
import app.recall.ui.money.MoneyScreen
import app.recall.ui.netlog.NetLogScreen
import app.recall.ui.onboarding.OnboardingScreen
import app.recall.ui.orb.OrbMood
import app.recall.ui.orb.RecallOrb
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.ui.today.TodayScreen
import app.recall.ui.waiting.WaitingScreen
import app.recall.ui.you.YouScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab { Today, Waiting, Home, Money, You }

private enum class Overlay { Ask, Everything, Models, NetLog, Learned, History }

private data class LoopCounts(val total: Int = 0, val urgent: Int = 0)

@Composable
fun RecallRoot(tabRequests: MutableStateFlow<String?>) {
    var onboarded by remember { mutableStateOf(App.prefs.onboarded) }
    if (!onboarded) {
        OnboardingScreen(onDone = {
            App.prefs.onboarded = true
            onboarded = true
        })
        return
    }

    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var askPrefill by rememberSaveable { mutableStateOf<String?>(null) }

    val requested by tabRequests.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { r ->
            Tab.entries.firstOrNull { it.name.equals(r, ignoreCase = true) }?.let { tab = it; overlay = null }
            tabRequests.value = null
        }
    }

    val counts by rememberQuery(LoopCounts()) { Repo.openLoops().let { LoopCounts(it.size, it.count { l -> l.isUrgent }) } }
    val brain by Brain.state.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(RecallListenerService.isEnabled(context) || App.prefs.demo) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = RecallListenerService.isEnabled(context) || App.prefs.demo }

    val mood = when {
        brain != BrainState.Idle -> OrbMood.Thinking
        !permitted -> OrbMood.Off
        counts.urgent > 0 -> OrbMood.Urgent
        counts.total > 0 -> OrbMood.Needs
        else -> OrbMood.Calm
    }

    BackHandler(enabled = overlay != null) { overlay = null }
    BackHandler(enabled = overlay == null && tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize().background(RC.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Home -> HomeScreen(
                        mood = mood,
                        capturing = permitted,
                        onAsk = { q -> askPrefill = q; overlay = Overlay.Ask },
                        onFixCapture = { Health.openNotificationAccess(context) },
                        onLearned = { overlay = Overlay.Learned },
                    )
                    Tab.Today -> TodayScreen(
                        onWaiting = { tab = Tab.Waiting },
                        onMoney = { tab = Tab.Money },
                        onEverything = { overlay = Overlay.Everything },
                    )
                    Tab.Waiting -> WaitingScreen()
                    Tab.Money -> MoneyScreen()
                    Tab.You -> YouScreen(
                        onModels = { overlay = Overlay.Models },
                        onNetLog = { overlay = Overlay.NetLog },
                        onLearned = { overlay = Overlay.Learned },
                        onHistory = { overlay = Overlay.History },
                    )
                }
            }
            BottomBar(tab, mood, counts, onSelect = { tab = it })
        }

        AnimatedVisibility(
            visible = overlay != null,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
        ) {
            // Keep drawing the last overlay while it animates out.
            val last = remember { mutableStateOf<Overlay?>(null) }
            if (overlay != null) last.value = overlay
            when (last.value) {
                Overlay.Ask -> AskScreen(askPrefill, onBack = { overlay = null; askPrefill = null }, onModels = { overlay = Overlay.Models })
                Overlay.Everything -> EverythingScreen(onBack = { overlay = null })
                Overlay.Models -> ModelsScreen(onBack = { overlay = null })
                Overlay.NetLog -> NetLogScreen(onBack = { overlay = null })
                Overlay.Learned -> LearnedScreen(onBack = { overlay = null }, onHistory = { overlay = Overlay.History })
                Overlay.History -> HistoryScreen(onBack = { overlay = null })
                null -> Unit
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, mood: OrbMood, counts: LoopCounts, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(RC.Surface2).navigationBarsPadding()) {
        HorizontalDivider(color = RC.Line)
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("Today", Icons.Outlined.WbTwilight, tab == Tab.Today) { onSelect(Tab.Today) }
            NavItem("Waiting", Icons.Outlined.HourglassEmpty, tab == Tab.Waiting, badge = counts.total.takeIf { it > 0 }) { onSelect(Tab.Waiting) }
            Column(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onSelect(Tab.Home) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Still in the tab bar: an always-animating orb on every screen costs battery.
                RecallOrb(mood, Modifier.size(52.dp).offset(y = (-6).dp), animate = false)
                Text("Recall", style = Type.label.copy(color = if (tab == Tab.Home) RC.Accent else RC.Muted, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), modifier = Modifier.offset(y = (-8).dp))
            }
            NavItem("Money", Icons.Outlined.CurrencyRupee, tab == Tab.Money) { onSelect(Tab.Money) }
            NavItem("You", Icons.Outlined.PersonOutline, tab == Tab.You) { onSelect(Tab.You) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String, icon: ImageVector, selected: Boolean, badge: Int? = null, onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            Icon(icon, label, tint = if (selected) RC.Accent else RC.Muted, modifier = Modifier.size(22.dp))
            if (badge != null) {
                Text(
                    badge.toString(),
                    style = Type.label.copy(color = RC.Bg, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp).clip(CircleShape).background(RC.Wait).padding(horizontal = 5.dp),
                )
            }
        }
        Text(label, style = Type.label.copy(color = if (selected) RC.Accent else RC.Muted, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified))
    }
}
