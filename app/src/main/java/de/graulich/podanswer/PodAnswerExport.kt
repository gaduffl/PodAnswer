package de.graulich.podanswer

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exchange format read by Podcast Transcriber's "Import from PodAnswer".
 * Files are saved with the `.podanswer.json` suffix, typically into a OneDrive folder.
 * Local file paths are left out on purpose; the media URL identifies the episode.
 */
object PodAnswerExport {
    const val FORMAT = "podanswer-export"
    const val VERSION = 1
    const val MIME_TYPE = "application/json"
    const val SUFFIX = ".podanswer.json"

    fun exportable(records: List<EpisodeRecord>): List<EpisodeRecord> =
        records.filter { it.stage == JobStage.COMPLETE && it.analysis != null }

    fun toJson(records: List<EpisodeRecord>, exportedAt: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", isoUtc(exportedAt))
            put("episodes", JSONArray().apply { exportable(records).forEach { put(recordJson(it)) } })
        }.toString(2)

    fun fileName(records: List<EpisodeRecord>, now: Long = System.currentTimeMillis()): String {
        val single = exportable(records).singleOrNull()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
        val base = if (single != null) {
            listOf(single.episode.podcast, single.episode.title, day).filter { it.isNotBlank() }.joinToString(" - ")
        } else "PodAnswer-Export $day"
        return sanitize(base) + SUFFIX
    }

    internal fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120).trimEnd('.', ' ')
            .ifBlank { "PodAnswer" }

    private fun recordJson(record: EpisodeRecord) = JSONObject().apply {
        val e = record.episode
        val a = record.analysis!!
        put("id", record.id)
        put("createdAt", record.createdAt)
        put("episode", JSONObject().apply {
            put("title", e.title); put("podcast", e.podcast); put("duration", e.duration)
            put("published", e.published); put("episodeUrl", e.episodeUrl); put("mediaUrl", e.mediaUrl)
            put("podcastUrl", e.podcastUrl); put("description", e.description); put("rssUrl", e.rssUrl)
        })
        put("analysis", JSONObject().apply {
            put("summary", a.summary)
            put("topics", JSONArray().apply { a.topics.forEach { t -> put(JSONObject().apply {
                put("question", t.question); put("status", t.status); put("answer", t.answer); put("nuance", t.nuance)
                put("evidence", JSONArray().apply { t.evidence.forEach { ev -> put(JSONObject().apply {
                    put("timestampSeconds", ev.timestampSeconds); put("quote", ev.quote)
                }) } })
            }) } })
            put("transcript", JSONArray().apply { a.transcript.forEach { s -> put(JSONObject().apply {
                put("startSeconds", s.startSeconds); put("endSeconds", s.endSeconds); put("speaker", s.speaker); put("text", s.text)
            }) } })
        })
    }

    private fun isoUtc(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
