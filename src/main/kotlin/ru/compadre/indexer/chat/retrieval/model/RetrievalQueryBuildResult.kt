package ru.compadre.indexer.chat.retrieval.model

/**
 * Итоговое действие для retrieval на текущем пользовательском ходе.
 */
enum class RetrievalAction {
    PERFORMED,
    SKIPPED,
}

/**
 * Причина, по которой retrieval может быть пропущен.
 */
enum class RetrievalSkipReason {
    SHORT_SERVICE_TURN,
}

/**
 * Результат построения retrieval query для текущего пользовательского хода.
 */
data class RetrievalQueryBuildResult(
    val action: RetrievalAction,
    val query: String? = null,
    val skipReason: RetrievalSkipReason? = null,
)
