package ru.compadre.indexer.search

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.PostRetrievalRequest
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.postprocess.NoOpPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.PostRetrievalProcessor
import java.nio.file.Path

/**
 * Оркестрирует общий retrieval pipeline: vector search и второй этап post-processing.
 */
class RetrievalPipelineService(
    private val searchEngine: SearchEngine,
    private val noOpPostRetrievalProcessor: PostRetrievalProcessor = NoOpPostRetrievalProcessor(),
) {
    /**
     * Выполняет retrieval и возвращает расширенный результат пайплайна.
     */
    suspend fun retrieve(
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
        val request = PostRetrievalRequest(
            query = query,
            strategy = strategy,
            initialTopK = initialTopK,
            finalTopK = finalTopK,
            mode = mode,
        )

        return when (mode) {
            PostRetrievalMode.NONE -> noOpPostRetrievalProcessor.process(
                request = request,
                matches = matches,
                config = config,
            )

            else -> throw IllegalArgumentException(
                "Режим `${mode.configValue}` будет реализован на следующих этапах.",
            )
        }
    }
}

