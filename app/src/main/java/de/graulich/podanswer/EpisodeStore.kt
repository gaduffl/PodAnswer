package de.graulich.podanswer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EpisodeStore(context: Context) {
    private val file = File(context.filesDir, "episodes.json")

    @Synchronized
    fun load(): List<EpisodeRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        List(array.length()) { recordFromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(records: List<EpisodeRecord>) {
        val array = JSONArray().apply { records.forEach { put(recordToJson(it)) } }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(array.toString())
            temporary.delete()
        }
    }

    private fun recordToJson(record: EpisodeRecord) = JSONObject().apply {
        put("id", record.id)
        put("createdAt", record.createdAt)
        put("stage", record.stage.name)
        put("error", record.error)
        put("episode", episodeToJson(record.episode))
        record.analysis?.let { put("analysis", analysisToJson(it)) }
    }

    private fun recordFromJson(json: JSONObject) = EpisodeRecord(
        id = json.getString("id"),
        createdAt = json.getLong("createdAt"),
        stage = runCatching { JobStage.valueOf(json.optString("stage")) }.getOrDefault(JobStage.IDLE),
        error = json.optString("error").takeIf { it.isNotBlank() && it != "null" },
        episode = episodeFromJson(json.getJSONObject("episode")),
        analysis = json.optJSONObject("analysis")?.let(::analysisFromJson),
    )

    private fun episodeToJson(e: SharedEpisode) = JSONObject().apply {
        put("title", e.title); put("podcast", e.podcast); put("duration", e.duration)
        put("published", e.published); put("episodeUrl", e.episodeUrl); put("mediaUrl", e.mediaUrl)
        put("podcastUrl", e.podcastUrl); put("description", e.description); put("rssUrl", e.rssUrl)
        put("localAudioPath", e.localAudioPath)
    }

    private fun episodeFromJson(j: JSONObject) = SharedEpisode(
        title = j.optString("title"), podcast = j.optString("podcast"), duration = j.optString("duration"),
        published = j.optString("published"), episodeUrl = j.optString("episodeUrl"),
        mediaUrl = j.optString("mediaUrl"), podcastUrl = j.optString("podcastUrl"),
        description = j.optString("description"), rssUrl = j.optString("rssUrl"),
        localAudioPath = j.optString("localAudioPath").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun analysisToJson(a: PodcastAnalysis) = JSONObject().apply {
        put("summary", a.summary)
        put("topics", JSONArray().apply { a.topics.forEach { topic -> put(JSONObject().apply {
            put("question", topic.question); put("status", topic.status); put("answer", topic.answer); put("nuance", topic.nuance)
            put("evidence", JSONArray().apply { topic.evidence.forEach { e -> put(JSONObject().apply {
                put("timestampSeconds", e.timestampSeconds); put("quote", e.quote)
            }) } })
        }) } })
        put("transcript", JSONArray().apply { a.transcript.forEach { s -> put(JSONObject().apply {
            put("startSeconds", s.startSeconds); put("endSeconds", s.endSeconds); put("speaker", s.speaker); put("text", s.text)
        }) } })
    }

    private fun analysisFromJson(j: JSONObject): PodcastAnalysis {
        val topics = j.optJSONArray("topics") ?: JSONArray()
        val transcript = j.optJSONArray("transcript") ?: JSONArray()
        return PodcastAnalysis(
            summary = j.optString("summary"),
            topics = List(topics.length()) { i -> topics.getJSONObject(i).let { t ->
                val evidence = t.optJSONArray("evidence") ?: JSONArray()
                TopicAnswer(t.optString("question"), t.optString("status"), t.optString("answer"), t.optString("nuance"),
                    List(evidence.length()) { k -> evidence.getJSONObject(k).let { Evidence(it.optInt("timestampSeconds"), it.optString("quote")) } })
            } },
            transcript = List(transcript.length()) { i -> transcript.getJSONObject(i).let {
                TranscriptSegment(it.optInt("startSeconds"), it.optInt("endSeconds"), it.optString("speaker"), it.optString("text"))
            } },
        )
    }
}
