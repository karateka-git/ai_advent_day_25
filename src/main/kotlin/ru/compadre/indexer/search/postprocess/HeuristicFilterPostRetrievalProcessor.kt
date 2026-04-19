package ru.compadre.indexer.search.postprocess

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.search.model.HeuristicFilterReason
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch

/**
 * Rule-based фильтр релевантности поверх кандидатов vector search.
 */
class HeuristicFilterPostRetrievalProcessor : PostRetrievalProcessor {
    override suspend fun process(
        request: PostRetrievalRequest,
        matches: List<SearchMatch>,
        config: AppConfig,
    ): RetrievalPipelineResult {
        val heuristicConfig = config.search.heuristic
        val duplicateSimilarityThreshold = 1.0 - heuristicConfig.duplicatePenalty
        val selectedTextTerms = mutableListOf<Set<String>>()
        val selectedMatches = mutableSetOf<SearchMatch>()

        val candidates = matches.mapIndexed { index, match ->
            val chunk = match.embeddedChunk.chunk
            val signals = HeuristicTextSignalExtractor.extract(
                query = request.query,
                text = chunk.text,
                title = chunk.metadata.title,
                section = chunk.metadata.section,
            )
            val heuristicScore = buildHeuristicScore(
                cosineScore = match.score,
                signals = signals,
                config = config,
            )
            val duplicateOfStrongerCandidate = selectedTextTerms.any { selectedTerms ->
                HeuristicTextSignalExtractor.jaccardSimilarity(selectedTerms, signals.textTerms) >= duplicateSimilarityThreshold
            }
            val decisionReason = when {
                !signals.hasMeaningfulMatch -> HeuristicFilterReason.NO_MEANINGFUL_MATCH
                signals.keywordOverlapCount < heuristicConfig.minKeywordOverlap -> HeuristicFilterReason.LOW_KEYWORD_OVERLAP
                duplicateOfStrongerCandidate -> HeuristicFilterReason.DUPLICATE_OF_STRONGER_CANDIDATE
                selectedMatches.size >= request.finalTopK -> HeuristicFilterReason.TRIMMED_BY_FINAL_TOP_K
                else -> null
            }
            val selected = decisionReason == null && selectedMatches.size < request.finalTopK

            if (selected) {
                selectedMatches += match
                selectedTextTerms += signals.textTerms
            }

            RetrievalCandidate(
                match = match,
                initialRank = index + 1,
                finalRank = if (selected) selectedMatches.size else null,
                cosineScore = match.score,
                finalScore = heuristicScore,
                heuristicScore = heuristicScore,
                decisionReason = decisionReason,
                selected = selected,
            )
        }

        return RetrievalPipelineResult(
            mode = request.mode,
            initialTopK = request.initialTopK,
            finalTopK = request.finalTopK,
            candidates = candidates,
        )
    }

    private fun buildHeuristicScore(
        cosineScore: Double,
        signals: HeuristicTextSignals,
        config: AppConfig,
    ): Double {
        val heuristicConfig = config.search.heuristic
        var score =
            cosineScore * heuristicConfig.cosineWeight +
                signals.overlapRatio * heuristicConfig.keywordOverlapWeight

        if (signals.hasTextMatch) {
            score += heuristicConfig.exactMatchBonus
        }
        if (signals.hasTitleMatch) {
            score += heuristicConfig.titleMatchBonus
        }
        if (signals.hasSectionMatch) {
            score += heuristicConfig.sectionMatchBonus
        }

        return score
    }
}
