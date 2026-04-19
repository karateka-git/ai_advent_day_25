package ru.compadre.indexer.search.model

/**
 * Расширенное представление кандидата после retrieval и post-processing.
 */
data class RetrievalCandidate(
    val match: SearchMatch,
    val initialRank: Int,
    val finalRank: Int? = null,
    val cosineScore: Double,
    val finalScore: Double,
    val heuristicScore: Double? = null,
    val modelScore: Double? = null,
    val decisionReason: RetrievalDecisionReason? = null,
    val selected: Boolean = false,
)
