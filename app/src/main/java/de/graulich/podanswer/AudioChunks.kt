package de.graulich.podanswer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AudioInfo(val durationMillis: Long, val mimeType: String)

internal data class AudioChunk(val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

internal object AudioChunks {
    const val MAX_DURATION_MILLIS = 6 * 60 * 60 * 1_000L
    const val CHUNK_DURATION_MILLIS = 20 * 60 * 1_000L

    fun plan(durationMillis: Long): List<AudioChunk> {
        if (durationMillis <= 0) throw IOException("Die Dauer der Audiodatei konnte nicht bestimmt werden.")
        if (durationMillis > MAX_DURATION_MILLIS) throw IOException("PodAnswer unterstützt Podcasts mit bis zu 6 Stunden Länge.")
        return (0L until durationMillis step CHUNK_DURATION_MILLIS).map { start ->
            AudioChunk(start, minOf(start + CHUNK_DURATION_MILLIS, durationMillis))
        }
    }
}

internal fun readAudioInfo(audio: File): AudioInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(audio.absolutePath)
        AudioInfo(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("Die Dauer der Audiodatei konnte nicht bestimmt werden."),
            when (val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)) {
                "audio/mp4", "video/mp4" -> "audio/m4a"
                null -> "audio/mpeg"
                else -> mime
            },
        )
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun interface AudioChunkExporter {
    suspend fun export(audio: File, chunk: AudioChunk): File
}

/** Export one section at a time so a six-hour source never needs to fit in memory or cache twice. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AndroidAudioChunkExporter(context: Context) : AudioChunkExporter {
    private val appContext = context.applicationContext

    override suspend fun export(audio: File, chunk: AudioChunk): File = withContext(Dispatchers.Main.immediate) {
        val output = File.createTempFile("podanswer-chunk-", ".m4a", appContext.cacheDir)
        // Transformer requires a new output path.
        output.delete()
        try {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(appContext)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(output)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(
                                IOException("Der Audioabschnitt konnte nicht vorbereitet werden.", exportException)
                            )
                        }
                    })
                    .build()
                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        transformer.cancel()
                        output.delete()
                    }
                }
                val item = MediaItem.Builder().setUri(Uri.fromFile(audio))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(chunk.startMillis)
                        .setEndPositionMs(chunk.endMillis)
                        .build())
                    .build()
                transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), output.absolutePath)
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}
