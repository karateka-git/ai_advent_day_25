package ru.compadre.indexer.chat.orchestration.model

import ru.compadre.indexer.chat.memory.model.ChatTurnType
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatSession
import ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult
import ru.compadre.indexer.qa.model.RagAnswer

/**
 * Итог обработки одного пользовательского хода внутри chat-сессии.
 */
data class ChatTurnResult(
    val session: ChatSession,
    val turnType: ChatTurnType,
    val userMessageRecord: ChatMessageRecord,
    val assistantMessageRecord: ChatMessageRecord? = null,
    val retrievalQuery: RetrievalQueryBuildResult,
    val ragAnswer: RagAnswer? = null,
)
