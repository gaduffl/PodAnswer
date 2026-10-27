package de.graulich.podanswer

data class SharedEpisode(
    val title: String = "",
    val podcast: String = "",
    val duration: String = "",
    val published: String = "",
    val episodeUrl: String = "",
    val mediaUrl: String = "",
    val podcastUrl: String = "",
    val description: String = "",
    val rssUrl: String = "",
    val localAudioPath: String? = null,
)

data class TranscriptSegment(
    val startSeconds: Int,
    val endSeconds: Int,
    val speaker: String,
    val text: String,
)

data class Evidence(
    val timestampSeconds: Int,
    val quote: String,
)

data class TopicAnswer(
    val question: String,
    val status: String,
    val answer: String,
    val nuance: String,
    val evidence: List<Evidence>,
)

data class PodcastAnalysis(
    val summary: String,
    val topics: List<TopicAnswer>,
    val transcript: List<TranscriptSegment>,
)

enum class JobStage { IDLE, DOWNLOADING, UPLOADING, PROCESSING, COMPLETE, ERROR }

data class EpisodeRecord(
    val id: String,
    val episode: SharedEpisode,
    val createdAt: Long,
    val analysis: PodcastAnalysis? = null,
    val stage: JobStage = JobStage.IDLE,
    val error: String? = null,
    val progressMessage: String = "",
)

data class AppState(
    val records: List<EpisodeRecord> = emptyList(),
    val selectedId: String? = null,
    val incoming: SharedEpisode? = null,
    val busyStage: JobStage = JobStage.IDLE,
    val busyMessage: String = "",
    val apiKeyConfigured: Boolean = false,
    val defaultContext: String = "",
    val interestingFactsEnabled: Boolean = false,
    val settingsOpen: Boolean = false,
)
