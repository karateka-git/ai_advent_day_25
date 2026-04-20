package ru.compadre.indexer.chat.orchestration.model

import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.qa.model.RagAnswer

/**
 * Входные данные для переписывания последнего grounded-ответа без нового retrieval.
 */
data class AnswerRewriteRequest(
    val requestId: String,
    val userMessage: String,
    val recentHistory: List<ChatMessageRecord>,
    val taskState: TaskState,
    val lastGroundedAnswer: RagAnswer,
    val config: LlmSection,
)
