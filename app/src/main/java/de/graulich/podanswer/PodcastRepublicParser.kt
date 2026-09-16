package de.graulich.podanswer

object PodcastRepublicParser {
    private val fields = setOf("Duration", "Published", "Episode", "Media", "Podcast", "Subscribe to this podcast")

    fun parse(text: String): SharedEpisode {
        val lines = text.lines().map { it.trim() }
        val sentIndex = lines.indexOfFirst { it.startsWith("Sent from Podcast Republic", ignoreCase = true) }
        val relevant = if (sentIndex >= 0) lines.take(sentIndex) else lines

        fun field(name: String): String = relevant.firstOrNull {
            it.startsWith("$name:", ignoreCase = true)
        }?.substringAfter(':')?.trim().orEmpty()

        val firstField = relevant.indexOfFirst { line -> fields.any { line.startsWith("$it:", true) } }
        val header = relevant.take(if (firstField >= 0) firstField else relevant.size).filter { it.isNotBlank() }
        val title = header.firstOrNull().orEmpty()
        val podcast = header.drop(1).firstOrNull().orEmpty()
        val description = relevant.firstOrNull { it.startsWith("<p>") }
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim().orEmpty()

        return SharedEpisode(
            title = title,
            podcast = podcast,
            duration = field("Duration"),
            published = field("Published"),
            episodeUrl = field("Episode"),
            mediaUrl = field("Media"),
            podcastUrl = field("Podcast"),
            description = description,
            rssUrl = field("Subscribe to this podcast"),
        )
    }
}
