package de.graulich.podanswer

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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

class GeminiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun analyze(
        apiKey: String,
        audio: File,
        episode: SharedEpisode,
        defaultContext: String,
        interestingFactsEnabled: Boolean,
        onStage: (JobStage, String) -> Unit,
    ): PodcastAnalysis {
        onStage(JobStage.UPLOADING, "Audio wird zu Gemini hochgeladen …")
        val durationMillis = readAudioDurationMillis(audio)
        val uploaded = uploadFile(apiKey, audio)
        return try {
            val active = waitUntilActive(apiKey, uploaded.first, uploaded.second)
            onStage(JobStage.PROCESSING, "Podcast wird transkribiert und ausgewertet …")
            generate(apiKey, active.second, episode, durationMillis, defaultContext, interestingFactsEnabled)
        } finally {
            runCatching { deleteFile(apiKey, uploaded.first) }
        }
    }

    private suspend fun uploadFile(apiKey: String, file: File): Pair<String, String> {
        val mime = "audio/mpeg"
        val metadata = JSONObject().put("file", JSONObject().put("display_name", file.name)).toString()
        val start = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(metadata.toRequestBody("application/json".toMediaType()))
            .build()
        val startResponse = client.newCall(start).await()
        if (!startResponse.isSuccessful) throw apiError(startResponse)
        val uploadUrl = startResponse.header("X-Goog-Upload-URL")
            ?: throw IOException("Gemini hat keine Upload-Adresse zurückgegeben.")
        startResponse.close()

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
        repeat(60) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/$name")
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

    private suspend fun generate(
        apiKey: String,
        uri: String,
        episode: SharedEpisode,
        durationMillis: Long?,
        defaultContext: String,
        interestingFactsEnabled: Boolean,
    ): PodcastAnalysis {
        val prompt = buildPrompt(episode, durationMillis, defaultContext, interestingFactsEnabled)
        val body = JSONObject().apply {
            put("model", "gemini-3.8-flash")
            put("input", JSONArray()
                .put(JSONObject().put("type", "audio").put("uri", uri).put("mime_type", "audio/mp3"))
                .put(JSONObject().put("type", "text").put("text", prompt)))
            put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", responseSchema()))
            put("generation_config", JSONObject()
                .put("max_output_tokens", 65_536)
                .put("thinking_level", "low"))
        }.toString()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw apiError(response)
            val raw = response.body?.string().orEmpty()
            if (raw.isBlank()) throw IOException("Gemini hat eine leere Antwort zurückgegeben.")
            return parseAnalysis(extractOutput(JSONObject(raw)))
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
            3. Beantworte jedes Thema ausschließlich anhand der Audiodatei. Gib den Status exakt als „Beantwortet“, „Teilweise beantwortet“ oder „Nicht beantwortet“ an.
            4. Führe relevante Einschränkungen, Gegenargumente und Unsicherheiten unter nuance auf.
            5. Belege jede inhaltliche Antwort mit bis zu drei kurzen, wortgetreuen Transkriptausschnitten und ihrer absoluten Zeit in Sekunden. Wenn es keinen Beleg gibt, verwende eine leere Belegliste.
            6. Stelle Aussagen der Sprecher als Aussagen der Sprecher dar. Führe keinen externen Faktencheck durch.
        """.trimIndent()
    }

    private fun readAudioDurationMillis(audio: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audio.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun responseSchema(): JSONObject {
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
        return JSONObject().put("type", "object")
            .put("properties", JSONObject().put("summary", string())
                .put("topics", JSONObject().put("type", "array").put("items", topic))
                .put("transcript", JSONObject().put("type", "array").put("items", segment)))
            .put("required", JSONArray(listOf("summary", "topics", "transcript")))
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
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/$name")
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
