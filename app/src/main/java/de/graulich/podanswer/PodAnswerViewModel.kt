package de.graulich.podanswer

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class PodAnswerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EpisodeStore(application)
    private val keyStore = ApiKeyStore(application)
    private val settingsStore = SettingsStore(application)
    private val gemini = GeminiClient(audioChunkExporter = AndroidAudioChunkExporter(application))
    private val rssResolver = RssDescriptionResolver()
    private val downloader = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).build()
    private val oneDriveStore = OneDriveStore(application)
    private val oneDrive = OneDriveClient(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(2, TimeUnit.MINUTES).build())
    private val syncLock = Mutex()
    private var oneDriveAccessToken: String? = null
    private var oneDriveAccessExpiry = 0L

    private val initialRecords = store.load().map { record ->
        if (record.stage !in setOf(JobStage.IDLE, JobStage.COMPLETE, JobStage.ERROR))
            record.copy(stage = JobStage.ERROR, error = "Die Verarbeitung wurde von Android unterbrochen. Du kannst sie erneut starten; eine bereits geladene MP3 wird wiederverwendet.")
        else record
    }
    private val _state = MutableStateFlow(
        AppState(
            records = initialRecords,
            apiKeyConfigured = keyStore.hasKey(),
            defaultContext = settingsStore.readDefaultContext(),
            interestingFactsEnabled = settingsStore.readInterestingFactsEnabled(),
            thinkingLevel = settingsStore.readThinkingLevel(),
            oneDriveClientId = oneDriveStore.clientId(),
            oneDriveConnected = oneDriveStore.isConnected(),
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        store.save(initialRecords)
        syncOneDrive()
    }

    // --- OneDrive auto-sync -------------------------------------------------------------

    fun connectOneDrive(clientId: String) = viewModelScope.launch {
        val id = clientId.trim()
        if (id.isBlank()) { setOneDriveStatus("Bitte zuerst die Client-ID eintragen."); return@launch }
        oneDriveStore.saveClientId(id)
        _state.update { it.copy(oneDriveClientId = id) }
        val code = try {
            withContext(Dispatchers.IO) { oneDrive.startDeviceLogin(id) }
        } catch (t: Throwable) {
            setOneDriveStatus(readableError(t)); return@launch
        }
        _state.update { it.copy(oneDriveLogin = OneDriveLogin(code.userCode, code.verificationUri), oneDriveStatus = "Warte auf Anmeldung bei Microsoft …") }
        var interval = code.intervalSeconds
        val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline && _state.value.oneDriveLogin != null) {
            delay(interval * 1000L)
            val result = runCatching { withContext(Dispatchers.IO) { oneDrive.pollDeviceLogin(id, code.deviceCode) } }
                .getOrDefault(OneDriveClient.PollResult.Pending)
            when (result) {
                OneDriveClient.PollResult.Pending -> Unit
                OneDriveClient.PollResult.SlowDown -> interval += 5
                is OneDriveClient.PollResult.Failed -> {
                    _state.update { it.copy(oneDriveLogin = null, oneDriveStatus = result.message) }; return@launch
                }
                is OneDriveClient.PollResult.Success -> {
                    val refresh = result.tokens.refreshToken
                    if (refresh == null) {
                        _state.update { it.copy(oneDriveLogin = null, oneDriveStatus = "Microsoft hat kein Refresh-Token geliefert.") }
                        return@launch
                    }
                    oneDriveStore.saveRefreshToken(refresh)
                    cacheAccessToken(result.tokens)
                    _state.update { it.copy(oneDriveLogin = null, oneDriveConnected = true, oneDriveStatus = "Mit OneDrive verbunden.") }
                    syncPending()
                    return@launch
                }
            }
        }
        if (_state.value.oneDriveLogin != null) {
            _state.update { it.copy(oneDriveLogin = null, oneDriveStatus = "Der Anmeldecode ist abgelaufen. Bitte erneut verbinden.") }
        }
    }

    fun cancelOneDriveLogin() = _state.update { it.copy(oneDriveLogin = null, oneDriveStatus = "") }

    fun disconnectOneDrive() {
        oneDriveStore.disconnect()
        oneDriveAccessToken = null
        _state.update { it.copy(oneDriveConnected = false, oneDriveStatus = "OneDrive-Sync ist ausgeschaltet.") }
    }

    fun syncOneDrive() { viewModelScope.launch { syncPending() } }

    /** Uploads every finished analysis that is not in OneDrive yet. Safe to call often. */
    private suspend fun syncPending() = syncLock.withLock {
        val clientId = oneDriveStore.clientId()
        val refreshToken = oneDriveStore.refreshToken()
        if (clientId.isBlank() || refreshToken == null) return@withLock
        val synced = oneDriveStore.syncedIds()
        val pending = PodAnswerExport.exportable(_state.value.records).filter { it.id !in synced }
        if (pending.isEmpty()) return@withLock
        try {
            withContext(Dispatchers.IO) {
                val token = accessToken(clientId, refreshToken)
                pending.forEach { record ->
                    oneDrive.upload(token, PodAnswerExport.syncFileName(record), PodAnswerExport.toJson(listOf(record)))
                    oneDriveStore.markSynced(record.id)
                }
            }
            setOneDriveStatus(if (pending.size == 1) "1 Analyse mit OneDrive synchronisiert." else "${pending.size} Analysen mit OneDrive synchronisiert.")
        } catch (t: Throwable) {
            setOneDriveStatus("OneDrive-Sync fehlgeschlagen: ${readableError(t)} Wird beim nächsten Start erneut versucht.")
        }
    }

    private fun accessToken(clientId: String, refreshToken: String): String {
        oneDriveAccessToken?.takeIf { System.currentTimeMillis() < oneDriveAccessExpiry }?.let { return it }
        val tokens = oneDrive.refresh(clientId, refreshToken)
        tokens.refreshToken?.let(oneDriveStore::saveRefreshToken)
        cacheAccessToken(tokens)
        return tokens.accessToken
    }

    private fun cacheAccessToken(tokens: OneDriveClient.Tokens) {
        oneDriveAccessToken = tokens.accessToken
        oneDriveAccessExpiry = System.currentTimeMillis() + (tokens.expiresInSeconds - 120).coerceAtLeast(60) * 1000L
    }

    private fun setOneDriveStatus(message: String) = _state.update { it.copy(oneDriveStatus = message) }

    fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when {
            intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) _state.update { it.copy(incoming = PodcastRepublicParser.parse(text), selectedId = null) }
            }
            intent.type?.startsWith("audio/") == true -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) importAudio(uri)
            }
        }
    }

    fun importAudio(uri: Uri) = viewModelScope.launch {
        runCatching {
            val file = copyAudio(uri)
            SharedEpisode(title = file.nameWithoutExtension, localAudioPath = file.absolutePath)
        }.onSuccess { episode -> _state.update { it.copy(incoming = episode, selectedId = null) } }
            .onFailure { fail(it) }
    }

    fun updateIncoming(episode: SharedEpisode) = _state.update { it.copy(incoming = episode) }
    fun cancelIncoming() = _state.update { it.copy(incoming = null) }
    fun openRecord(id: String?) = _state.update { it.copy(selectedId = id, incoming = null) }
    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open) }

    fun saveSettings(apiKey: String, defaultContext: String, interestingFactsEnabled: Boolean, thinkingLevel: ThinkingLevel) {
        if (apiKey.isNotBlank()) keyStore.save(apiKey)
        val normalizedContext = defaultContext.trim()
        settingsStore.saveDefaultContext(normalizedContext)
        settingsStore.saveInterestingFactsEnabled(interestingFactsEnabled)
        settingsStore.saveThinkingLevel(thinkingLevel)
        _state.update {
            it.copy(
                apiKeyConfigured = keyStore.hasKey(),
                defaultContext = normalizedContext,
                interestingFactsEnabled = interestingFactsEnabled,
                thinkingLevel = thinkingLevel,
                settingsOpen = false,
            )
        }
    }

    fun analyzeIncoming() {
        val episode = _state.value.incoming ?: return
        if (episode.title.isBlank()) {
            fail(IllegalArgumentException("Bitte gib einen Episodentitel ein.")); return
        }
        if (episode.mediaUrl.isBlank() && episode.localAudioPath.isNullOrBlank()) {
            fail(IllegalArgumentException("Im Share-Text wurde kein Media-Link gefunden.")); return
        }
        if (!keyStore.hasKey()) {
            _state.update { it.copy(settingsOpen = true) }; return
        }
        val id = UUID.randomUUID().toString()
        val record = EpisodeRecord(id, episode, System.currentTimeMillis(), stage = JobStage.DOWNLOADING)
        val records = listOf(record) + _state.value.records
        store.save(records)
        _state.update { it.copy(records = records, incoming = null, selectedId = id, busyStage = JobStage.DOWNLOADING, busyMessage = "MP3 wird geladen …") }
        runAnalysis(id)
    }

    fun retry(id: String) {
        if (!keyStore.hasKey()) { _state.update { it.copy(settingsOpen = true) }; return }
        updateRecord(id) { it.copy(stage = JobStage.DOWNLOADING, error = null) }
        runAnalysis(id)
    }

    private fun runAnalysis(id: String) = viewModelScope.launch {
        try {
            val record = _state.value.records.first { it.id == id }
            val enrichedEpisode = rssResolver.enrich(record.episode)
            val audio = enrichedEpisode.localAudioPath?.let(::File)?.takeIf { it.exists() }
                ?: download(enrichedEpisode.mediaUrl, id)
            val episodeWithAudio = enrichedEpisode.copy(localAudioPath = audio.absolutePath)
            updateRecord(id) { it.copy(episode = episodeWithAudio, stage = JobStage.UPLOADING) }
            val apiKey = keyStore.read() ?: error("Der API-Schlüssel konnte nicht gelesen werden.")
            val analysis = gemini.analyze(
                apiKey = apiKey,
                audio = audio,
                episode = episodeWithAudio,
                defaultContext = _state.value.defaultContext,
                interestingFactsEnabled = _state.value.interestingFactsEnabled,
                thinkingLevel = _state.value.thinkingLevel,
            ) { stage, message ->
                _state.update { it.copy(busyStage = stage, busyMessage = message) }
                updateRecord(id, persist = false) { it.copy(stage = stage, progressMessage = message) }
            }
            updateRecord(id) { it.copy(analysis = analysis, stage = JobStage.COMPLETE, error = null) }
            _state.update { it.copy(busyStage = JobStage.COMPLETE, busyMessage = "Analyse abgeschlossen") }
            oneDriveStore.markUnsynced(id)
            syncPending()
        } catch (throwable: Throwable) {
            updateRecord(id) { it.copy(stage = JobStage.ERROR, error = readableError(throwable)) }
            _state.update { it.copy(busyStage = JobStage.ERROR, busyMessage = readableError(throwable)) }
        }
    }

    fun deleteRecord(id: String) {
        _state.value.records.firstOrNull { it.id == id }?.episode?.localAudioPath?.let { runCatching { File(it).delete() } }
        val records = _state.value.records.filterNot { it.id == id }
        store.save(records)
        _state.update { it.copy(records = records, selectedId = null) }
    }

    /** Writes the given analyses to a document the user picked, e.g. a file in their OneDrive folder. */
    fun exportAnalyses(uri: Uri, ids: List<String>, onDone: (String) -> Unit) = viewModelScope.launch {
        val records = PodAnswerExport.exportable(_state.value.records.filter { it.id in ids })
        val message = runCatching {
            withContext(Dispatchers.IO) {
                val json = PodAnswerExport.toJson(records)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("Die Exportdatei konnte nicht geöffnet werden.")
            }
            if (records.size == 1) "Analyse exportiert." else "${records.size} Analysen exportiert."
        }.getOrElse { "Export fehlgeschlagen: ${readableError(it)}" }
        onDone(message)
    }

    private suspend fun download(url: String, id: String): File = withContext(Dispatchers.IO) {
        _state.update { it.copy(busyStage = JobStage.DOWNLOADING, busyMessage = "MP3 wird geladen …") }
        val targetDir = File(getApplication<Application>().filesDir, "audio").apply { mkdirs() }
        val target = File(targetDir, "$id.mp3")
        val request = Request.Builder().url(url).header("User-Agent", "PodAnswer/0.1").build()
        downloader.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download fehlgeschlagen (${response.code}).")
            val body = response.body ?: throw IOException("Der Download war leer.")
            target.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
        }
        target
    }

    private suspend fun copyAudio(uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = getApplication<Application>().contentResolver
        val dir = File(getApplication<Application>().filesDir, "audio").apply { mkdirs() }
        val file = File(dir, "import-${UUID.randomUUID()}.mp3")
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Die Audiodatei konnte nicht geöffnet werden.")
        file
    }

    private fun updateRecord(id: String, persist: Boolean = true, transform: (EpisodeRecord) -> EpisodeRecord) {
        val records = _state.value.records.map { if (it.id == id) transform(it) else it }
        if (persist) store.save(records)
        _state.update { it.copy(records = records) }
    }

    private fun fail(t: Throwable) = _state.update { it.copy(busyStage = JobStage.ERROR, busyMessage = readableError(t)) }

    private fun readableError(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: "Unbekannter Fehler"
}
