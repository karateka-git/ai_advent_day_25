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
    /** Главная цель диалога в нормализованном виде. */
    val goal: String? = null,
    /** Зафиксированные ограничения и правила диалога. */
    val constraints: List<String> = emptyList(),
    /** Зафиксированные термины, которые нужно учитывать в ответах. */
    val fixedTerms: List<FixedTerm> = emptyList(),
    /** Подтверждённые факты, которые уже были установлены в диалоге. */
    val knownFacts: List<String> = emptyList(),
    /** Незакрытые вопросы и пункты, которые пока нужно уточнить. */
    val openQuestions: List<String> = emptyList(),
    /**
     * Нормализованное намерение последнего user-turn.
     *
     * Поле должно оставаться коротким, но при этом сохранять ключевые сущности,
     * тему и предмет запроса. Для retrieval-зависимых follow-up ходов оно
     * используется как semantic core для embedding query.
     */
    val lastUserIntent: String? = null,
)
