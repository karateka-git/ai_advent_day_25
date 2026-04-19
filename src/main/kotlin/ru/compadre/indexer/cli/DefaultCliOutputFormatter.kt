package ru.compadre.indexer.cli

import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.workflow.result.AskResult
import ru.compadre.indexer.workflow.result.ChunkEmbeddingPreview
import ru.compadre.indexer.workflow.result.ChunkPreviewResult
import ru.compadre.indexer.workflow.result.CompareReportResult
import ru.compadre.indexer.workflow.result.CommandResult
import ru.compadre.indexer.workflow.result.DocumentLoadResult
import ru.compadre.indexer.workflow.result.HelpResult
import ru.compadre.indexer.workflow.result.IndexPersistResult
import ru.compadre.indexer.workflow.result.SearchResult

/**
 * Форматтер CLI-вывода для стартовых этапов проекта.
 */
class DefaultCliOutputFormatter : CliOutputFormatter {
    override fun format(result: CommandResult): String = when (result) {
        is HelpResult -> helpText(result)
        is IndexPersistResult -> indexPersistText(result)
        is CompareReportResult -> compareReportText(result)
        is AskResult -> askText(result)
        is SearchResult -> searchText(result)
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
        add("  ask --query <text> --mode plain")
        add("  ask --query <text> --mode rag --strategy <fixed|structured> --top <N>")
        add("  search --query <text> --strategy <fixed|structured> --top <N>")
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
        add("Текущий статус: index сохраняет SQLite-индекс, compare строит comparison.md, ask поддерживает plain и rag, search показывает retrieval topK.")
    }.joinToString(separator = System.lineSeparator())

    private fun askText(result: AskResult): String = buildList {
        add("Команда `ask` получила ответ модели.")
        add("")
        add("Параметры запуска:")
        add("  mode = ${result.mode}")
        add("  query = ${result.query}")

        if (result.mode == "rag") {
            add("  strategy = ${result.strategyLabel ?: "<не указана>"}")
            add("  topK = ${result.topK ?: 0}")
            add("  database = ${result.databasePath ?: "<не указана>"}")
        }

        add("")
        add("Ответ:")
        add(result.answer)

        if (result.mode == "rag") {
            add("")
            addAll(retrievalSummaryLines(result.retrievalResult, result.matches))
        }
    }.joinToString(separator = System.lineSeparator())

    private fun searchText(result: SearchResult): String = buildList {
        add("Команда `search` выполнила semantic search.")
        add("")
        add("Параметры запуска:")
        add("  query = ${result.query}")
        add("  strategy = ${result.strategyLabel}")
        add("  topK = ${result.topK}")
        add("  database = ${result.databasePath}")
        add("")

        addAll(retrievalSummaryLines(result.retrievalResult, result.matches))
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
        add("  Количество документов = ${result.documentsCount}")
        add("  Подготовлено чанков = ${result.chunksPrepared}")
        add("  Сохранено чанков = ${result.chunksStored}")
        add("  Сохранено embeddings = ${result.embeddingsStored}")
        add("  Стратегии в индексе = ${result.strategiesStored.joinToString()}")

        if (result.skippedChunkIds.isEmpty()) {
            add("  Пропущено чанков = 0")
        } else {
            add("  Пропущено чанков = ${result.skippedChunkIds.size}")
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
        add("  Количество документов = ${result.report.documentsCount}")
        add("  Fixed: количество чанков = ${result.report.fixedMetrics.chunksCount}")
        add("  Fixed: средняя длина = ${result.report.fixedMetrics.averageLength.toInt()}")
        add("  Structured: количество чанков = ${result.report.structuredMetrics.chunksCount}")
        add("  Structured: средняя длина = ${result.report.structuredMetrics.averageLength.toInt()}")
        add("")
        add("Распределение длин для fixed:")
        if (result.report.fixedMetrics.lengthBuckets.isEmpty()) {
            add("  - пусто")
        } else {
            result.report.fixedMetrics.lengthBuckets.forEach { bucket ->
                add("  - ${bucket.rangeLabel}: ${bucket.count}")
            }
        }
        add("Распределение длин для structured:")
        if (result.report.structuredMetrics.lengthBuckets.isEmpty()) {
            add("  - пусто")
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

    private fun retrievalSummaryLines(
        retrievalResult: RetrievalPipelineResult?,
        matches: List<ru.compadre.indexer.search.model.SearchMatch>,
    ): List<String> = buildList {
        add("Retrieval-сводка:")

        if (retrievalResult == null) {
            if (matches.isEmpty()) {
                add("  Контекст не найден.")
            } else {
                add("  Режим pipeline недоступен, показаны только финальные совпадения.")
                matches.forEachIndexed { index, match ->
                    addAll(selectedCandidateLines(index + 1, match.embeddedChunk.chunk.metadata.chunkId, match.score, null, null, match.embeddedChunk.chunk.metadata.title, match.embeddedChunk.chunk.metadata.filePath, match.embeddedChunk.chunk.metadata.section, match.embeddedChunk.chunk.text))
                }
            }
            return@buildList
        }

        add("  mode = ${retrievalResult.mode.configValue}")
        add("  initialTopK = ${retrievalResult.initialTopK}")
        add("  finalTopK = ${retrievalResult.finalTopK}")
        add("  candidatesBefore = ${retrievalResult.initialCandidates.size}")
        add("  candidatesSelected = ${retrievalResult.selectedCandidates.size}")

        if (retrievalResult.candidates.isEmpty()) {
            add("  Контекст не найден.")
            return@buildList
        }

        add("  Кандидаты pipeline:")
        retrievalResult.candidates.forEachIndexed { index, candidate ->
            addAll(candidateLines(index + 1, candidate))
        }
    }

    private fun candidateLines(index: Int, candidate: RetrievalCandidate): List<String> {
        val chunk = candidate.match.embeddedChunk.chunk
        return buildList {
            add("  ${index}. selected = ${if (candidate.selected) "yes" else "no"}")
            add("     cosineScore = ${"%.4f".format(candidate.cosineScore)}")
            add("     finalScore = ${"%.4f".format(candidate.finalScore)}")
            candidate.heuristicScore?.let { add("     heuristicScore = ${"%.4f".format(it)}") }
            candidate.modelScore?.let { add("     modelScore = ${"%.4f".format(it)}") }
            candidate.filterReason?.let { add("     filterReason = $it") }
            add("     chunkId = ${chunk.metadata.chunkId}")
            add("     title = ${chunk.metadata.title}")
            add("     filePath = ${chunk.metadata.filePath}")
            add("     section = ${chunk.metadata.section}")
            add("     preview = ${previewText(chunk.text)}")
        }
    }

    private fun selectedCandidateLines(
        index: Int,
        chunkId: String,
        score: Double,
        heuristicScore: Double?,
        modelScore: Double?,
        title: String,
        filePath: String,
        section: String,
        text: String,
    ): List<String> = buildList {
        add("  ${index}. selected = yes")
        add("     cosineScore = ${"%.4f".format(score)}")
        add("     finalScore = ${"%.4f".format(score)}")
        heuristicScore?.let { add("     heuristicScore = ${"%.4f".format(it)}") }
        modelScore?.let { add("     modelScore = ${"%.4f".format(it)}") }
        add("     chunkId = $chunkId")
        add("     title = $title")
        add("     filePath = $filePath")
        add("     section = $section")
        add("     preview = ${previewText(text)}")
    }

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
