package ru.compadre.indexer.search

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
import ru.compadre.indexer.trace.putDouble
import ru.compadre.indexer.trace.putInt
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.tracePayload
import java.nio.file.Path
import java.util.UUID

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
        requestId: String? = null,
        query: String,
        databasePath: Path,
        strategy: ChunkingStrategy?,
        initialTopK: Int,
        finalTopK: Int,
        config: AppConfig,
    ): RetrievalPipelineResult {
        val effectiveRequestId = requestId ?: "retrieval-${UUID.randomUUID()}"
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
            requestId = effectiveRequestId,
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
                            val chunk = match.embeddedChunk.chunk
                            add(
                                buildJsonObject {
                                    putString("chunkId", chunk.metadata.chunkId)
                                    putString("title", chunk.metadata.title)
                                    putString("section", chunk.metadata.section)
                                    putString("filePath", chunk.metadata.filePath)
                                    putDouble("cosineScore", match.score)
                                    putInt("initialRank", index + 1)
                                },
                            )
                        }
                    },
                )
            },
        )

        val request = PostRetrievalRequest(
            requestId = effectiveRequestId,
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
            requestId = effectiveRequestId,
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
                            val chunk = candidate.match.embeddedChunk.chunk
                            add(
                                buildJsonObject {
                                    putString("chunkId", chunk.metadata.chunkId)
                                    putDouble("cosineScore", candidate.cosineScore)
                                    putInt("finalRank", candidate.finalRank)
                                    putString("postProcessingMode", mode.configValue)
                                },
                            )
                        }
                    },
                )
            },
        )

        return retrievalResult
    }
}
