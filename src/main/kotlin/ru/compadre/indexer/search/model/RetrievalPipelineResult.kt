package ru.compadre.indexer.search.model

/**
 * Полный результат retrieval pipeline: кандидаты до и после post-processing.
 */
data class RetrievalPipelineResult(
    val mode: PostRetrievalMode,
    val initialTopK: Int,
    val finalTopK: Int,
    val candidates: List<RetrievalCandidate>,
) {
    /**
     * Кандидаты в исходном порядке после vector search.
     */
    val initialCandidates: List<RetrievalCandidate>
        get() = candidates

    /**
     * Кандидаты, выбранные для финального контекста.
     */
    val selectedCandidates: List<RetrievalCandidate>
        get() = candidates.filter { candidate -> candidate.selected }

    /**
     * Финальные матчи в формате, ожидаемом текущим RAG prompt.
     */
    val selectedMatches: List<SearchMatch>
        get() = selectedCandidates.map { candidate -> candidate.match }
}

