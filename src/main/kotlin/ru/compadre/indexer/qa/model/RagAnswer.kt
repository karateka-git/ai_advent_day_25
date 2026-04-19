package ru.compadre.indexer.qa.model

import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch

/**
 * Structured source reference used by the RAG answer.
 */
data class RagSource(
    val source: String,
    val section: String,
    val chunkId: String,
)

/**
 * Short text fragment returned with the answer for traceability.
 */
data class RagQuote(
    val chunkId: String,
    val quote: String,
)

/**
 * Structured RAG answer with retrieval metadata, sources and quotes.
 */
data class RagAnswer(
    val answer: String,
    val sources: List<RagSource> = emptyList(),
    val quotes: List<RagQuote> = emptyList(),
    val isRefusal: Boolean = false,
    val refusalReason: String? = null,
    val retrievalResult: RetrievalPipelineResult,
) {
    val matches: List<SearchMatch>
        get() = retrievalResult.selectedMatches
}
