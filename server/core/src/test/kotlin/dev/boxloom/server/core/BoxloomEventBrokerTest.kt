package dev.boxloom.server.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoxloomEventBrokerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `returns events published after a cursor`() {
        BoxloomEventBroker(clock = clock).use { broker ->
            val cursor = broker.openCursor(null)

            val published = broker.publishChatMessage("hello", "Steve", TEST_UUID)
            val events = broker.awaitAfter(cursor, Duration.ZERO)

            assertEquals(listOf(published), events)
            assertEquals("2026-08-14T00:00:00Z", published.timestamp.toString())
            assertEquals("hello", published.message)
        }
    }

    @Test
    fun `reopens a retained cursor without duplicating acknowledged events`() {
        BoxloomEventBroker(clock = clock).use { broker ->
            broker.publishChatMessage("first", "Steve", TEST_UUID)
            val acknowledged = broker.publishChatMessage("second", "Steve", TEST_UUID)
            val expected = broker.publishChatMessage("third", "Alex", OTHER_UUID)

            val cursor = broker.openCursor(acknowledged.id)
            val events = broker.awaitAfter(cursor, Duration.ZERO)

            assertEquals(listOf(expected), events)
        }
    }

    @Test
    fun `rejects a cursor after its history is evicted`() {
        BoxloomEventBroker(capacity = 2, clock = clock).use { broker ->
            val expired = broker.openCursor(null)
            broker.publishChatMessage("one", "Steve", TEST_UUID)
            broker.publishChatMessage("two", "Steve", TEST_UUID)
            broker.publishChatMessage("three", "Steve", TEST_UUID)

            assertFailsWith<EventCursorExpiredException> {
                broker.awaitAfter(expired, Duration.ZERO)
            }
        }
    }

    @Test
    fun `reset invalidates cursors from the previous server session`() {
        BoxloomEventBroker(clock = clock).use { broker ->
            val previous = broker.openCursor(null)

            broker.reset()

            assertFailsWith<EventCursorExpiredException> {
                broker.openCursor(previous.toString())
            }
            assertTrue(broker.openCursor(null).toString() != previous.toString())
        }
    }

    @Test
    fun `rejects a malformed cursor`() {
        BoxloomEventBroker(clock = clock).use { broker ->
            assertFailsWith<InvalidEventCursorException> {
                broker.openCursor("not-a-cursor")
            }
        }
    }

    companion object {
        private const val TEST_UUID = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        private const val OTHER_UUID = "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"
    }
}
