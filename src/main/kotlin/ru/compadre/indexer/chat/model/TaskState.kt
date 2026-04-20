package ru.compadre.indexer.chat.model

/**
 * Зафиксированный термин и его рабочее определение внутри chat-сессии.
 */
data class FixedTerm(
    val term: String,
    val definition: String,
)

/**
 * Компактная рабочая память задачи для текущей chat-сессии.
 */
data class TaskState(
    val goal: String? = null,
    val constraints: List<String> = emptyList(),
    val fixedTerms: List<FixedTerm> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val lastUserIntent: String? = null,
)
