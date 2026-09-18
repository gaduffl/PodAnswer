package de.graulich.podanswer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            interestingFactsEnabled = false,
        )

        assertTrue(prompt.contains("exakt 3723.456 Sekunden lang"))
        assertTrue(prompt.contains("0 Sekunden ist der erste decodierbare Audioframe"))
        assertTrue(prompt.contains("nicht segmentweise aufsummiert"))
    }

    @Test
    fun promptIncludesSavedDefaultContextAsAnalysisFocus() {
        val prompt = client.buildPrompt(
            SharedEpisode(title = "Folge"),
            durationMillis = null,
            defaultContext = "Achte besonders auf Risiken und offene Fragen.",
            interestingFactsEnabled = false,
        )

        assertTrue(prompt.contains("<standard_context>"))
        assertTrue(prompt.contains("Achte besonders auf Risiken und offene Fragen."))
        assertTrue(prompt.contains("Themenauswahl, Kurzfassung, Antworten und Einordnung"))
        assertTrue(prompt.contains("Der Standard-Kontext beeinflusst trotzdem weiterhin"))
    }

    @Test
    fun promptRequestsSeparateInterestingFactsOnlyWhenEnabled() {
        val prompt = client.buildPrompt(
            SharedEpisode(title = "Folge"),
            durationMillis = null,
            defaultContext = "Achte besonders auf Risiken.",
            interestingFactsEnabled = true,
        )

        assertTrue(prompt.contains("status: exakt „Interessanter Fakt“"))
        assertTrue(prompt.contains("wiederhole keine Inhalte"))
        assertTrue(prompt.contains("Achte besonders auf Risiken."))
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
