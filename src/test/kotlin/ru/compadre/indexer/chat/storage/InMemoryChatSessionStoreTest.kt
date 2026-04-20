package ru.compadre.indexer.chat.storage

import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.model.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.time.Instant

class InMemoryChatSessionStoreTest {
    @Test
    fun `create stores new empty session`() {
        val createdAt = Instant.parse("2026-04-20T11:00:00Z")
        val store = InMemoryChatSessionStore(nowProvider = { createdAt })

        val session = store.create("session-1")

        assertEquals("session-1", session.sessionId)
        assertEquals(createdAt, session.createdAt)
        assertEquals(createdAt, session.updatedAt)
        assertEquals(session, store.findById("session-1"))
    }

    @Test
    fun `find returns null for unknown session`() {
        val store = InMemoryChatSessionStore()

        assertNull(store.findById("missing-session"))
    }

    @Test
    fun `save updates existing session snapshot`() {
        val createdAt = Instant.parse("2026-04-20T11:00:00Z")
        val updatedAt = Instant.parse("2026-04-20T11:05:00Z")
        val store = InMemoryChatSessionStore(nowProvider = { createdAt })
        val session = store.create("session-1")

        val updatedSession = session.copy(
            messages = listOf(
                ChatMessageRecord(
                    turnId = 1,
                    role = ChatRole.USER,
                    text = "Привет",
                    timestamp = updatedAt,
                ),
            ),
            taskState = TaskState(goal = "Собрать mini-chat"),
            updatedAt = updatedAt,
        )

        store.save(updatedSession)

        val storedSession = store.findById("session-1")
        assertNotNull(storedSession)
        assertEquals(updatedSession, storedSession)
    }

    @Test
    fun `create fails for duplicate session id`() {
        val store = InMemoryChatSessionStore()
        store.create("session-1")

        assertFailsWith<IllegalArgumentException> {
            store.create("session-1")
        }
    }
}
