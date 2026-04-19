package ru.compadre.indexer.search.postprocess

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.ThresholdFilterReason
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch

/**
 * Фильтрует retrieval-кандидатов по минимальному similarity score.
 */
class ThresholdPostRetrievalProcessor : PostRetrievalProcessor {
    override suspend fun process(
        request: PostRetrievalRequest,
        matches: List<SearchMatch>,
        config: AppConfig,
    ): RetrievalPipelineResult {
        val minimumSimilarity = config.search.minSimilarity
        val filteredMatches = matches.filter { match -> match.score >= minimumSimilarity }
        val selectedMatches = filteredMatches.take(request.finalTopK)

        val candidates = matches.map { match ->
            val decisionReason = when {
                match.score < minimumSimilarity -> ThresholdFilterReason.BELOW_MIN_SIMILARITY
                match !in selectedMatches -> ThresholdFilterReason.TRIMMED_BY_FINAL_TOP_K
                else -> null
            }

            RetrievalCandidate(
                match = match,
                cosineScore = match.score,
                finalScore = match.score,
                decisionReason = decisionReason,
                selected = match in selectedMatches,
            )
        }

        return RetrievalPipelineResult(
            mode = request.mode,
            initialTopK = request.initialTopK,
            finalTopK = request.finalTopK,
            candidates = candidates,
        )
    }
}
