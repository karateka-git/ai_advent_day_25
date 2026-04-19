package ru.compadre.indexer.qa.model

import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch

/**
 * Результат RAG-ответа вместе с retrieval-сводкой.
 */
data class RagAnswer(
    val answer: String,
    val retrievalResult: RetrievalPipelineResult,
) {
    val matches: List<SearchMatch>
        get() = retrievalResult.selectedMatches
}
