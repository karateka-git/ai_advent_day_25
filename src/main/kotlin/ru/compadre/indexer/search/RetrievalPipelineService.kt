package ru.compadre.indexer.search

import kotlinx.serialization.json.buildJsonArray
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.postprocess.HeuristicFilterPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.HeuristicRerankPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.ModelRerankPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.NoOpPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.PostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.ThresholdPostRetrievalProcessor
import ru.compadre.indexer.trace.NoOpTraceSink
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.emitRecord
import ru.compadre.indexer.trace.putInt
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.retrievalCandidateTracePayload
import ru.compadre.indexer.trace.searchMatchTracePayload
import ru.compadre.indexer.trace.tracePayload
import java.nio.file.Path

/**
 * Orchestrates the shared retrieval pipeline: vector search plus post-processing.
 */
class RetrievalPipelineService(
    private val searchEngine: SearchEngine,
    private val traceSink: TraceSink = NoOpTraceSink,
    private val noOpPostRetrievalProcessor: PostRetrievalProcessor = NoOpPostRetrievalProcessor(),
    private val thresholdPostRetrievalProcessor: PostRetrievalProcessor = ThresholdPostRetrievalProcessor(),
    private val heuristicFilterPostRetrievalProcessor: PostRetrievalProcessor = HeuristicFilterPostRetrievalProcessor(),
    private val heuristicRerankPostRetrievalProcessor: PostRetrievalProcessor = HeuristicRerankPostRetrievalProcessor(),
    private val modelRerankPostRetrievalProcessor: PostRetrievalProcessor = ModelRerankPostRetrievalProcessor(traceSink = traceSink),
) {
    suspend fun retrieve(
        requestId: String,
        query: String,
        databasePath: Path,
        strategy: ChunkingStrategy?,
        initialTopK: Int,
        finalTopK: Int,
        config: AppConfig,
    ): RetrievalPipelineResult {
        require(initialTopK > 0) { "Параметр initialTopK должен быть больше 0." }
        require(finalTopK > 0) { "Параметр finalTopK должен быть больше 0." }

        val mode = PostRetrievalMode.fromValue(config.search.postProcessingMode)
            ?: throw IllegalArgumentException(
                "Неподдерживаемый режим post-processing: `${config.search.postProcessingMode}`.",
            )

        val matches = searchEngine.search(
            query = query,
            databasePath = databasePath,
            strategy = strategy,
            topK = initialTopK,
            config = config,
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "embedding_candidates_built",
            stage = "retrieval.embedding_search",
            payload = tracePayload {
                putString("query", query)
                putString("databasePath", databasePath.toAbsolutePath().toString())
                putString("strategy", strategy?.id)
                putInt("initialTopK", initialTopK)
                put(
                    "candidates",
                    buildJsonArray {
                        matches.forEachIndexed { index, match ->
                            add(searchMatchTracePayload(match, initialRank = index + 1))
                        }
                    },
                )
            },
        )

        val request = PostRetrievalRequest(
            requestId = requestId,
            query = query,
            strategy = strategy,
            initialTopK = initialTopK,
            finalTopK = finalTopK,
            mode = mode,
        )

        val retrievalResult = when (mode) {
            PostRetrievalMode.NONE -> noOpPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )

            PostRetrievalMode.THRESHOLD_FILTER -> thresholdPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )

            PostRetrievalMode.HEURISTIC_FILTER -> heuristicFilterPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )

            PostRetrievalMode.HEURISTIC_RERANK -> heuristicRerankPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )

            PostRetrievalMode.MODEL_RERANK -> modelRerankPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )
        }

        traceSink.emitRecord(
            requestId = requestId,
            kind = "selected_matches_built",
            stage = "retrieval.postprocess",
            payload = tracePayload {
                putString("query", query)
                putString("postProcessingMode", mode.configValue)
                putInt("selectedMatchesCount", retrievalResult.selectedMatches.size)
                put(
                    "selectedMatches",
                    buildJsonArray {
                        retrievalResult.selectedCandidates.forEach { candidate ->
                            add(retrievalCandidateTracePayload(candidate, postProcessingMode = mode.configValue))
                        }
                    },
                )
            },
        )

        return retrievalResult
    }
}
