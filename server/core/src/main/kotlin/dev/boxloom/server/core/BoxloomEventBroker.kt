package dev.boxloom.server.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BoxloomEventBroker(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val revision = MutableStateFlow(0L)
    private val events = ArrayDeque<ChatMessageEvent>()
    private var instanceId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var closed = false

    init {
        require(capacity > 0) { "Event broker capacity must be positive" }
    }

    fun publishChatMessage(message: String, username: String, uuid: String): ChatMessageEvent =
        lock.withLock {
            check(!closed) { "The event broker is closed" }
            sequence = Math.incrementExact(sequence)

            val event = ChatMessageEvent(
                EventCursor(instanceId, sequence),
                clock.instant(),
                message,
                username,
                uuid,
            )
            events.addLast(event)

            while (events.size > capacity) {
                events.removeFirst()
            }

            signalChanged()
            event
        }

    fun openCursor(lastEventId: String?): EventCursor = lock.withLock {
        check(!closed) { "The event broker is closed" }

        if (lastEventId.isNullOrBlank()) {
            EventCursor(instanceId, sequence)
        } else {
            parseCursor(lastEventId).also(::validateCursor)
        }
    }

    suspend fun awaitAfter(cursor: EventCursor, timeout: Duration): List<ChatMessageEvent>? {
        require(!timeout.isNegative) { "Event wait timeout must not be negative" }

        while (true) {
            val snapshot = lock.withLock {
                if (closed) return null
                validateCursor(cursor)

                val available = events.filter { it.cursor.sequence > cursor.sequence }
                if (available.isNotEmpty()) return available
                revision.value
            }

            if (timeout.isZero) return emptyList()
            val changed = withTimeoutOrNull(timeout.toMillis()) {
                revision.first { it != snapshot }
            }
            if (changed == null) return emptyList()
        }
    }

    fun reset() {
        lock.withLock {
            if (closed) return
            instanceId = UUID.randomUUID().toString()
            sequence = 0
            events.clear()
            signalChanged()
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            events.clear()
            signalChanged()
        }
    }

    private fun signalChanged() {
        revision.value = Math.incrementExact(revision.value)
    }

    private fun parseCursor(value: String): EventCursor {
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) {
            throw InvalidEventCursorException("Last-Event-ID is not a valid boxloom event cursor")
        }

        val parsedInstanceId = value.substring(0, separator)
        val parsedSequence = value.substring(separator + 1).toLongOrNull()
            ?: throw InvalidEventCursorException(
                "Last-Event-ID is not a valid boxloom event cursor",
            )

        try {
            UUID.fromString(parsedInstanceId)
        } catch (exception: IllegalArgumentException) {
            throw InvalidEventCursorException(
                "Last-Event-ID is not a valid boxloom event cursor",
            )
        }

        if (parsedSequence < 0) {
            throw InvalidEventCursorException("Last-Event-ID sequence must not be negative")
        }

        return EventCursor(parsedInstanceId, parsedSequence)
    }

    private fun validateCursor(cursor: EventCursor) {
        if (cursor.instanceId != instanceId) {
            throw EventCursorExpiredException("The event cursor belongs to another server session")
        }
        if (cursor.sequence > sequence) {
            throw InvalidEventCursorException("Last-Event-ID is ahead of the event stream")
        }

        val earliestAvailable = events.firstOrNull()?.cursor?.sequence ?: sequence + 1
        if (cursor.sequence < earliestAvailable - 1) {
            throw EventCursorExpiredException("The requested events are no longer retained")
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 1_024
    }
}

class EventCursor internal constructor(
    internal val instanceId: String,
    internal val sequence: Long,
) {
    override fun toString(): String = "$instanceId:$sequence"
}

class ChatMessageEvent internal constructor(
    val cursor: EventCursor,
    val timestamp: Instant,
    val message: String,
    val username: String,
    val uuid: String,
) {
    val id: String
        get() = cursor.toString()
}

class InvalidEventCursorException(message: String) : IllegalArgumentException(message)

class EventCursorExpiredException(message: String) : IllegalStateException(message)
