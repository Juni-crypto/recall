package app.recall.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recall.App
import app.recall.model.Brain
import app.recall.model.Edition
import app.recall.model.InstalledModel
import app.recall.model.ModelCatalog
import app.recall.model.ModelSpec
import app.recall.model.ModelStore
import app.recall.ui.components.Label
import app.recall.ui.components.Pill
import app.recall.ui.components.RCard
import app.recall.ui.components.SmallButton
import app.recall.ui.components.Tone
import app.recall.ui.theme.RC
import app.recall.ui.theme.Type
import app.recall.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(RC.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = RC.Soft) }
            Text("Models", style = Type.h2)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modelsContent()
        }
    }
}

/** Shared by the Models screen and the last onboarding step. */
fun LazyListScope.modelsContent() {
    item { ModelsHeader() }
    ModelCatalog.all.forEach { spec -> item(key = spec.id) { SpecCard(spec) } }
    item { ImportCard() }
    item { TestCard() }
}

private val tick = mutableIntStateOf(0)

@Composable
private fun ModelsHeader() {
    val context = LocalContext.current
    val ram = remember { ModelStore.ramGb(context) }
    val free = remember(tick.intValue) { ModelStore.freeBytes() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Give Recall a brain", style = Type.h1)
        Text("Your phone: %.0f GB RAM · %s free".format(ram, Fmt.bytes(free)), style = Type.small)
        Text(
            "The model runs on this phone. Recall works without one, but the written summary and full chat answers need it.",
            style = Type.small,
        )
        if (Edition.CAN_DOWNLOAD) {
            var mobile by remember { mutableStateOf(App.prefs.allowMobileData) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Use mobile data for downloads", style = Type.bodySoft, modifier = Modifier.weight(1f))
                Switch(checked = mobile, onCheckedChange = { mobile = it; App.prefs.allowMobileData = it })
            }
        }
    }
}

@Composable
private fun SpecCard(spec: ModelSpec) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = tick.intValue
    val installed: InstalledModel? = remember(t) { ModelStore.installed().firstOrNull { it.file.name == spec.file } }
    val active = remember(t) { ModelStore.active()?.file?.name == spec.file }
    val recommended = remember { ModelCatalog.recommend(ModelStore.ramGb(context))?.id == spec.id }
    val state by remember(spec.id) { Edition.state(context, spec) }.collectAsStateWithLifecycle(null)
    LaunchedEffect(state?.finished) { if (state?.finished == true) tick.intValue++ }

    RCard(highlight = recommended && installed == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(spec.name, style = Type.title, modifier = Modifier.weight(1f))
            when {
                active && installed != null -> Pill("in use", Tone.Money)
                recommended -> Pill("best for you", Tone.Accent)
            }
        }
        Text("${Fmt.bytes(spec.bytes)} · ${spec.note} · ${spec.license}", style = Type.small)
        val s = state
        when {
            installed != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) SmallButton("Use this", primary = true) { ModelStore.setActive(installed.file); tick.intValue++ }
                SmallButton("Delete") {
                    scope.launch(Dispatchers.IO) { ModelStore.delete(installed.file); tick.intValue++ }
                }
            }
            s != null && s.running -> {
                LinearProgressIndicator(
                    progress = { s.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = RC.AccentMid, trackColor = RC.Raised,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.error ?: "${Fmt.bytes(s.done)} / ${Fmt.bytes(s.total)} · resumes if interrupted",
                        style = Type.small, modifier = Modifier.weight(1f),
                    )
                    SmallButton("Cancel") { Edition.cancel(context, spec) }
                }
            }
            else -> {
                if (s?.error != null) Text(s.error, style = Type.small.copy(color = RC.Urgent))
                if (Edition.CAN_DOWNLOAD) {
                    SmallButton(if (s?.error != null) "Try again" else "Download", primary = recommended) { Edition.download(context, spec) }
                }
            }
        }
    }
}

@Composable
private fun ImportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = null
        progress = 0L to -1L
        scope.launch {
            val result = withContext(Dispatchers.IO) { ModelStore.import(context, uri) { d, t -> progress = d to t } }
            progress = null
            message = result.fold({ "Imported ${it.name}" }, { it.message ?: "Import failed" })
            tick.intValue++
        }
    }
    RCard {
        Label(if (Edition.CAN_DOWNLOAD) "Other ways" else "Add a model")
        if (!Edition.CAN_DOWNLOAD) {
            Text(
                "This edition has no internet permission, so Android guarantees nothing leaves your phone. " +
                    "Download a model file in your browser, then import it here:",
                style = Type.small,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelCatalog.all.forEach { Text("${it.name}\n${it.url}", style = Type.mono.copy(color = RC.Muted)) }
                }
            }
        }
        SmallButton("Import a .gguf file", primary = !Edition.CAN_DOWNLOAD, enabled = progress == null) {
            picker.launch(arrayOf("*/*"))
        }
        progress?.let { (d, t) ->
            Text(if (t > 0) "Importing ${Fmt.bytes(d)} of ${Fmt.bytes(t)}…" else "Importing ${Fmt.bytes(d)}…", style = Type.small)
        }
        message?.let { Text(it, style = Type.small.copy(color = RC.Accent)) }
        if (Edition.CAN_DOWNLOAD) {
            Text("Or paste any GGUF link from Hugging Face:", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://huggingface.co/…/model.gguf") })
            if (custom.isNotBlank()) SmallButton("Download link") {
                message = if (Edition.downloadCustom(context, custom)) "Downloading in the background" else "That doesn't look like a huggingface.co .gguf link"
                custom = ""
            }
        }
    }
}

@Composable
private fun TestCard() {
    val scope = rememberCoroutineScope()
    val t = tick.intValue
    val active = remember(t) { ModelStore.active() } ?: return
    var out by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    RCard {
        Label("Try it")
        Text("Runs ${active.name} once on this phone and shows how fast it is.", style = Type.small)
        SmallButton(if (running) "Running…" else "Test the model", enabled = !running) {
            running = true
            out = null
            scope.launch {
                val t0 = System.currentTimeMillis()
                var tokens = 0
                val answer = Brain.generate(
                    system = "You are Recall, a private assistant on the user's phone.",
                    user = "In one short sentence, say hello and what you can help with.",
                    maxTokens = 60,
                    load = app.recall.model.Safety.Load.Interactive,
                ) { tokens++ }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                out = (answer ?: Brain.blocked.value ?: "The model couldn't load. Try another one.") + "\n\n%.1f s total".format(secs)
                running = false
            }
        }
        out?.let { Text(it, style = Type.bodySoft) }
    }
}
