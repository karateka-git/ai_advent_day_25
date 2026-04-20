package ru.compadre.indexer.chat.storage

import ru.compadre.indexer.chat.model.ChatSession
import java.time.Instant

/**
 * In-memory реализация хранения chat-сессий для первой версии mini-chat.
 */
class InMemoryChatSessionStore(
    private val nowProvider: () -> Instant = Instant::now,
) : ChatSessionStore {
    private val sessions = linkedMapOf<String, ChatSession>()

    override fun create(sessionId: String): ChatSession {
        require(sessionId.isNotBlank()) { "Идентификатор chat-сессии не должен быть пустым." }
        require(sessionId !in sessions) { "Chat-сессия `$sessionId` уже существует." }

        return ChatSession.newSession(
            sessionId = sessionId,
            createdAt = nowProvider(),
        ).also { session ->
            sessions[sessionId] = session
        }
    }

    override fun findById(sessionId: String): ChatSession? = sessions[sessionId]

    override fun save(session: ChatSession): ChatSession =
        session.also { storedSession ->
            sessions[storedSession.sessionId] = storedSession
        }
}
