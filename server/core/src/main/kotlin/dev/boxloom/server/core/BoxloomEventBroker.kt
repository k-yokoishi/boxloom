package dev.boxloom.server.core

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
    private val changed = lock.newCondition()
    private val events = ArrayDeque<BoxloomEvent>()
    private var instanceId = UUID.randomUUID().toString()
    private var sequence = 0L
    private var closed = false

    init {
        require(capacity > 0) { "Event broker capacity must be positive" }
    }

    fun publishChatMessage(message: String, username: String, uuid: String): ChatMessageEvent =
        publish { cursor, timestamp ->
            ChatMessageEvent(cursor, timestamp, message, username, uuid)
        }

    fun publishPlayerDeath(
        message: String,
        username: String,
        uuid: String,
        dimension: String,
        x: Double,
        y: Double,
        z: Double,
    ): PlayerDeathEvent = publish { cursor, timestamp ->
        PlayerDeathEvent(cursor, timestamp, message, username, uuid, dimension, x, y, z)
    }

    fun publishPlayerSwing(context: PlayerActionContext): PlayerSwingEvent =
        publish { cursor, timestamp -> PlayerSwingEvent(cursor, timestamp, context) }

    fun publishBlockAttack(context: PlayerActionContext, target: BlockTarget): BlockAttackEvent =
        publish { cursor, timestamp -> BlockAttackEvent(cursor, timestamp, context, target) }

    fun publishBlockUse(context: PlayerActionContext, target: BlockTarget): BlockUseEvent =
        publish { cursor, timestamp -> BlockUseEvent(cursor, timestamp, context, target) }

    fun publishItemUse(context: PlayerActionContext): ItemUseEvent =
        publish { cursor, timestamp -> ItemUseEvent(cursor, timestamp, context) }

    private fun <T : BoxloomEvent> publish(create: (EventCursor, Instant) -> T): T =
        lock.withLock {
            check(!closed) { "The event broker is closed" }
            sequence = Math.incrementExact(sequence)

            val event = create(EventCursor(instanceId, sequence), clock.instant())
            events.addLast(event)

            while (events.size > capacity) {
                events.removeFirst()
            }

            changed.signalAll()
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

    fun awaitAfter(cursor: EventCursor, timeout: Duration): List<BoxloomEvent>? {
        return lock.withLock {
            require(!timeout.isNegative) { "Event wait timeout must not be negative" }
            var remainingNanos = timeout.toNanos()

            while (!closed) {
                validateCursor(cursor)

                val available = events.filter { it.cursor.sequence > cursor.sequence }
                if (available.isNotEmpty()) return@withLock available
                if (remainingNanos <= 0) return@withLock emptyList()

                remainingNanos = changed.awaitNanos(remainingNanos)
            }

            null
        }
    }

    fun reset() {
        lock.withLock {
            if (closed) return
            instanceId = UUID.randomUUID().toString()
            sequence = 0
            events.clear()
            changed.signalAll()
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            events.clear()
            changed.signalAll()
        }
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

sealed class BoxloomEvent(
    val cursor: EventCursor,
    val timestamp: Instant,
    val username: String,
    val uuid: String,
) {
    abstract val type: String

    val id: String
        get() = cursor.toString()
}

class ChatMessageEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    val message: String,
    username: String,
    uuid: String,
) : BoxloomEvent(cursor, timestamp, username, uuid) {
    override val type: String = "chat.message"
}

class PlayerDeathEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    val message: String,
    username: String,
    uuid: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
) : BoxloomEvent(cursor, timestamp, username, uuid) {
    override val type: String = "player.death"
}

/** Immutable values captured on the Minecraft server thread, before an item is consumed. */
data class PlayerActionContext(
    val username: String,
    val uuid: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val hand: String,
    val item: String?,
)

data class BlockTarget(val block: String, val x: Int, val y: Int, val z: Int, val face: String)

sealed class PlayerActionEvent(
    cursor: EventCursor,
    timestamp: Instant,
    val context: PlayerActionContext,
) : BoxloomEvent(cursor, timestamp, context.username, context.uuid)

class PlayerSwingEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    context: PlayerActionContext,
) : PlayerActionEvent(cursor, timestamp, context) {
    override val type: String = "player.swing"
}

sealed class BlockActionEvent(
    cursor: EventCursor,
    timestamp: Instant,
    context: PlayerActionContext,
    val target: BlockTarget,
) : PlayerActionEvent(cursor, timestamp, context)

class BlockAttackEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    context: PlayerActionContext,
    target: BlockTarget,
) : BlockActionEvent(cursor, timestamp, context, target) {
    override val type: String = "block.attack"
}

class BlockUseEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    context: PlayerActionContext,
    target: BlockTarget,
) : BlockActionEvent(cursor, timestamp, context, target) {
    override val type: String = "block.use"
}

class ItemUseEvent internal constructor(
    cursor: EventCursor,
    timestamp: Instant,
    context: PlayerActionContext,
) : PlayerActionEvent(cursor, timestamp, context) {
    override val type: String = "item.use"
}

class InvalidEventCursorException(message: String) : IllegalArgumentException(message)

class EventCursorExpiredException(message: String) : IllegalStateException(message)
