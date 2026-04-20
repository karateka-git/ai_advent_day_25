package ru.compadre.indexer.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant

class ChatSessionTest {
    @Test
    fun `new session starts with empty history and empty task state`() {
        val createdAt = Instant.parse("2026-04-20T10:15:30Z")

        val session = ChatSession.newSession(
            sessionId = "session-1",
            createdAt = createdAt,
        )

        assertEquals("session-1", session.sessionId)
        assertEquals(emptyList(), session.messages)
        assertEquals(TaskState(), session.taskState)
        assertEquals(null, session.lastGroundedAnswer)
        assertEquals(createdAt, session.createdAt)
        assertEquals(createdAt, session.updatedAt)
    }
}
