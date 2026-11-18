package de.graulich.podanswer

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a plain-text report that can be shown, copied and shared when an analysis fails. */
internal object ErrorReport {
    private const val MAX_STACK_TRACE_CHARS = 12_000

    fun build(
        throwable: Throwable,
        timestampMillis: Long,
        context: List<Pair<String, String>>,
        describeExtra: (Throwable) -> String? = { null },
    ): String = buildString {
        appendLine("Zeitpunkt: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(timestampMillis))}")
        context.filter { it.second.isNotBlank() }.forEach { (label, value) -> appendLine("$label: $value") }

        appendLine()
        appendLine("Ursachenkette:")
        causeChain(throwable).forEachIndexed { index, cause ->
            append("${index + 1}. ${cause.javaClass.name}")
            cause.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
            appendLine()
            describeExtra(cause)?.takeIf { it.isNotBlank() }?.lines()?.forEach { appendLine("   $it") }
        }

        appendLine()
        appendLine("Stacktrace:")
        append(stackTrace(throwable))
    }.trimEnd()

    internal fun causeChain(throwable: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = throwable
        while (current != null && current !in chain) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun stackTrace(throwable: Throwable): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return if (trace.length <= MAX_STACK_TRACE_CHARS) trace
        else trace.take(MAX_STACK_TRACE_CHARS) + "\n… (gekürzt, ${trace.length - MAX_STACK_TRACE_CHARS} Zeichen ausgelassen)"
    }
}
