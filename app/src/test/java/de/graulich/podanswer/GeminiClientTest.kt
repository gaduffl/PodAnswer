package de.graulich.podanswer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class GeminiClientTest {
    private val client = GeminiClient()

    @Test
    fun extractsStructuredJsonFromDocumentedInteractionsResponse() {
        val analysisJson = """{"summary":"Kurzfassung","topics":[],"transcript":[]}"""
        val response = JSONObject().apply {
            put("status", "completed")
            put("steps", org.json.JSONArray().put(JSONObject().apply {
                put("type", "model_output")
                put("content", org.json.JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", analysisJson)
                }))
            }))
        }

        val result = client.extractOutput(response)

        assertEquals("Kurzfassung", result.getString("summary"))
    }

    @Test
    fun rejectsIncompleteInteractionInsteadOfShowingGenericParseError() {
        val response = JSONObject().put("status", "incomplete")

        val error = assertThrows(IOException::class.java) { client.extractOutput(response) }

        assertEquals("Gemini hat nur ein unvollständiges Ergebnis erzeugt. Bitte erneut versuchen.", error.message)
    }


    @Test
    fun promptAnchorsTimestampsToMeasuredAudioTimeline() {
        val prompt = client.buildPrompt(
            SharedEpisode(title = "Folge"),
            durationMillis = 3_723_456,
            defaultContext = "",
        )

        assert(prompt.contains("exakt 3723.456 Sekunden lang"))
        assert(prompt.contains("0 Sekunden ist der erste decodierbare Audioframe"))
        assert(prompt.contains("nicht segmentweise aufsummiert"))
    }

    @Test
    fun promptIncludesSavedDefaultContextAsAnalysisFocus() {
        val prompt = client.buildPrompt(
            SharedEpisode(title = "Folge"),
            durationMillis = null,
            defaultContext = "Achte besonders auf Risiken und offene Fragen.",
        )

        assert(prompt.contains("<standard_context>"))
        assert(prompt.contains("Achte besonders auf Risiken und offene Fragen."))
        assert(prompt.contains("Themenauswahl, Kurzfassung, Antworten und Einordnung"))
    }

    @Test
    fun acceptsFencedJsonForCompatibility() {
        val response = JSONObject().put(
            "output_text",
            """```json
                {"summary":"Ergebnis","topics":[],"transcript":[]}
                ```""".trimIndent(),
        )

        assertEquals("Ergebnis", client.extractOutput(response).getString("summary"))
    }
}
