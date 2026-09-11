package dev.boxloom.server.core.http

import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal class SseWriter(
    private val output: OutputStream,
) {
    fun retry(milliseconds: Long) {
        write("retry: $milliseconds\n\n")
    }

    fun heartbeat() {
        write(": keepalive\n\n")
    }

    fun event(eventType: String, id: String?, data: String) {
        val frame = buildString {
            append("event: ").append(eventType).append('\n')
            id?.let { append("id: ").append(it).append('\n') }
            data.lineSequence().forEach { line ->
                append("data: ").append(line).append('\n')
            }
            append('\n')
        }
        write(frame)
    }

    private fun write(value: String) {
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }
}
