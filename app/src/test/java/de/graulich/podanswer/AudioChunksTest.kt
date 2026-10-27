package de.graulich.podanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AudioChunksTest {
    @Test
    fun sixHoursAreCoveredExactlyOnceByEighteenSections() {
        val chunks = AudioChunks.plan(21_600_000)

        assertEquals(18, chunks.size)
        assertEquals(0L, chunks.first().startMillis)
        assertEquals(21_600_000L, chunks.last().endMillis)
        assertTrue(chunks.all { it.durationMillis == 1_200_000L })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.endMillis == b.startMillis })
    }

    @Test
    fun retainsFractionalFinalSectionWithoutAddingEmptySection() {
        assertEquals(listOf(AudioChunk(0, 1_200_000)), AudioChunks.plan(1_200_000))
        assertEquals(listOf(AudioChunk(0, 1_200_000), AudioChunk(1_200_000, 1_200_001)), AudioChunks.plan(1_200_001))
        assertEquals(listOf(AudioChunk(0, 500)), AudioChunks.plan(500))
    }

    @Test
    fun rejectsOverSixHoursAndInvalidDuration() {
        for (duration in listOf(21_600_001L, Long.MAX_VALUE, 0L, -1L)) {
            assertThrows(IOException::class.java) { AudioChunks.plan(duration) }
        }
    }

    @Test
    fun shiftsEvidenceAndTranscriptToOriginalTimelineIncludingLastSection() {
        val result = GeminiClient().offsetAnalysis(
            PodcastAnalysis("", listOf(TopicAnswer("Frage", "Beantwortet", "Antwort", "", listOf(Evidence(10, "Zitat")))),
                listOf(TranscriptSegment(0, 1_200, "Sprecher 1", "Text"))),
            AudioChunk(20_400_000, 21_600_000), 18,
        )

        assertEquals(20_410, result.topics.single().evidence.single().timestampSeconds)
        assertEquals(20_400, result.transcript.single().startSeconds)
        assertEquals(21_600, result.transcript.single().endSeconds)
        assertEquals("Teil 18 · Sprecher 1", result.transcript.single().speaker)
    }

    @Test
    fun rejectsSectionTimestampsOutsideTheSection() {
        for (segment in listOf(TranscriptSegment(-1, 1, "", ""), TranscriptSegment(5, 4, "", ""), TranscriptSegment(0, 11, "", ""))) {
            assertThrows(IOException::class.java) {
                GeminiClient().offsetAnalysis(PodcastAnalysis("", emptyList(), listOf(segment)), AudioChunk(0, 10_000), 1)
            }
        }
        assertThrows(IOException::class.java) {
            GeminiClient().offsetAnalysis(PodcastAnalysis("", listOf(TopicAnswer("", "", "", "", listOf(Evidence(11, "")))), emptyList()), AudioChunk(0, 10_000), 1)
        }
    }
}
