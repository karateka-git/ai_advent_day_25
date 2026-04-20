package ru.compadre.indexer.chat.memory.model

import kotlinx.serialization.Serializable

/**
 * JSON-контракт, который ожидается от LLM при обновлении памяти задачи.
 */
@Serializable
data class TaskStateUpdateCompletion(
    val goal: String? = null,
    val constraints: List<String> = emptyList(),
    val fixedTerms: List<TaskStateUpdateFixedTerm> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val lastUserIntent: String? = null,
)

/**
 * JSON-представление зафиксированного термина внутри memory update.
 */
@Serializable
data class TaskStateUpdateFixedTerm(
    val term: String? = null,
    val definition: String? = null,
)
