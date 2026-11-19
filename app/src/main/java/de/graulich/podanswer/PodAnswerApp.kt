package de.graulich.podanswer

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFF172A2D)
private val Paper = Color(0xFFF7F4EF)
private val Teal = Color(0xFF146B66)
private val Mist = Color(0xFFE3EFEC)
private val Coral = Color(0xFFC85D45)

@Composable
fun PodAnswerApp(viewModel: PodAnswerViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAudio)
    }
    var exportIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PodAnswerExport.MIME_TYPE)) { uri ->
        val ids = exportIds
        exportIds = emptyList()
        if (uri != null && ids.isNotEmpty()) viewModel.exportAnalyses(uri, ids) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val startExport: (List<EpisodeRecord>) -> Unit = { records ->
        val exportable = PodAnswerExport.exportable(records)
        if (exportable.isEmpty()) Toast.makeText(context, "Keine abgeschlossene Analyse zum Exportieren.", Toast.LENGTH_SHORT).show()
        else { exportIds = exportable.map { it.id }; exporter.launch(PodAnswerExport.fileName(exportable)) }
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Teal, onPrimary = Color.White, secondary = Coral,
            background = Paper, surface = Paper, onBackground = Ink, onSurface = Ink,
            primaryContainer = Mist, onPrimaryContainer = Ink,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            when {
                state.incoming != null -> ImportScreen(
                    episode = state.incoming!!,
                    onChange = viewModel::updateIncoming,
                    onAnalyze = viewModel::analyzeIncoming,
                    onCancel = viewModel::cancelIncoming,
                    apiConfigured = state.apiKeyConfigured,
                    onSettings = { viewModel.openSettings(true) },
                )
                state.selectedId != null -> state.records.firstOrNull { it.id == state.selectedId }?.let { record ->
                    DetailScreen(record, onBack = { viewModel.openRecord(null) }, onRetry = { viewModel.retry(record.id) },
                        onDelete = { viewModel.deleteRecord(record.id) }, onShare = { shareAnalysis(context, record) },
                        onExport = { startExport(listOf(record)) })
                } ?: HomeScreen(state.records, viewModel::openRecord, { picker.launch(arrayOf("audio/*")) }, { viewModel.openSettings(true) }, { startExport(state.records) })
                else -> HomeScreen(state.records, viewModel::openRecord, { picker.launch(arrayOf("audio/*")) }, { viewModel.openSettings(true) }, { startExport(state.records) })
            }
        }
        if (state.settingsOpen) SettingsDialog(
            configured = state.apiKeyConfigured,
            defaultContext = state.defaultContext,
            interestingFactsEnabled = state.interestingFactsEnabled,
            thinkingLevel = state.thinkingLevel,
            onDismiss = { viewModel.openSettings(false) },
            onSave = viewModel::saveSettings,
            oneDriveClientId = state.oneDriveClientId,
            oneDriveConnected = state.oneDriveConnected,
            oneDriveStatus = state.oneDriveStatus,
            onConnectOneDrive = viewModel::connectOneDrive,
            onDisconnectOneDrive = viewModel::disconnectOneDrive,
            onSyncOneDrive = viewModel::syncOneDrive,
        )
        state.oneDriveLogin?.let { login ->
            OneDriveLoginDialog(login, onCancel = viewModel::cancelOneDriveLogin)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(records: List<EpisodeRecord>, onOpen: (String?) -> Unit, onPick: () -> Unit, onSettings: () -> Unit, onExportAll: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("PodAnswer", fontWeight = FontWeight.Bold) }, actions = {
            if (PodAnswerExport.exportable(records).isNotEmpty()) IconButton(onClick = onExportAll) { Icon(Icons.Default.CloudUpload, "Alle Analysen nach OneDrive exportieren") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
        }) },
        bottomBar = { NavigationBar(containerColor = Paper) {
            NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.History, null) }, label = { Text("Verlauf") })
            NavigationBarItem(selected = false, onClick = onPick, icon = { Icon(Icons.Default.Add, null) }, label = { Text("Audiodatei") })
        } }
    ) { padding ->
        if (records.isEmpty()) EmptyHome(Modifier.padding(padding), onPick)
        else LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Deine Analysen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
            items(records, key = { it.id }) { record -> EpisodeCard(record) { onOpen(record.id) } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier, onPick: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(76.dp).background(Mist, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Share, null, tint = Teal, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("Podcast teilen, Antworten erhalten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Teile eine Folge aus Podcast Republic mit PodAnswer. Titel, MP3 und Metadaten werden automatisch übernommen.", color = Ink.copy(alpha = .72f))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onPick) { Text("MP3 manuell auswählen") }
    }
}

