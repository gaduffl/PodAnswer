package de.graulich.podanswer

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastRepublicParserTest {
    @Test
    fun parsesPodcastRepublicShare() {
        val shared = """
            Episode 515: New Data on Calcium Scoring CT Scans and #TestNotGuess
            Heart Doc VIP with Dr. Joel Kahn
            Duration: 27:02
            Published: Tue, 15 Sep 2026 07:00:00 +0000
            Episode: https://heartdocvip.libsyn.com/episode-515-new-data-on-calcium-scoring-ct-scans-and-testnotguess

            Media: https://traffic.libsyn.com/secure/heartdocvip/heartdocvip_091526_drjoelkahn.mp3?dest-id=551546

            Podcast: https://www.podcastrepublic.net/podcast/1268172276

            <p>This week, Dr. Kahn discusses two large new studies.</p>

            Subscribe to this podcast: https://rss.libsyn.com/shows/103460/destinations/551546.xml

            ----
            Sent from Podcast Republic 26.9.8R
        """.trimIndent()

        val result = PodcastRepublicParser.parse(shared)

        assertEquals("Episode 515: New Data on Calcium Scoring CT Scans and #TestNotGuess", result.title)
        assertEquals("Heart Doc VIP with Dr. Joel Kahn", result.podcast)
        assertEquals("27:02", result.duration)
        assertEquals("https://traffic.libsyn.com/secure/heartdocvip/heartdocvip_091526_drjoelkahn.mp3?dest-id=551546", result.mediaUrl)
        assertEquals("This week, Dr. Kahn discusses two large new studies.", result.description)
    }
}
