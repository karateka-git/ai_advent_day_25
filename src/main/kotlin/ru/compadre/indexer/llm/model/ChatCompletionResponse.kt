package ru.compadre.indexer.llm.model

import kotlinx.serialization.Serializable

/**
 * Ответ внешнего chat completion API.
 */
@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
)

/**
 * Вариант ответа модели.
 */
@Serializable
data class Choice(
    val message: ChatMessage,
)