@Composable
private fun EpisodeCard(record: EpisodeRecord, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            SelectionContainer {
                Text(record.episode.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (record.episode.podcast.isNotBlank()) {
                SelectionContainer {
                    Text(record.episode.podcast, style = MaterialTheme.typography.bodySmall, color = Teal)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(record.stage)
                Spacer(Modifier.weight(1f))
                Text(DateFormat.getDateInstance().format(Date(record.createdAt)), style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = .55f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreen(
    episode: SharedEpisode,
    onChange: (SharedEpisode) -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    apiConfigured: Boolean,
    onSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Neue Folge") }, navigationIcon = {
        IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Bereit zur Analyse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Prüfe den Titel – aus ihm leitet die App die zu beantwortenden Fragen ab.", color = Ink.copy(alpha = .7f))
            }
            item { OutlinedTextField(episode.title, { onChange(episode.copy(title = it)) }, label = { Text("Episodentitel") }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
            item { OutlinedTextField(episode.podcast, { onChange(episode.copy(podcast = it)) }, label = { Text("Podcast") }, modifier = Modifier.fillMaxWidth()) }
            if (episode.duration.isNotBlank() || episode.published.isNotBlank()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (episode.duration.isNotBlank()) MetadataChip("Dauer", episode.duration)
                    if (episode.published.isNotBlank()) MetadataChip("Veröffentlicht", episode.published.substringBefore(" +"))
                }
            }
            item { OutlinedTextField(episode.description, { onChange(episode.copy(description = it)) }, label = { Text("Beschreibung (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
            item {
                if (!apiConfigured) Card(colors = CardDefaults.cardColors(containerColor = Mist)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Gemini-API-Schlüssel erforderlich", fontWeight = FontWeight.SemiBold)
                        Text("Der Schlüssel wird verschlüsselt auf diesem Gerät gespeichert.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSettings) { Text("Schlüssel hinterlegen") }
                    }
                }
            }
            item {
                Button(onClick = onAnalyze, enabled = episode.title.isNotBlank() && apiConfigured, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Podcast analysieren")
                }
                Text("Die MP3 wird zur Verarbeitung an Google Gemini übertragen.", style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = .55f), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MetadataChip(label: String, value: String) {
    Column(Modifier.background(Mist, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Teal)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(record: EpisodeRecord, onBack: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit, onExport: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val player = rememberAudioPlayer(record.episode.localAudioPath)
    Scaffold(topBar = { TopAppBar(title = { Text(record.episode.podcast.ifBlank { "Analyse" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
    }, actions = {
        if (record.analysis != null) IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Teilen") }
        if (record.analysis != null && record.stage == JobStage.COMPLETE) IconButton(onClick = onExport) { Icon(Icons.Default.CloudUpload, "Nach OneDrive exportieren") }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Löschen") }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SelectionContainer {
                    Text(record.episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            when {
                record.stage == JobStage.ERROR -> ErrorPanel(record.error.orEmpty(), onRetry)
                record.analysis == null -> ProcessingPanel(record.stage, record.progressMessage)
                else -> {
                    TabRow(selectedTabIndex = tab, containerColor = Paper) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Antworten") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Transkript") })
                    }
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) AnswersTab(record, player) else TranscriptTab(record, player)
                    }
                    AudioPlaybackBar(player)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Analyse löschen?") },
        text = { Text("Dabei werden auch die lokal gespeicherte MP3 und das Transkript gelöscht.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Löschen", color = Coral) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } })
}

@Composable
private fun ProcessingPanel(stage: JobStage, message: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Teal)
        Spacer(Modifier.height(18.dp))
        Text(message.ifBlank {
            when (stage) {
                JobStage.DOWNLOADING -> "MP3 wird geladen …"
                JobStage.UPLOADING -> "Audio wird hochgeladen …"
                JobStage.PROCESSING -> "Transkript und Antworten werden erstellt …"
                else -> "Analyse wird vorbereitet …"
            }
        }, fontWeight = FontWeight.SemiBold)
        Text("Folgen bis 6 Stunden werden abschnittsweise verarbeitet. Bitte lasse die App während der Analyse geöffnet.", style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .6f))
    }
}

@Composable
private fun ErrorPanel(error: String, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("Analyse fehlgeschlagen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Coral)
        Spacer(Modifier.height(8.dp))
        SelectionContainer { Text(error) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRetry) { Text("Erneut versuchen") }
    }
}

@Composable
private fun AnswersTab(record: EpisodeRecord, player: AudioPlayerControls) {
    val analysis = record.analysis ?: return
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SelectionContainer {
                Column {
                    Text("Kurzfassung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    Text(analysis.summary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        items(analysis.topics) { topic ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                SelectionContainer {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) { Text(topic.question, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); StatusLabel(topic.status) }
                        Spacer(Modifier.height(10.dp)); Text(topic.answer)
                        if (topic.nuance.isNotBlank()) {
                            Spacer(Modifier.height(12.dp)); Text("Einordnung", style = MaterialTheme.typography.labelLarge, color = Teal); Text(topic.nuance, style = MaterialTheme.typography.bodySmall)
                        }
                        topic.evidence.forEach { evidence ->
                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Mist)
                            val isCurrent = player.activeSeconds == evidence.timestampSeconds
                            Row(Modifier.clickable {
                                if (isCurrent) player.togglePause() else player.playFrom(evidence.timestampSeconds)
                            }, verticalAlignment = Alignment.Top) {
                                Icon(if (isCurrent && player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Teal, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                                Column { Text(formatTime(evidence.timestampSeconds), color = Teal, style = MaterialTheme.typography.labelMedium); Text("„${evidence.quote}“", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TranscriptTab(record: EpisodeRecord, player: AudioPlayerControls) {
    val transcript = record.analysis?.transcript.orEmpty()
    var query by remember { mutableStateOf("") }
    val visible = remember(query, transcript) { if (query.isBlank()) transcript else transcript.filter { it.text.contains(query, true) || it.speaker.contains(query, true) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, label = { Text("Transkript durchsuchen") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true) }
        items(visible) { segment ->
            val isCurrent = player.activeSeconds == segment.startSeconds
            SelectionContainer {
                Row(Modifier.fillMaxWidth().clickable {
                    if (isCurrent) player.togglePause() else player.playFrom(segment.startSeconds)
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(formatTime(segment.startSeconds), color = Teal, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
                    Column { Text(segment.speaker, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium); Text(segment.text) }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private data class AudioPlayerControls(
    val isAvailable: Boolean,
    val activeSeconds: Int?,
    val isPlaying: Boolean,
    val playFrom: (Int) -> Unit,
    val togglePause: () -> Unit,
    val stop: () -> Unit,
)

@Composable
private fun rememberAudioPlayer(path: String?): AudioPlayerControls {
    var isPlaying by remember(path) { mutableStateOf(false) }
    var activeSeconds by remember(path) { mutableStateOf<Int?>(null) }
    val player = remember(path) {
        path?.takeIf { File(it).exists() }?.let { audioPath ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(audioPath)
                    prepare()
                    setOnCompletionListener {
                        isPlaying = false
                        activeSeconds = null
                    }
                }
            }.getOrNull()
        }
    }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    return AudioPlayerControls(
        isAvailable = player != null,
        activeSeconds = activeSeconds,
        isPlaying = isPlaying,
        playFrom = { seconds ->
            player?.apply {
                if (isPlaying) pause()
                isPlaying = false
                activeSeconds = seconds
                setOnSeekCompleteListener { mediaPlayer ->
                    mediaPlayer.start()
                    isPlaying = true
                }
                seekTo(seconds.coerceAtLeast(0) * 1000L, MediaPlayer.SEEK_CLOSEST)
            }
        },
        togglePause = {
            player?.let {
                if (activeSeconds != null) {
                    if (it.isPlaying) {
                        it.pause()
                        isPlaying = false
                    } else {
                        it.start()
                        isPlaying = true
                    }
                }
            }
        },
        stop = {
            player?.let {
                if (it.isPlaying) it.pause()
                activeSeconds = null
                isPlaying = false
            }
        },
    )
}

@Composable
private fun AudioPlaybackBar(player: AudioPlayerControls) {
    val startSeconds = player.activeSeconds ?: return
    Surface(color = Mist, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (player.isPlaying) "Wiedergabe läuft" else "Wiedergabe pausiert", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Text("Snippet ab ${formatTime(startSeconds)}", style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = .65f))
            }
            IconButton(onClick = player.togglePause, enabled = player.isAvailable) {
                Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.isPlaying) "Pausieren" else "Fortsetzen")
            }
            IconButton(onClick = player.stop, enabled = player.isAvailable) {
                Icon(Icons.Default.Stop, "Wiedergabe stoppen")
            }
        }
    }
}

@Composable
private fun StatusPill(stage: JobStage) {
    val (text, color) = when (stage) {
        JobStage.COMPLETE -> "Fertig" to Teal
        JobStage.ERROR -> "Fehler" to Coral
        else -> "In Arbeit" to Color(0xFF9A6A13)
    }
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp))
}

@Composable
private fun StatusLabel(status: String) {
    val color = when (status) {
        "Beantwortet" -> Teal
        "Nicht beantwortet" -> Coral
        "Interessanter Fakt" -> Color(0xFF6750A4)
        else -> Color(0xFF9A6A13)
    }
    Text(status, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun SettingsDialog(
    configured: Boolean,
    defaultContext: String,
    interestingFactsEnabled: Boolean,
    thinkingLevel: ThinkingLevel,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, ThinkingLevel) -> Unit,
    oneDriveClientId: String,
    oneDriveConnected: Boolean,
    oneDriveStatus: String,
    onConnectOneDrive: (String) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    onSyncOneDrive: () -> Unit,
) {
    var clientId by remember(oneDriveClientId) { mutableStateOf(oneDriveClientId) }
    var key by remember { mutableStateOf("") }
    var context by remember(defaultContext) { mutableStateOf(defaultContext) }
    var includeInterestingFacts by remember(interestingFactsEnabled) { mutableStateOf(interestingFactsEnabled) }
    var selectedThinking by remember(thinkingLevel) { mutableStateOf(thinkingLevel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (configured) "Ein API-Schlüssel ist bereits gespeichert. Ein neuer Eintrag ersetzt ihn." else "Füge deinen Gemini-API-Schlüssel aus Google AI Studio ein.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("API-Schlüssel") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(20.dp))
                Text("Standard-Kontext", fontWeight = FontWeight.SemiBold)
                Text(
                    "Dieser Fokus wird bei jeder neuen Transkription und Zusammenfassung berücksichtigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = .7f),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    context,
                    { context = it },
                    label = { Text("Worauf soll PodAnswer achten?") },
                    placeholder = { Text("z. B. Risiken, konkrete Empfehlungen und offene Fragen hervorheben") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        includeInterestingFacts = !includeInterestingFacts
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeInterestingFacts,
                        onCheckedChange = { includeInterestingFacts = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Interessante Fakten", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Zusätzliche Karten für Fakten, die wegen deines persönlichen Kontexts relevant sind.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = .7f),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                OneDriveSection(clientId, { clientId = it }, oneDriveConnected, oneDriveStatus,
                    { onConnectOneDrive(clientId) }, onDisconnectOneDrive, onSyncOneDrive)
                Spacer(Modifier.height(20.dp))
                Text("Denktiefe", fontWeight = FontWeight.SemiBold)
                Text(
                    "Wie intensiv Gemini 4 Hydrogen vor der Antwort nachdenkt. Höhere Stufen sind langsamer und verbrauchen mehr Tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = .7f),
                )
                ThinkingLevel.entries.forEach { level ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedThinking = level },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedThinking == level, onClick = { selectedThinking = level })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(level.label, fontWeight = FontWeight.SemiBold)
                            Text(level.description, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .7f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(key, context, includeInterestingFacts, selectedThinking) },
                enabled = configured || key.isNotBlank(),
            ) {
                Text("Speichern")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun formatTime(seconds: Int): String = if (seconds >= 3_600) {
    "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
} else {
    "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun shareAnalysis(context: Context, record: EpisodeRecord) {
    val analysis = record.analysis ?: return
    val text = buildString {
        appendLine(record.episode.title); appendLine(record.episode.podcast); appendLine(); appendLine(analysis.summary); appendLine()
        analysis.topics.forEach { topic ->
            appendLine(topic.question); appendLine("${topic.status}: ${topic.answer}")
            if (topic.nuance.isNotBlank()) appendLine("Einordnung: ${topic.nuance}")
            topic.evidence.forEach { appendLine("${formatTime(it.timestampSeconds)} — ${it.quote}") }
            appendLine()
        }
        appendLine("TRANSKRIPT")
        analysis.transcript.forEach { segment ->
            appendLine("${formatTime(segment.startSeconds)} ${segment.speaker}: ${segment.text}")
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, record.episode.title); putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(intent, "Analyse teilen"))
}

@Composable
private fun OneDriveSection(
    clientId: String,
    onClientId: (String) -> Unit,
    connected: Boolean,
    status: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    Text("OneDrive-Sync", fontWeight = FontWeight.SemiBold)
    Text(
        if (connected) "Jede fertige Analyse wird automatisch nach OneDrive › Apps › PodAnswer hochgeladen. Podcast Transcriber importiert sie von dort."
        else "Lädt jede fertige Analyse automatisch nach OneDrive hoch, damit Podcast Transcriber sie importiert. Einmalig die Client-ID deiner App-Registrierung eintragen und verbinden.",
        style = MaterialTheme.typography.bodySmall,
        color = Ink.copy(alpha = .7f),
    )
    Spacer(Modifier.height(8.dp))
    if (!connected) {
        OutlinedTextField(
            clientId, onClientId,
            label = { Text("Client-ID (Microsoft Entra)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onConnect, enabled = clientId.isNotBlank()) { Text("Mit OneDrive verbinden") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSync) { Text("Jetzt synchronisieren") }
            TextButton(onClick = onDisconnect) { Text("Trennen") }
        }
    }
    if (status.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.bodySmall, color = Teal)
    }
}

@Composable
private fun OneDriveLoginDialog(login: OneDriveLogin, onCancel: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Bei OneDrive anmelden") },
        text = {
            Column {
                Text("Öffne die Anmeldeseite, gib diesen Code ein und melde dich mit deinem Microsoft-Konto an. PodAnswer verbindet sich danach automatisch.")
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(login.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Teal)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Warte auf Anmeldung …", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(login.userCode))
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.verificationUri))) }
            }) { Text("Code kopieren & öffnen") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}
