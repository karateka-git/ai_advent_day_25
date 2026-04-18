package ru.compadre.indexer.llm.model

import kotlinx.serialization.Serializable

/**
 * Сообщение в истории диалога внешнего LLM API.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)
