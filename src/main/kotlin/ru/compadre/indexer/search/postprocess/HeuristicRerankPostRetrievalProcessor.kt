package ru.compadre.indexer.search.postprocess

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.search.model.HeuristicRerankReason
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch

/**
 * Rule-based reranker, который перестраивает порядок кандидатов по heuristic score.
 */
class HeuristicRerankPostRetrievalProcessor : PostRetrievalProcessor {
    override suspend fun process(
        request: PostRetrievalRequest,
        matches: List<SearchMatch>,
        config: AppConfig,
    ): RetrievalPipelineResult {
        val scoredCandidates = matches.mapIndexed { index, match ->
            val chunk = match.embeddedChunk.chunk
            val signals = HeuristicTextSignalExtractor.extract(
                query = request.query,
                text = chunk.text,
                title = chunk.metadata.title,
                section = chunk.metadata.section,
            )
            val heuristicScore = HeuristicScoreCalculator.calculate(
                cosineScore = match.score,
                signals = signals,
                config = config,
            )

            ScoredHeuristicCandidate(
                match = match,
                initialRank = index + 1,
                heuristicScore = heuristicScore,
                signals = signals,
            )
        }
        val rerankedCandidates = scoredCandidates.sortedWith(
            compareByDescending<ScoredHeuristicCandidate> { candidate -> candidate.heuristicScore }
                .thenByDescending { candidate -> candidate.match.score }
                .thenBy { candidate -> candidate.initialRank },
        )
        val finalRanks = rerankedCandidates.withIndex().associate { (index, candidate) ->
            candidate.match to (index + 1)
        }
        val selectedMatches = rerankedCandidates
            .take(request.finalTopK)
            .map { candidate -> candidate.match }
            .toSet()

        val candidates = scoredCandidates.map { candidate ->
            val finalRank = finalRanks.getValue(candidate.match)
            val selected = candidate.match in selectedMatches
            RetrievalCandidate(
                match = candidate.match,
                initialRank = candidate.initialRank,
                finalRank = finalRank,
                cosineScore = candidate.match.score,
                finalScore = candidate.heuristicScore,
                heuristicScore = candidate.heuristicScore,
                decisionReason = buildDecisionReason(
                    candidate = candidate,
                    selected = selected,
                    finalRank = finalRank,
                    request = request,
                ),
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

    private fun buildDecisionReason(
        candidate: ScoredHeuristicCandidate,
        selected: Boolean,
        finalRank: Int,
        request: PostRetrievalRequest,
    ) = when {
        !selected && finalRank > request.finalTopK -> HeuristicRerankReason.TRIMMED_BY_FINAL_TOP_K
        finalRank < candidate.initialRank && candidate.signals.hasTextMatch -> HeuristicRerankReason.BOOSTED_BY_EXACT_MATCH
        finalRank < candidate.initialRank && candidate.signals.hasTitleMatch -> HeuristicRerankReason.BOOSTED_BY_TITLE_MATCH
        finalRank > candidate.initialRank && candidate.signals.keywordOverlapCount == 0 -> HeuristicRerankReason.DEMOTED_BY_WEAK_OVERLAP
        finalRank != candidate.initialRank -> HeuristicRerankReason.RANKED_BY_HEURISTIC_SCORE
        else -> null
    }

    private data class ScoredHeuristicCandidate(
        val match: SearchMatch,
        val initialRank: Int,
        val heuristicScore: Double,
        val signals: HeuristicTextSignals,
    )
}

