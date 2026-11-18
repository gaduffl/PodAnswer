package de.graulich.podanswer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PodAnswerExportTest {
    private val analysis = PodcastAnalysis(
        summary = "Kurzfassung",
        topics = listOf(TopicAnswer("Lohnt sich ein CT?", "Beantwortet", "Ja.", "Ab 40.", listOf(Evidence(75, "It is worth it.")))),
        transcript = listOf(TranscriptSegment(0, 12, "Sprecher 1", "Welcome back.")),
    )
    private val done = EpisodeRecord(
        id = "a1", createdAt = 1_763_492_400_000,
        episode = SharedEpisode(title = "Episode 515: Calcium?", podcast = "Heart Doc VIP", duration = "27:02",
            mediaUrl = "https://example.com/515.mp3", rssUrl = "https://example.com/feed.xml", localAudioPath = "/data/x.mp3"),
        analysis = analysis, stage = JobStage.COMPLETE,
    )
    private val failed = EpisodeRecord("b2", SharedEpisode(title = "Failed"), 0, stage = JobStage.ERROR, error = "x")

    @Test
    fun writesOnlyCompletedAnalysesInTheTranscriberFormat() {
        val json = JSONObject(PodAnswerExport.toJson(listOf(done, failed), exportedAt = 0))
        assertEquals("podanswer-export", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals("1970-01-01T00:00:00Z", json.getString("exportedAt"))
        val episodes = json.getJSONArray("episodes")
        assertEquals(1, episodes.length())
        val record = episodes.getJSONObject(0)
        val episode = record.getJSONObject("episode")
        assertEquals("https://example.com/515.mp3", episode.getString("mediaUrl"))
        assertFalse(episode.has("localAudioPath"))
        val topic = record.getJSONObject("analysis").getJSONArray("topics").getJSONObject(0)
        assertEquals("Beantwortet", topic.getString("status"))
        assertEquals(75, topic.getJSONArray("evidence").getJSONObject(0).getInt("timestampSeconds"))
        assertEquals("Welcome back.", record.getJSONObject("analysis").getJSONArray("transcript").getJSONObject(0).getString("text"))
    }

    @Test
    fun namesFilesSafely() {
        assertEquals("Heart Doc VIP - Episode 515 Calcium - 1970-01-01.podanswer.json", PodAnswerExport.fileName(listOf(done), now = 0))
        assertEquals("PodAnswer-Export 1970-01-01.podanswer.json", PodAnswerExport.fileName(listOf(done, done.copy(id = "c")), now = 0))
    }
}
