package ru.compadre.indexer.chat.orchestration.model

import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.ChunkingStrategy
import java.nio.file.Path

/**
 * Входные данные для ответа с опорой на найденный контекст в рамках одного chat-хода.
 */
data class GroundedChatAnswerRequest(
    val requestId: String,
    val sessionId: String,
    val userMessage: String,
    val retrievalQuery: String,
    val recentHistory: List<ChatMessageRecord>,
    val taskState: TaskState,
    val databasePath: Path,
    val strategy: ChunkingStrategy,
    val initialTopK: Int,
    val finalTopK: Int,
    val config: AppConfig,
)
