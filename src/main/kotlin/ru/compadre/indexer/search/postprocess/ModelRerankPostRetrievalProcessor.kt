package ru.compadre.indexer.search.postprocess

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.search.model.ModelRerankReason
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.trace.NoOpTraceSink
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.emitRecord
import ru.compadre.indexer.trace.llmRequestTracePayload
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.tracePayload

/**
 * Model-based reranker over vector-search candidates.
 */
class ModelRerankPostRetrievalProcessor(
    private val modelRerankJudge: ModelRerankScorer = ModelRerankJudge(),
    private val traceSink: TraceSink = NoOpTraceSink,
) : PostRetrievalProcessor {
    override suspend fun process(
        request: PostRetrievalRequest,
        matches: List<SearchMatch>,
        config: AppConfig,
    ): RetrievalPipelineResult {
        require(config.search.modelRerank.enabled) {
            "Model-based rerank отключён в конфигурации `search.modelRerank.enabled`."
        }

        val maxCandidates = config.search.modelRerank.maxCandidates
        val rerankableMatches = matches.take(maxCandidates)
        val passthroughMatches = matches.drop(maxCandidates)
        val scoredCandidates = rerankableMatches.mapIndexed { index, match ->
            val chunk = match.embeddedChunk.chunk
            val prompt = modelRerankJudge.buildPrompt(
                query = request.query,
                chunk = chunk,
                config = config.llm,
            )
            traceSink.emitRecord(
                requestId = request.requestId,
                kind = "model_rerank_prompt_built",
                stage = "retrieval.model_rerank_prompt",
                payload = tracePayload {
                    put("llmRequest", llmRequestTracePayload(prompt.config, prompt.messages))
                },
            )
            val evaluation = modelRerankJudge.score(
                prompt = prompt,
                fallbackCosineScore = match.score,
            )
            traceSink.emitRecord(
                requestId = request.requestId,
                kind = "model_rerank_scored",
                stage = "retrieval.model_rerank",
                payload = tracePayload {
                    putString("llmResponse", evaluation.rawResponse)
                },
            )

            ScoredModelCandidate(
                match = match,
                initialRank = index + 1,
                modelScore = evaluation.score,
                usedFallback = evaluation.usedFallback,
            )
        }
        val rerankedCandidates = scoredCandidates.sortedWith(
            compareByDescending<ScoredModelCandidate> { candidate -> candidate.modelScore }
                .thenByDescending { candidate -> candidate.match.score }
                .thenBy { candidate -> candidate.initialRank },
        )
        val finalOrder = rerankedCandidates.map { candidate -> candidate.match } + passthroughMatches
        val finalRanks = finalOrder.withIndex().associate { (index, match) -> match to (index + 1) }
        val selectedMatches = finalOrder.take(request.finalTopK).toSet()

        val candidates = buildList {
            addAll(
                scoredCandidates.map { candidate ->
                    val finalRank = finalRanks.getValue(candidate.match)
                    val selected = candidate.match in selectedMatches
                    RetrievalCandidate(
                        match = candidate.match,
                        initialRank = candidate.initialRank,
                        finalRank = finalRank,
                        cosineScore = candidate.match.score,
                        finalScore = candidate.modelScore,
                        modelScore = candidate.modelScore,
                        decisionReason = buildDecisionReason(
                            candidate = candidate,
                            selected = selected,
                            finalRank = finalRank,
                            request = request,
                        ),
                        selected = selected,
                    )
                },
            )
            addAll(
                passthroughMatches.mapIndexed { index, match ->
                    val initialRank = rerankableMatches.size + index + 1
                    val finalRank = finalRanks.getValue(match)
                    RetrievalCandidate(
                        match = match,
                        initialRank = initialRank,
                        finalRank = finalRank,
                        cosineScore = match.score,
                        finalScore = match.score * MODEL_SCORE_MULTIPLIER,
                        modelScore = null,
                        decisionReason = if (match in selectedMatches) {
                            ModelRerankReason.RANKED_BY_MODEL_SCORE
                        } else {
                            ModelRerankReason.TRIMMED_BY_FINAL_TOP_K
                        },
                        selected = match in selectedMatches,
                    )
                },
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
        candidate: ScoredModelCandidate,
        selected: Boolean,
        finalRank: Int,
        request: PostRetrievalRequest,
    ) = when {
        candidate.usedFallback -> ModelRerankReason.FALLBACK_TO_COSINE_SCORE
        !selected && finalRank > request.finalTopK -> ModelRerankReason.TRIMMED_BY_FINAL_TOP_K
        finalRank != candidate.initialRank -> ModelRerankReason.RANKED_BY_MODEL_SCORE
        else -> null
    }

    private data class ScoredModelCandidate(
        val match: SearchMatch,
        val initialRank: Int,
        val modelScore: Double,
        val usedFallback: Boolean,
    )

    private companion object {
        private const val MODEL_SCORE_MULTIPLIER = 100.0
    }
}
