package de.graulich.podanswer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GeminiClient(
    private val audioChunkExporter: AudioChunkExporter? = null,
    private val audioInfoReader: (File) -> AudioInfo = ::readAudioInfo,
    private val apiBaseUrl: String = "https://generativelanguage.googleapis.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build(),
) {

    suspend fun analyze(
        apiKey: String,
        audio: File,
        episode: SharedEpisode,
        defaultContext: String,
        interestingFactsEnabled: Boolean,
        onStage: (JobStage, String) -> Unit,
    ): PodcastAnalysis = withContext(Dispatchers.IO) {
        val info = audioInfoReader(audio)
        val chunks = AudioChunks.plan(info.durationMillis)
        if (chunks.size == 1) return@withContext analyzeFile(
            apiKey, audio, info.mimeType,
            buildPrompt(episode, info.durationMillis, defaultContext, interestingFactsEnabled), onStage,
        )

        val exporter = audioChunkExporter ?: throw IOException("Die Audioaufteilung ist nicht verfügbar.")
        val parts = chunks.mapIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val progress = "Abschnitt ${index + 1}/${chunks.size}"
            onStage(JobStage.PROCESSING, "$progress: Audio wird vorbereitet …")
            val section = exporter.export(audio, chunk)
            try {
                val prompt = buildPrompt(episode, chunk.durationMillis, defaultContext, interestingFactsEnabled) +
                    "\nDies ist $progress einer zusammenhängenden Folge. Analysiere den gesamten Abschnitt. " +
                    "Alle Zeiten beziehen sich auf diese Abschnittsdatei und beginnen bei 0. " +
                    "Der Themenstatus gilt vorläufig nur für diesen Abschnitt; die Gesamtbewertung erfolgt später."
                val analysis = analyzeFile(apiKey, section, "audio/m4a", prompt) { stage, message ->
                    onStage(stage, "$progress: $message")
                }
                offsetAnalysis(analysis, chunk, index + 1)
            } finally {
                section.delete()
            }
        }

        onStage(JobStage.PROCESSING, "Alle ${chunks.size} Abschnitte werden zur Gesamtanalyse zusammengeführt …")
        val combined = requestResult(
            apiKey = apiKey,
            prompt = buildMergePrompt(episode, parts, defaultContext, interestingFactsEnabled),
            includeTranscript = false,
        ).put("transcript", JSONArray())
        val result = parseAnalysis(combined)
        val sourceEvidence = parts.flatMap { it.topics }.flatMap { it.evidence }.toSet()
        if (result.topics.any { topic -> topic.evidence.any { it !in sourceEvidence } }) {
            throw IOException("Die Gesamtanalyse enthält einen nicht belegten Zeitstempel oder ein verändertes Zitat. Bitte erneut versuchen.")
        }
        result.copy(transcript = parts.flatMap { it.transcript }.sortedBy { it.startSeconds })
    }

    private suspend fun analyzeFile(
        apiKey: String,
        audio: File,
        mimeType: String,
        prompt: String,
        onStage: (JobStage, String) -> Unit,
    ): PodcastAnalysis {
        onStage(JobStage.UPLOADING, "Audio wird zu Gemini hochgeladen …")
        val uploaded = uploadFile(apiKey, audio, mimeType)
        return try {
            val active = waitUntilActive(apiKey, uploaded.first, uploaded.second)
            onStage(JobStage.PROCESSING, "Podcast wird transkribiert und ausgewertet …")
            parseAnalysis(requestResult(apiKey, prompt, active.second, mimeType))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(30_000) { runCatching { deleteFile(apiKey, uploaded.first) } }
            }
        }
    }

    private suspend fun uploadFile(apiKey: String, file: File, mime: String): Pair<String, String> {
        val metadata = JSONObject().put("file", JSONObject().put("display_name", file.name)).toString()
        val start = Request.Builder()
            .url("$apiBaseUrl/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(metadata.toRequestBody("application/json".toMediaType()))
            .build()
        val uploadUrl = client.newCall(start).await().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini hat keine Upload-Adresse zurückgegeben.")
        }

        val upload = Request.Builder().url(uploadUrl)
            .header("Content-Length", file.length().toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mime.toMediaType()))
            .build()
        client.newCall(upload).await().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            val json = JSONObject(response.body?.string().orEmpty()).getJSONObject("file")
            return json.getString("name") to json.getString("uri")
        }
    }

    private suspend fun waitUntilActive(apiKey: String, name: String, uri: String): Pair<String, String> {
        repeat(450) {
            val request = Request.Builder()
                .url("$apiBaseUrl/v1beta/$name")
                .header("x-goog-api-key", apiKey)
                .get().build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw apiError(response)
                val json = JSONObject(response.body?.string().orEmpty())
                when (json.optString("state")) {
                    "ACTIVE" -> return name to json.optString("uri", uri)
                    "FAILED" -> throw IOException("Gemini konnte die Audiodatei nicht verarbeiten.")
                }
            }
            delay(2_000)
        }
        throw IOException("Die Audioverarbeitung hat zu lange gedauert.")
    }

    private suspend fun requestResult(
        apiKey: String,
        prompt: String,
        uri: String? = null,
        mimeType: String = "audio/mpeg",
        includeTranscript: Boolean = true,
    ): JSONObject {
        val body = JSONObject().apply {
            put("model", "gemini-4-hydrogen")
            put("input", JSONArray().apply {
                if (uri != null) put(JSONObject().put("type", "audio").put("uri", uri).put("mime_type", mimeType))
                put(JSONObject().put("type", "text").put("text", prompt))
            })
            put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", responseSchema(includeTranscript)))
            put("generation_config", JSONObject()
                .put("max_output_tokens", 65_536)
                .put("thinking_level", "low"))
        }.toString()
        val request = Request.Builder()
            .url("$apiBaseUrl/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) throw IOException("Gemini hat eine leere Antwort zurückgegeben.")
            return extractOutput(JSONObject(raw))
        }
    }

    internal fun extractOutput(envelope: JSONObject): JSONObject {
        val status = envelope.optString("status")
        when (status) {
            "failed" -> {
                val message = envelope.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Die Verarbeitung ist bei Gemini fehlgeschlagen."
                throw IOException(message)
            }
            "incomplete" -> throw IOException("Gemini hat nur ein unvollständiges Ergebnis erzeugt. Bitte erneut versuchen.")
            "budget_exceeded" -> throw IOException("Das Gemini-Tokenbudget für diese Analyse wurde überschritten.")
            "queued", "in_progress" -> throw IOException("Gemini hat die Analyse noch nicht abgeschlossen. Bitte erneut versuchen.")
        }
        if (envelope.has("summary")) return envelope
        envelope.optString("output_text").takeIf { it.isNotBlank() }?.let { return parseJsonObject(it) }
        envelope.optJSONObject("interaction")?.let { return extractOutput(it) }

        // Current Interactions API response shape:
        // steps[] -> { type: "model_output", content[] -> { type: "text", text: "..." } }
        envelope.optJSONArray("steps")?.let { steps ->
            val text = buildString {
                for (stepIndex in 0 until steps.length()) {
                    val step = steps.optJSONObject(stepIndex) ?: continue
                    if (step.optString("type") != "model_output") continue
                    val content = step.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val item = content.optJSONObject(contentIndex) ?: continue
                        if (item.optString("type") == "text") append(item.optString("text"))
                    }
                }
            }
            if (text.isNotBlank()) return parseJsonObject(text)
        }

        val outputs = envelope.optJSONArray("outputs") ?: envelope.optJSONArray("output")
        if (outputs != null) {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                item.optString("text").takeIf { it.isNotBlank() }?.let { return parseJsonObject(it) }
                item.optJSONObject("content")?.optString("text")?.takeIf { it.isNotBlank() }?.let { return parseJsonObject(it) }
            }
        }
        throw IOException("Gemini hat kein auswertbares Ergebnis zurückgegeben.")
    }

    private fun parseJsonObject(value: String): JSONObject {
        val trimmed = value.trim()
        val withoutFence = if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else trimmed
        return try {
            JSONObject(withoutFence)
        } catch (error: Exception) {
            throw IOException("Gemini hat kein gültiges JSON-Ergebnis zurückgegeben.", error)
        }
    }

    internal fun buildPrompt(
        e: SharedEpisode,
        durationMillis: Long?,
        defaultContext: String,
        interestingFactsEnabled: Boolean,
    ): String {
        val timeline = durationMillis?.let {
            val durationSeconds = "%.3f".format(java.util.Locale.US, it / 1000.0)
            """
            Die hochgeladene Audiodatei ist laut ihrem Media-Container exakt $durationSeconds Sekunden lang.
            Verwende ausschließlich die Zeitachse dieser Datei: 0 Sekunden ist der erste decodierbare Audioframe.
            Bestimme jeden Zeitstempel direkt am Audio, nicht aus Textmenge oder geschätzter Sprechgeschwindigkeit.
            Alle Zeitstempel sind absolute Positionen, werden nicht segmentweise aufsummiert und dürfen $durationSeconds Sekunden nicht überschreiten.
            """.trimIndent()
        }.orEmpty()
        val focus = defaultContext.trim().takeIf { it.isNotEmpty() }?.let {
            """
            Zusätzlicher Standard-Kontext des Nutzers:
            <standard_context>
            $it
            </standard_context>
            Berücksichtige diesen Fokus in Themenauswahl, Kurzfassung, Antworten und Einordnung, ohne unbelegte Aussagen hinzuzufügen.
            """.trimIndent()
        }.orEmpty()
        val interestingFacts = if (interestingFactsEnabled && defaultContext.isNotBlank()) {
            """
            Ergänze nach den Frage-Themen separate Einträge für besonders relevante Fakten aus dem Podcast, die durch den Standard-Kontext des Nutzers bedeutsam sind.
            Verwende für diese Einträge:
            - question: eine kurze, aussagekräftige Überschrift, nicht als Frage formuliert
            - status: exakt „Interessanter Fakt“
            - answer: eine knappe Zusammenfassung des Fakts und seiner Relevanz für den Standard-Kontext
            - nuance: wichtige Einschränkungen oder Unsicherheiten
            - evidence: bis zu drei kurze, wortgetreue Belege mit absoluten Zeitstempeln
            Nimm nur substanzielle, direkt belegte Fakten auf und wiederhole keine Inhalte, die bereits ein Frage-Thema beantworten.
            """.trimIndent()
        } else {
            """
            Erzeuge keine Einträge mit dem Status „Interessanter Fakt“. Der Standard-Kontext beeinflusst trotzdem weiterhin Themenauswahl, Kurzfassung, Antworten und Einordnung.
            """.trimIndent()
        }

        return """
            Du analysierst eine Podcastfolge. Antworte auf Deutsch; belasse das Transkript in der gesprochenen Originalsprache.

            Titel: ${e.title}
            Podcast: ${e.podcast}
            Beschreibung: ${e.description}

            $timeline

            $focus

            $interestingFacts

            1. Transkribiere die gesamte Audiodatei möglichst wortgetreu in sinnvollen Segmenten mit Start- und Endzeit in Sekunden. Erkenne verschiedene Sprecher neutral als „Sprecher 1“, „Sprecher 2“ usw. Markiere unverständliche Passagen mit [unverständlich].
            2. Leite nur Fragen und Themen ab, die Titel, Beschreibung oder Standard-Kontext tatsächlich vorgeben. Erfinde keine konkreten Fragen für vage Formulierungen.
            3. Beantworte jedes Frage-Thema ausschließlich anhand der Audiodatei. Gib für Frage-Themen den Status exakt als „Beantwortet“, „Teilweise beantwortet“ oder „Nicht beantwortet“ an.
            4. Führe relevante Einschränkungen, Gegenargumente und Unsicherheiten unter nuance auf.
            5. Belege jede inhaltliche Antwort mit bis zu drei kurzen, wortgetreuen Transkriptausschnitten und ihrer absoluten Zeit in Sekunden. Wenn es keinen Beleg gibt, verwende eine leere Belegliste.
            6. Stelle Aussagen der Sprecher als Aussagen der Sprecher dar. Führe keinen externen Faktencheck durch.
        """.trimIndent()
    }

    internal fun offsetAnalysis(analysis: PodcastAnalysis, chunk: AudioChunk, partNumber: Int): PodcastAnalysis {
        val lastSecond = ((chunk.durationMillis + 999) / 1_000).toInt()
        val lastStartSecond = (chunk.durationMillis / 1_000).toInt()
        val offsetSeconds = (chunk.startMillis / 1_000).toInt()
        if (analysis.transcript.any { it.startSeconds !in 0..lastStartSecond || it.endSeconds !in it.startSeconds..lastSecond } ||
            analysis.topics.any { topic -> topic.evidence.any { it.timestampSeconds !in 0..lastStartSecond } }) {
            throw IOException("Gemini hat ungültige Zeitstempel für Abschnitt $partNumber zurückgegeben. Bitte erneut versuchen.")
        }
        return analysis.copy(
            topics = analysis.topics.map { topic -> topic.copy(evidence = topic.evidence.map {
                it.copy(timestampSeconds = it.timestampSeconds + offsetSeconds)
            }) },
            // Speaker numbers from independent requests do not establish identity across sections.
            transcript = analysis.transcript.map { it.copy(
                startSeconds = it.startSeconds + offsetSeconds,
                endSeconds = it.endSeconds + offsetSeconds,
                speaker = "Teil $partNumber · ${it.speaker}",
            ) },
        )
    }

    internal fun buildMergePrompt(
        episode: SharedEpisode,
        parts: List<PodcastAnalysis>,
        defaultContext: String,
        interestingFactsEnabled: Boolean,
    ): String {
        val sections = JSONArray().apply { parts.forEachIndexed { index, part ->
            put(JSONObject().put("section", index + 1).put("summary", part.summary)
                .put("topics", JSONArray().apply { part.topics.forEach { topic ->
                    put(JSONObject().put("question", topic.question).put("status", topic.status)
                        .put("answer", topic.answer).put("nuance", topic.nuance)
                        .put("evidence", JSONArray().apply { topic.evidence.forEach { evidence ->
                            put(JSONObject().put("timestampSeconds", evidence.timestampSeconds).put("quote", evidence.quote))
                        } }))
                } }))
        } }
        val facts = if (interestingFactsEnabled && defaultContext.isNotBlank())
            "Führe relevante, direkt belegte Fakten separat mit dem Status „Interessanter Fakt“ auf."
        else "Erzeuge keine Einträge mit dem Status „Interessanter Fakt“."
        return """
            Führe die folgenden chronologischen Teilanalysen einer Podcastfolge zu einer einzigen Gesamtanalyse auf Deutsch zusammen.
            Titel: ${episode.title}
            Podcast: ${episode.podcast}
            Beschreibung: ${episode.description}
            Standard-Kontext des Nutzers: $defaultContext

            Berücksichtige ALLE Abschnitte, auch die letzten. Fasse die gesamte Folge in summary zusammen.
            Vereinige gleiche Frage-Themen. Bewerte „Beantwortet“, „Teilweise beantwortet“ oder „Nicht beantwortet“ anhand ALLER Abschnitte.
            Eine in einem frühen Abschnitt unbeantwortete Frage kann später beantwortet werden. Bewahre Einschränkungen, Gegenargumente und Unsicherheiten unter nuance.
            Belege jede Antwort mit bis zu drei Belegen aus den Teilanalysen. Übernimm timestampSeconds und quote unverändert; alle Zeiten sind bereits absolute Positionen in der Originaldatei.
            Erfinde keine Belege oder Antworten und führe keinen externen Faktencheck durch. Aussagen der Sprecher bleiben Aussagen der Sprecher.
            $facts
            Gib nur summary und topics zurück. Das vollständige Transkript wird unverändert aus den Abschnitten übernommen.

            <section_analyses>
            $sections
            </section_analyses>
        """.trimIndent()
    }

    private fun responseSchema(includeTranscript: Boolean = true): JSONObject {
        fun string() = JSONObject().put("type", "string")
        fun integer() = JSONObject().put("type", "integer")
        val evidence = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("timestampSeconds", integer()).put("quote", string()))
            .put("required", JSONArray(listOf("timestampSeconds", "quote")))
        val topic = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("question", string()).put("status", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("Beantwortet", "Teilweise beantwortet", "Nicht beantwortet", "Interessanter Fakt"))))
                .put("answer", string()).put("nuance", string())
                .put("evidence", JSONObject().put("type", "array").put("items", evidence)))
            .put("required", JSONArray(listOf("question", "status", "answer", "nuance", "evidence")))
        val segment = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("startSeconds", integer()).put("endSeconds", integer())
                .put("speaker", string()).put("text", string()))
            .put("required", JSONArray(listOf("startSeconds", "endSeconds", "speaker", "text")))
        val properties = JSONObject().put("summary", string())
            .put("topics", JSONObject().put("type", "array").put("items", topic))
        val required = JSONArray(listOf("summary", "topics"))
        if (includeTranscript) {
            properties.put("transcript", JSONObject().put("type", "array").put("items", segment))
            required.put("transcript")
        }
        return JSONObject().put("type", "object").put("properties", properties).put("required", required)
    }

    private fun parseAnalysis(j: JSONObject): PodcastAnalysis {
        val topics = j.getJSONArray("topics")
        val transcript = j.getJSONArray("transcript")
        return PodcastAnalysis(
            summary = j.getString("summary"),
            topics = List(topics.length()) { i -> topics.getJSONObject(i).let { t ->
                val ev = t.getJSONArray("evidence")
                TopicAnswer(t.getString("question"), t.getString("status"), t.getString("answer"), t.getString("nuance"),
                    List(ev.length()) { k -> ev.getJSONObject(k).let { Evidence(it.getInt("timestampSeconds"), it.getString("quote")) } })
            } },
            transcript = List(transcript.length()) { i -> transcript.getJSONObject(i).let {
                TranscriptSegment(it.getInt("startSeconds"), it.getInt("endSeconds"), it.getString("speaker"), it.getString("text"))
            } },
        )
    }

    private suspend fun deleteFile(apiKey: String, name: String) {
        val request = Request.Builder().url("$apiBaseUrl/v1beta/$name")
            .header("x-goog-api-key", apiKey).delete().build()
        client.newCall(request).await().close()
    }

    private fun apiError(response: Response): IOException {
        val raw = response.body?.string().orEmpty()
        val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw)
        return IOException("Gemini-Fehler ${response.code}: ${message.take(500)}")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
}
