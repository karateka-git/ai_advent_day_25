package ru.compadre.indexer.cli

import ru.compadre.indexer.workflow.result.ChunkEmbeddingPreview
import ru.compadre.indexer.workflow.result.ChunkPreviewResult
import ru.compadre.indexer.workflow.result.CompareReportResult
import ru.compadre.indexer.workflow.result.CommandResult
import ru.compadre.indexer.workflow.result.DocumentLoadResult
import ru.compadre.indexer.workflow.result.HelpResult
import ru.compadre.indexer.workflow.result.IndexPersistResult

/**
 * Форматтер CLI-вывода для стартовых этапов проекта.
 */
class DefaultCliOutputFormatter : CliOutputFormatter {
    override fun format(result: CommandResult): String = when (result) {
        is HelpResult -> helpText(result)
        is IndexPersistResult -> indexPersistText(result)
        is CompareReportResult -> compareReportText(result)
        is ChunkPreviewResult -> chunkPreviewText(result)
        is DocumentLoadResult -> documentLoadText(result)
    }

    private fun helpText(result: HelpResult): String = buildList {
        add("Local Document Indexer")
        add("")
        add("Доступные команды:")
        add("  index --input <dir> --strategy <fixed|structured>")
        add("  index --input <dir> --all-strategies")
        add("  compare --input <dir>")
        add("  help")
        add("")
        add("Текущий конфиг:")
        add("  inputDir = ${result.inputDir}")
        add("  outputDir = ${result.outputDir}")
        add("  ollama.baseUrl = ${result.ollamaBaseUrl}")
        add("  ollama.embeddingModel = ${result.embeddingModel}")
        add("  chunking.fixedSize = ${result.fixedSize}")
        add("  chunking.overlap = ${result.overlap}")
        add("")
        add("Текущий статус: index сохраняет SQLite-индекс, compare строит comparison.md и показывает метрики стратегий.")
    }.joinToString(separator = System.lineSeparator())

    private fun indexPersistText(result: IndexPersistResult): String = buildList {
        add("Команда `index` завершила локальную индексацию.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("  database = ${result.databasePath}")
        add("")
        add("Сводка индексации:")
        add("  documents = ${result.documentsCount}")
        add("  chunksPrepared = ${result.chunksPrepared}")
        add("  chunksStored = ${result.chunksStored}")
        add("  embeddingsStored = ${result.embeddingsStored}")
        add("  strategiesStored = ${result.strategiesStored.joinToString()}")

        if (result.skippedChunkIds.isEmpty()) {
            add("  skippedChunks = 0")
        } else {
            add("  skippedChunks = ${result.skippedChunkIds.size}")
            add("Пропущенные чанки:")
            result.skippedChunkIds.take(10).forEach { chunkId ->
                add("  - $chunkId")
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun compareReportText(result: CompareReportResult): String = buildList {
        add("Команда `compare` завершила сравнение стратегий chunking.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${result.inputDir}")
        add("  outputDir = ${result.outputDir}")
        add("  report = ${result.reportPath}")
        add("")
        add("Сводка:")
        add("  documents = ${result.report.documentsCount}")
        add("  fixed.chunks = ${result.report.fixedMetrics.chunksCount}")
        add("  fixed.avgLength = ${result.report.fixedMetrics.averageLength.toInt()}")
        add("  structured.chunks = ${result.report.structuredMetrics.chunksCount}")
        add("  structured.avgLength = ${result.report.structuredMetrics.averageLength.toInt()}")
        add("")
        add("Распределение fixed:")
        if (result.report.fixedMetrics.lengthBuckets.isEmpty()) {
            add("  - empty")
        } else {
            result.report.fixedMetrics.lengthBuckets.forEach { bucket ->
                add("  - ${bucket.rangeLabel}: ${bucket.count}")
            }
        }
        add("Распределение structured:")
        if (result.report.structuredMetrics.lengthBuckets.isEmpty()) {
            add("  - empty")
        } else {
            result.report.structuredMetrics.lengthBuckets.forEach { bucket ->
                add("  - ${bucket.rangeLabel}: ${bucket.count}")
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun chunkPreviewText(result: ChunkPreviewResult): String = buildList {
        add("Команда `${result.commandName}` выполнила preview chunking.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("")
        add("Найдено документов: ${result.documents.size}")
        add("Сформировано чанков: ${result.chunks.size}")

        val byStrategy = result.chunks.groupBy { it.strategy }
        if (byStrategy.isNotEmpty()) {
            add("Чанков по стратегиям:")
            byStrategy.forEach { (strategy, chunks) ->
                add("  - ${strategy.id}: ${chunks.size}")
            }
        }

        if (result.chunks.isEmpty()) {
            add("Чанки не сформированы.")
        } else {
            add("Первые чанки:")
            result.chunks.take(12).forEach { chunk ->
                add("  - ${chunk.metadata.chunkId}")
                add("    strategy = ${chunk.strategy.id}")
                add("    section = ${chunk.metadata.section}")
                add("    offsets = ${chunk.metadata.startOffset}..${chunk.metadata.endOffset}")
                add("    textLength = ${chunk.text.length}")
                add("    preview = ${previewText(chunk.text)}")
            }
        }

        if (result.embeddings.isEmpty()) {
            add("Preview embeddings не получены.")
        } else {
            add("Preview embeddings:")
            result.embeddings.forEach { embedding ->
                addEmbeddingPreview(this, embedding)
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun addEmbeddingPreview(lines: MutableList<String>, embedding: ChunkEmbeddingPreview) {
        lines.add("  - ${embedding.chunkId}")
        lines.add("    model = ${embedding.model}")
        lines.add("    vectorSize = ${embedding.vectorSize}")
        lines.add("    preview = ${previewText(embedding.textPreview)}")
    }

    private fun documentLoadText(result: DocumentLoadResult): String = buildList {
        add("Команда `${result.commandName}` выполнила загрузку документов.")
        add("")
        add("Параметры запуска:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("")
        add("Найдено документов: ${result.documents.size}")
    }.joinToString(separator = System.lineSeparator())

    private fun previewText(text: String): String {
        if (text.isBlank()) {
            return "<пусто>"
        }

        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 80) {
            singleLine
        } else {
            singleLine.take(77) + "..."
        }
    }
}
