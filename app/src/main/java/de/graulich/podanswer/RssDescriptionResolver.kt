package de.graulich.podanswer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class RssDescriptionResolver {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun enrich(episode: SharedEpisode): SharedEpisode = withContext(Dispatchers.IO) {
        if (episode.rssUrl.isBlank()) return@withContext episode
        runCatching {
            val request = Request.Builder().url(episode.rssUrl).header("User-Agent", "PodAnswer/0.1").build()
            val xml = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching episode
                response.body?.string().orEmpty()
            }
            val items = parse(xml)
            val match = items.firstOrNull { item ->
                item.title.equals(episode.title, ignoreCase = true) ||
                    (episode.episodeUrl.isNotBlank() && item.link.substringBefore('?') == episode.episodeUrl.substringBefore('?')) ||
                    (episode.mediaUrl.isNotBlank() && item.media.substringBefore('?') == episode.mediaUrl.substringBefore('?'))
            }
            match?.description?.takeIf { it.isNotBlank() }?.let { episode.copy(description = stripHtml(it)) } ?: episode
        }.getOrDefault(episode)
    }

    private data class Item(val title: String, val link: String, val media: String, val description: String)

    private fun parse(xml: String): List<Item> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val result = mutableListOf<Item>()
        var insideItem = false
        var title = ""; var link = ""; var media = ""; var description = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item", "entry" -> { insideItem = true; title = ""; link = ""; media = ""; description = "" }
                    "title" -> if (insideItem) title = parser.nextText()
                    "description", "summary", "content:encoded", "encoded" -> if (insideItem) description = parser.nextText()
                    "link" -> if (insideItem) link = parser.getAttributeValue(null, "href") ?: parser.nextText()
                    "enclosure" -> if (insideItem) media = parser.getAttributeValue(null, "url").orEmpty()
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.lowercase() in setOf("item", "entry")) {
                result += Item(title, link, media, description)
                insideItem = false
            }
            parser.next()
        }
        return result
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
