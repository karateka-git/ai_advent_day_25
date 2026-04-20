package ru.compadre.indexer.chat.model

import java.time.Instant

/**
 * Роль сообщения внутри chat-сессии.
 */
enum class ChatRole {
    USER,
    ASSISTANT,
}

/**
 * Отдельное сообщение из истории chat-сессии.
 */
data class ChatMessageRecord(
    val turnId: Int,
    val role: ChatRole,
    val text: String,
    val timestamp: Instant,
)

/**
 * Текущее состояние chat-сессии с историей сообщений и памятью задачи.
 */
data class ChatSession(
    val sessionId: String,
    val messages: List<ChatMessageRecord>,
    val taskState: TaskState,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * Создаёт новую пустую chat-сессию.
         *
         * @param sessionId идентификатор новой сессии.
         * @param createdAt момент создания сессии.
         * @return пустая chat-сессия с начальным `TaskState`.
         */
        fun newSession(
            sessionId: String,
            createdAt: Instant = Instant.now(),
        ): ChatSession =
            ChatSession(
                sessionId = sessionId,
                messages = emptyList(),
                taskState = TaskState(),
                createdAt = createdAt,
                updatedAt = createdAt,
            )
    }
}
