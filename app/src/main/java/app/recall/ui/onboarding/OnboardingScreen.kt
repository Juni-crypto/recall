package app.recall.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.recall.App
import app.recall.capture.SmsReader
import app.recall.digest.DigestScheduler
import kotlinx.coroutines.launch
import app.recall.ui.Health
import app.recall.ui.components.BigButton
import app.recall.ui.components.RCard
import app.recall.ui.models.modelsContent
import app.recall.ui.orb.OrbMood
import app.recall.ui.orb.RecallOrb
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type

private const val STEPS = 6

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checks++
        App.scope.launch { SmsReader.sync(context) }
    }

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF171B3A), RC.Bg), radius = 1500f))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(STEPS) { i ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(if (i == step) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                            .background(if (i <= step) RC.AccentMid else RC.Line2),
                    )
                }
            }
            @Suppress("UNUSED_VARIABLE") val recheck = checks
            when (step) {
                0 -> Page(
                    orb = OrbMood.Needs,
                    title = "Recall",
                    body = "Recall reads every notification on this phone, learns what matters to you, and every night tells you who's waiting on you, what was urgent and where your money went.\n\nEverything, including the AI, stays on this phone.",
                    button = "Get started", onNext = { step++ },
                )
                1 -> {
                    val ok = Health.notificationAccess(context)
                    Page(
                        orb = if (ok) OrbMood.Calm else OrbMood.Off,
                        title = "Let Recall read notifications",
                        body = "This is how Recall sees your messages, calls, bank alerts and everything else. Nothing is filtered and nothing leaves the phone.\n\nOn the next screen, turn on Recall.",
                        status = if (ok) "Notification access is on" else null,
                        button = if (ok) "Next" else "Allow notification access",
                        onNext = { if (ok) step++ else Health.openNotificationAccess(context) },
                        skip = if (ok) null else ({ step++ }),
                    )
                }
                2 -> {
                    val ok = Health.batteryUnrestricted(context)
                    Page(
                        orb = if (ok) OrbMood.Calm else OrbMood.Needs,
                        title = "Keep Recall running",
                        body = "Phones like OnePlus, Samsung and Xiaomi stop background apps to save battery, and Recall would miss notifications.\n\nAllow Recall to run in the background. On OnePlus you can also set App info → Battery → Unrestricted.",
                        status = if (ok) "Background running is allowed" else null,
                        button = if (ok) "Next" else "Allow background running",
                        onNext = { if (ok) step++ else Health.openBattery(context) },
                        skip = { step++ },
                        extra = if (!ok) ("Open app battery settings" to { Health.openAppDetails(context) }) else null,
                    )
                }
                3 -> {
                    val notifOk = Health.postNotifications(context)
                    val alarmOk = Health.exactAlarms(context)
                    Page(
                        orb = OrbMood.Needs,
                        title = "Your 9:30 PM digest",
                        body = "Every night Recall sends one notification: who's waiting on you, what was urgent, and what you spent. You can change the time later.",
                        status = listOfNotNull(
                            if (notifOk) "Notifications are on" else null,
                            if (alarmOk) "Digest will arrive on time" else null,
                        ).joinToString("\n").ifEmpty { null },
                        button = when {
                            !notifOk -> "Allow notifications"
                            !alarmOk -> "Allow exact timing"
                            else -> "Next"
                        },
                        onNext = {
                            when {
                                !notifOk && Build.VERSION.SDK_INT >= 33 -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                !alarmOk -> Health.openExactAlarms(context)
                                else -> {
                                    DigestScheduler.scheduleNext(context)
                                    step++
                                }
                            }
                        },
                        skip = { DigestScheduler.scheduleNext(context); step++ },
                    )
                }
                4 -> {
                    val ok = SmsReader.granted(context)
                    Page(
                        orb = if (ok) OrbMood.Calm else OrbMood.Needs,
                        title = "Read bank SMS",
                        body = "Android hides some bank alerts from notification readers because they look like one-time codes. " +
                            "With SMS access, Recall reads them straight from your SMS, including the last 30 days, so your spending is complete from day one.\n\nOptional. Stays on this phone.",
                        status = if (ok) "SMS access is on" else null,
                        button = if (ok) "Next" else "Allow SMS access",
                        onNext = {
                            if (ok) step++ else smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        },
                        skip = if (ok) null else ({ step++ }),
                    )
                }
                else -> Column(Modifier.weight(1f)) {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        modelsContent()
                    }
                    Column(Modifier.padding(16.dp)) {
                        BigButton("Done", onClick = onDone)
                        Text(
                            "You can get a model later from You → Model. Recall starts capturing right away.",
                            style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Page(
    orb: OrbMood,
    title: String,
    body: String,
    button: String,
    onNext: () -> Unit,
    status: String? = null,
    skip: (() -> Unit)? = null,
    extra: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        RecallOrb(orb, Modifier.size(230.dp))
        Text(title, style = if (title == "Recall") Type.wordmark else Type.h1, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(body, style = Type.bodySoft, textAlign = TextAlign.Center)
        if (status != null) {
            Spacer(Modifier.height(16.dp))
            RCard { Text(status, style = Type.body.copy(color = RC.Money), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(button, onClick = onNext)
        extra?.let { (label, action) ->
            Text(label, style = Type.button.copy(color = RC.Accent), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = action))
        }
        if (skip != null) {
            Text("Skip for now", style = Type.button.copy(color = RC.Muted), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = skip))
        }
    }
}
