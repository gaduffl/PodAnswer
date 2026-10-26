package de.graulich.podanswer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class LongPodcastTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun processesSixHoursAndMergesLateAnswerWithoutRegeneratingTranscript() = withServer { server, api ->
        val sections = mutableListOf<AudioChunk>()
        val temporaryAudio = mutableListOf<File>()
        val progress = mutableListOf<String>()
        val source = temporaryFolder.newFile("episode.mp3").apply { writeText("source") }
        val client = client(server, 21_600_000) { _, chunk ->
            sections += chunk
            temporaryFolder.newFile().apply { writeText("section"); temporaryAudio += this }
        }

        val result = runBlocking {
            client.analyze("key", source, SharedEpisode(title = "Frage"), "Risiken", true) { _, message -> progress += message }
        }

        assertEquals(18, sections.size)
        assertEquals(18, result.transcript.size)
        assertEquals(0, result.transcript.first().startSeconds)
        assertEquals(21_600, result.transcript.last().endSeconds)
        assertEquals("Teil 18 · Sprecher 1", result.transcript.last().speaker)
        assertEquals("Späte Antwort", result.topics.single().answer)
        assertEquals("Beantwortet", result.topics.single().status)
        assertEquals(20_410, result.topics.single().evidence.single().timestampSeconds)
        assertEquals(18, api.deleted.size)
        assertTrue(temporaryAudio.none { it.exists() })
        assertTrue(source.exists())
        assertTrue(progress.any { it.contains("Abschnitt 18/18") })
        assertEquals(19, api.interactions.size)
        val merge = api.interactions.last()
        assertEquals(1, merge.getJSONArray("input").length())
        val prompt = merge.getJSONArray("input").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("Zusammenfassung 1"))
        assertTrue(prompt.contains("Zusammenfassung 18"))
        assertTrue(prompt.contains("Nicht beantwortet"))
        assertTrue(prompt.contains("20410"))
        assertFalse(merge.getJSONObject("response_format").getJSONObject("schema").getJSONObject("properties").has("transcript"))
        assertTrue(api.interactions.dropLast(1).all {
            it.getJSONArray("input").getJSONObject(0).getString("mime_type") == "audio/m4a"
        })
    }

    @Test
    fun incompleteLaterSectionFailsAndDeletesTemporaryAndRemoteAudio() = withServer { server, api ->
        api.incompleteAt = 2
        val exported = mutableListOf<File>()
        val source = temporaryFolder.newFile("source.mp3")
        val client = client(server, 2_400_001) { _, _ -> temporaryFolder.newFile().also { exported += it } }

        assertThrows(IOException::class.java) {
            runBlocking { client.analyze("key", source, SharedEpisode(), "", false) { _, _ -> } }
        }

        assertEquals(2, exported.size)
        assertTrue(exported.none { it.exists() })
        assertEquals(2, api.deleted.size)
        assertEquals(2, api.interactions.size)
        assertTrue(source.exists())
    }

    @Test
    fun preservesShortFinalSectionAndItsOriginalAudioOffset() = withServer { server, api ->
        val sections = mutableListOf<AudioChunk>()
        val client = client(server, 1_200_500) { _, chunk ->
            sections += chunk
            temporaryFolder.newFile()
        }

        val result = runBlocking { client.analyze("key", temporaryFolder.newFile(), SharedEpisode(), "", false) { _, _ -> } }

        assertEquals(listOf(AudioChunk(0, 1_200_000), AudioChunk(1_200_000, 1_200_500)), sections)
        assertEquals(1_200, result.transcript.last().startSeconds)
        assertEquals(1_201, result.transcript.last().endSeconds)
        assertEquals(1_200, result.topics.single().evidence.single().timestampSeconds)
        assertEquals(2, api.deleted.size)
    }

    @Test
    fun cancellationAfterUploadStillDeletesRemoteAndTemporaryAudio() = withServer { server, api ->
        val exported = mutableListOf<File>()
        val client = client(server, 1_200_001) { _, _ -> temporaryFolder.newFile().also { exported += it } }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                val job = coroutineContext[kotlinx.coroutines.Job]!!
                client.analyze("key", temporaryFolder.newFile(), SharedEpisode(), "", false) { _, message ->
                    if (message.contains("transkribiert")) job.cancel(CancellationException("cancel"))
                }
            }
        }

        assertFalse(exported.single().exists())
        assertEquals(1, api.deleted.size)
        assertTrue(api.interactions.isEmpty())
    }

    @Test
    fun overSixHoursIsRejectedBeforeExportOrUpload() = withServer { server, api ->
        val client = client(server, 21_600_001) { _, _ -> error("Must not export") }

        assertThrows(IOException::class.java) {
            runBlocking { client.analyze("key", temporaryFolder.newFile(), SharedEpisode(), "", false) { _, _ -> } }
        }

        assertEquals(0, server.requestCount)
        assertTrue(api.interactions.isEmpty())
    }

    @Test
    fun shortEpisodeUsesOriginalAudioAndKeepsExistingSpeakerLabels() = withServer { server, api ->
        val source = temporaryFolder.newFile("short.mp3")
        val client = client(server, 1_200_000) { _, _ -> error("Must not export") }

        val result = runBlocking { client.analyze("key", source, SharedEpisode(), "", false) { _, _ -> } }

        assertEquals("Sprecher 1", result.transcript.single().speaker)
        assertEquals(1, api.interactions.size)
        assertEquals(1, api.deleted.size)
        assertEquals("audio/mpeg", api.interactions.single().getJSONArray("input").getJSONObject(0).getString("mime_type"))
        assertTrue(source.exists())
    }

    @Test
    fun mergeCannotInventEvidence() = withServer { server, api ->
        api.inventEvidence = true
        val client = client(server, 2_400_000) { _, _ -> temporaryFolder.newFile() }

        assertThrows(IOException::class.java) {
            runBlocking { client.analyze("key", temporaryFolder.newFile(), SharedEpisode(), "", false) { _, _ -> } }
        }
        assertEquals(2, api.deleted.size)
    }

    private fun client(server: MockWebServer, duration: Long, exporter: AudioChunkExporter) = GeminiClient(
        audioChunkExporter = exporter,
        audioInfoReader = { AudioInfo(duration, "audio/mpeg") },
        apiBaseUrl = server.url("/").toString().removeSuffix("/"),
        client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
    )

    private fun withServer(test: (MockWebServer, FakeGemini) -> Unit) {
        MockWebServer().use { server ->
            val api = FakeGemini(server)
            server.dispatcher = api
            server.start()
            test(server, api)
        }
    }

    private class FakeGemini(private val server: MockWebServer) : Dispatcher() {
        val interactions = CopyOnWriteArrayList<JSONObject>()
        val deleted = CopyOnWriteArrayList<String>()
        var incompleteAt: Int? = null
        var inventEvidence = false
        private var uploaded = 0
        private var lastTimestamp = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path == "/upload/v1beta/files" -> MockResponse().addHeader("X-Goog-Upload-URL", server.url("/upload"))
                path == "/upload" -> {
                    uploaded++
                    json(JSONObject().put("file", JSONObject().put("name", "files/$uploaded").put("uri", "gs://audio/$uploaded")))
                }
                request.method == "DELETE" -> {
                    deleted += path
                    json(JSONObject())
                }
                path.startsWith("/v1beta/files/") -> json(JSONObject().put("state", "ACTIVE"))
                path == "/v1beta/interactions" -> {
                    val body = JSONObject(request.body.readUtf8())
                    interactions += body
                    if (interactions.size == incompleteAt) json(JSONObject().put("status", "incomplete"))
                    else {
                        val merge = body.getJSONArray("input").length() == 1
                        val prompt = body.getJSONArray("input").getJSONObject(if (merge) 0 else 1).getString("text")
                        val duration = Regex("exakt ([0-9.]+) Sekunden lang").find(prompt)?.groupValues?.get(1)?.toDouble() ?: 1_200.0
                        val timestamp = if (merge) lastTimestamp else minOf(10, duration.toInt())
                        if (!merge) lastTimestamp = (uploaded - 1) * 1_200 + timestamp
                        val evidence = JSONObject().put("timestampSeconds", timestamp)
                            .put("quote", if (inventEvidence && merge) "Erfunden" else "Zitat $uploaded")
                        val unanswered = !merge && uploaded == 1
                        val topic = JSONObject().put("question", "Frage").put("status", if (unanswered) "Nicht beantwortet" else "Beantwortet")
                            .put("answer", if (merge) "Späte Antwort" else if (unanswered) "" else "Antwort $uploaded").put("nuance", "")
                            .put("evidence", if (unanswered) JSONArray() else JSONArray().put(evidence))
                        val result = JSONObject().put("summary", "Zusammenfassung $uploaded").put("topics", JSONArray().put(topic))
                        if (!merge) result.put("transcript", JSONArray().put(JSONObject()
                            .put("startSeconds", 0).put("endSeconds", kotlin.math.ceil(duration).toInt()).put("speaker", "Sprecher 1").put("text", "Text $uploaded")))
                        json(JSONObject().put("status", "completed").put("output_text", result.toString()))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
}
