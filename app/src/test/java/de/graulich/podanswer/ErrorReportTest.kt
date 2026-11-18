package de.graulich.podanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorReportTest {
    @Test
    fun reportListsContextCauseChainAndExtraDetails() {
        val root = IllegalStateException("Codec konnte nicht gestartet werden")
        val error = AudioChunkExportException(AudioChunk(1_200_000, 2_400_000), root)

        val report = ErrorReport.build(
            throwable = error,
            timestampMillis = 0,
            context = listOf("Phase" to "PROCESSING", "Leer" to ""),
            describeExtra = { if (it is AudioChunkExportException) "Abschnitt: ${it.chunk.startMillis}" else null },
        )

        assertTrue(report.contains("Phase: PROCESSING"))
        assertFalse(report.contains("Leer:"))
        assertTrue(report.contains("1. de.graulich.podanswer.AudioChunkExportException: Der Audioabschnitt konnte nicht vorbereitet werden."))
        assertTrue(report.contains("   Abschnitt: 1200000"))
        assertTrue(report.contains("2. java.lang.IllegalStateException: Codec konnte nicht gestartet werden"))
        assertTrue(report.contains("Stacktrace:"))
    }

    @Test
    fun causeChainStopsOnCycles() {
        val first = IOException("a")
        val second = IOException("b", first)
        first.initCause(second)
        assertEquals(listOf(first, second), ErrorReport.causeChain(first))
    }
}
