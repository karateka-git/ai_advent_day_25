package ru.compadre.indexer.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тело запроса к внешнему chat completion API.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens")
    val maxTokens: Int,
)
