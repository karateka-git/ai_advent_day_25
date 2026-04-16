package ru.compadre.indexer.workflow.result

import ru.compadre.indexer.model.DocumentChunk
import ru.compadre.indexer.model.RawDocument
import ru.compadre.indexer.report.ChunkingComparisonReport

/**
 * Базовый тип результатов выполнения CLI-команд.
 */
sealed interface CommandResult

/**
 * Результат вывода справки.
 */
data class HelpResult(
    val inputDir: String,
    val outputDir: String,
    val ollamaBaseUrl: String,
    val embeddingModel: String,
    val fixedSize: Int,
    val overlap: Int,
) : CommandResult

/**
 * Результат предпросмотра загрузки документов для индексации.
 */
data class DocumentLoadResult(
    val commandName: String,
    val inputDir: String,
    val outputDir: String,
    val strategyLabel: String,
    val documents: List<RawDocument>,
) : CommandResult

/**
 * Результат полной индексации корпуса в локальный SQLite-индекс.
 */
data class IndexPersistResult(
    val inputDir: String,
    val outputDir: String,
    val databasePath: String,
    val strategyLabel: String,
    val documentsCount: Int,
    val chunksPrepared: Int,
    val chunksStored: Int,
    val embeddingsStored: Int,
    val skippedChunkIds: List<String>,
    val strategiesStored: List<String>,
) : CommandResult

/**
 * Результат сравнения fixed и structured chunking с сохранённым отчётом.
 */
data class CompareReportResult(
    val inputDir: String,
    val outputDir: String,
    val reportPath: String,
    val report: ChunkingComparisonReport,
) : CommandResult

/**
 * Результат предпросмотра chunking на текущем этапе.
 */
data class ChunkPreviewResult(
    val commandName: String,
    val inputDir: String,
    val outputDir: String,
    val strategyLabel: String,
    val documents: List<RawDocument>,
    val chunks: List<DocumentChunk>,
    val embeddings: List<ChunkEmbeddingPreview>,
) : CommandResult

/**
 * Короткий preview embedding для CLI-вывода.
 */
data class ChunkEmbeddingPreview(
    val chunkId: String,
    val model: String,
    val vectorSize: Int,
    val textPreview: String,
)
