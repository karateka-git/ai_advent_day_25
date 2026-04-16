package ru.compadre.indexer.workflow.service

import ru.compadre.indexer.chunking.FixedSizeChunker
import ru.compadre.indexer.chunking.StructuredChunker
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.embedding.EmbeddingService
import ru.compadre.indexer.loader.DocumentLoader
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.model.DocumentChunk
import ru.compadre.indexer.model.EmbeddedChunk
import ru.compadre.indexer.model.RawDocument
import ru.compadre.indexer.report.ChunkingComparisonService
import ru.compadre.indexer.report.MarkdownComparisonReportWriter
import ru.compadre.indexer.storage.IndexStore
import ru.compadre.indexer.storage.SqliteIndexStore
import ru.compadre.indexer.workflow.command.CompareCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.IndexCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand
import ru.compadre.indexer.workflow.result.ChunkEmbeddingPreview
import ru.compadre.indexer.workflow.result.ChunkPreviewResult
import ru.compadre.indexer.workflow.result.CompareReportResult
import ru.compadre.indexer.workflow.result.CommandResult
import ru.compadre.indexer.workflow.result.HelpResult
import ru.compadre.indexer.workflow.result.IndexPersistResult
import java.nio.file.Path

/**
 * Стартовая реализация обработчика команд для этапов загрузки корпуса, chunking, embeddings и SQLite storage.
 */
class DefaultWorkflowCommandHandler(
    private val documentLoader: DocumentLoader = DocumentLoader(),
    private val indexStore: IndexStore = SqliteIndexStore(),
    private val comparisonService: ChunkingComparisonService = ChunkingComparisonService(),
    private val comparisonReportWriter: MarkdownComparisonReportWriter = MarkdownComparisonReportWriter(),
) : WorkflowCommandHandler {
    override suspend fun handle(command: WorkflowCommand, config: AppConfig): CommandResult = when (command) {
        HelpCommand -> HelpResult(
            inputDir = config.app.inputDir,
            outputDir = config.app.outputDir,
            ollamaBaseUrl = config.ollama.baseUrl,
            embeddingModel = config.ollama.embeddingModel,
            fixedSize = config.chunking.fixedSize,
            overlap = config.chunking.overlap,
        )

        is IndexCommand -> runIndexing(
            inputDir = command.inputDir ?: config.app.inputDir,
            config = config,
            strategy = command.strategy,
            allStrategies = command.allStrategies,
        )

        is CompareCommand -> buildCompareReport(
            inputDir = command.inputDir ?: config.app.inputDir,
            config = config,
        )
    }

    private suspend fun runIndexing(
        inputDir: String,
        config: AppConfig,
        strategy: ChunkingStrategy?,
        allStrategies: Boolean,
    ): IndexPersistResult {
        val documents = documentLoader.load(Path.of(inputDir))
        val chunks = buildChunks(
            documents = documents,
            config = config,
            strategy = strategy,
            allStrategies = allStrategies,
        )
        val skippedChunkIds = mutableListOf<String>()
        val embeddedChunks = buildEmbeddedChunks(chunks, config, skippedChunkIds)
        val databasePath = resolveDatabasePath(
            outputDir = config.app.outputDir,
            strategy = strategy,
            allStrategies = allStrategies,
        )
        val storedSummary = indexStore.save(
            databasePath = databasePath,
            documents = documents,
            embeddedChunks = embeddedChunks,
        )

        return IndexPersistResult(
            inputDir = inputDir,
            outputDir = config.app.outputDir,
            databasePath = databasePath.toAbsolutePath().toString(),
            strategyLabel = strategy?.id ?: if (allStrategies) "all" else "fixed",
            documentsCount = documents.size,
            chunksPrepared = chunks.size,
            chunksStored = storedSummary.chunksCount,
            embeddingsStored = storedSummary.embeddingsCount,
            skippedChunkIds = skippedChunkIds,
            strategiesStored = storedSummary.strategies,
        )
    }

    private fun buildCompareReport(
        inputDir: String,
        config: AppConfig,
    ): CompareReportResult {
        val documents = documentLoader.load(Path.of(inputDir))
        val fixedChunks = buildChunks(
            documents = documents,
            config = config,
            strategy = ChunkingStrategy.FIXED,
            allStrategies = false,
        )
        val structuredChunks = buildChunks(
            documents = documents,
            config = config,
            strategy = ChunkingStrategy.STRUCTURED,
            allStrategies = false,
        )
        val report = comparisonService.buildReport(
            inputDir = inputDir,
            documentsCount = documents.size,
            fixedChunks = fixedChunks,
            structuredChunks = structuredChunks,
        )
        val reportPath = Path.of(config.app.outputDir).resolve("comparison.md")
        comparisonReportWriter.write(reportPath, report)

        return CompareReportResult(
            inputDir = inputDir,
            outputDir = config.app.outputDir,
            reportPath = reportPath.toAbsolutePath().toString(),
            report = report,
        )
    }

    private fun buildChunks(
        documents: List<RawDocument>,
        config: AppConfig,
        strategy: ChunkingStrategy?,
        allStrategies: Boolean,
    ): List<DocumentChunk> {
        val fixedChunker = FixedSizeChunker(
            chunkSize = config.chunking.fixedSize,
            overlap = config.chunking.overlap,
        )
        val structuredChunker = StructuredChunker(fallbackChunker = fixedChunker)

        return when {
            allStrategies -> documents.flatMap { document ->
                fixedChunker.chunk(document) + structuredChunker.chunk(document)
            }

            strategy == ChunkingStrategy.STRUCTURED -> documents.flatMap(structuredChunker::chunk)
            else -> documents.flatMap(fixedChunker::chunk)
        }
    }

    private suspend fun buildEmbeddedChunks(
        chunks: List<DocumentChunk>,
        config: AppConfig,
        skippedChunkIds: MutableList<String>,
    ): List<EmbeddedChunk> {
        if (chunks.isEmpty()) {
            return emptyList()
        }

        val embeddingService = EmbeddingService(config.ollama)
        return try {
            chunks.mapNotNull { chunk ->
                val embedding = embeddingService.generate(chunk.text)
                if (embedding == null) {
                    skippedChunkIds += chunk.metadata.chunkId
                    null
                } else {
                    EmbeddedChunk(
                        chunk = chunk,
                        embedding = embedding,
                    )
                }
            }
        } finally {
            embeddingService.close()
        }
    }

    private suspend fun buildEmbeddingPreview(
        chunks: List<DocumentChunk>,
        config: AppConfig,
    ): List<ChunkEmbeddingPreview> {
        if (chunks.isEmpty()) {
            return emptyList()
        }

        val embeddingService = EmbeddingService(config.ollama)
        return try {
            chunks.take(EMBEDDING_PREVIEW_LIMIT).mapNotNull { chunk ->
                val embedding = embeddingService.generate(chunk.text) ?: return@mapNotNull null

                ChunkEmbeddingPreview(
                    chunkId = chunk.metadata.chunkId,
                    model = embedding.model,
                    vectorSize = embedding.vector.size,
                    textPreview = chunk.text,
                )
            }
        } finally {
            embeddingService.close()
        }
    }

    private fun resolveDatabasePath(
        outputDir: String,
        strategy: ChunkingStrategy?,
        allStrategies: Boolean,
    ): Path {
        val fileName = when {
            allStrategies -> "index-all.db"
            strategy == ChunkingStrategy.STRUCTURED -> "index-structured.db"
            else -> "index-fixed.db"
        }

        return Path.of(outputDir).resolve(fileName)
    }

    private companion object {
        private const val EMBEDDING_PREVIEW_LIMIT = 3
    }
}
