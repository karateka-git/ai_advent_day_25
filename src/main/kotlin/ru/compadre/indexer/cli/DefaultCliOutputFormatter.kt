package ru.compadre.indexer.cli

import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.workflow.result.AskResult
import ru.compadre.indexer.workflow.result.ChatHistoryViewResult
import ru.compadre.indexer.workflow.result.ChatMemoryViewResult
import ru.compadre.indexer.workflow.result.ChatSessionStartedResult
import ru.compadre.indexer.workflow.result.ChatTurnCliResult
import ru.compadre.indexer.workflow.result.ChunkEmbeddingPreview
import ru.compadre.indexer.workflow.result.ChunkPreviewResult
import ru.compadre.indexer.workflow.result.CommandResult
import ru.compadre.indexer.workflow.result.CompareReportResult
import ru.compadre.indexer.workflow.result.DocumentLoadResult
import ru.compadre.indexer.workflow.result.HelpResult
import ru.compadre.indexer.workflow.result.IndexPersistResult
import ru.compadre.indexer.workflow.result.PostModeUpdateResult
import ru.compadre.indexer.workflow.result.SearchResult

/**
 * Форматтер CLI-вывода для проекта.
 */
class DefaultCliOutputFormatter : CliOutputFormatter {
    override fun format(result: CommandResult): String = when (result) {
        is HelpResult -> helpText(result)
        is IndexPersistResult -> indexPersistText(result)
        is CompareReportResult -> compareReportText(result)
        is AskResult -> askText(result)
        is SearchResult -> searchText(result)
        is PostModeUpdateResult -> postModeUpdateText(result)
        is ChunkPreviewResult -> chunkPreviewText(result)
        is DocumentLoadResult -> documentLoadText(result)
        is ChatSessionStartedResult -> chatSessionStartedText(result)
        is ChatTurnCliResult -> chatTurnText(result)
        is ChatMemoryViewResult -> chatMemoryText(result)
        is ChatHistoryViewResult -> chatHistoryText(result)
    }

    private fun helpText(result: HelpResult): String = buildList {
        add("Local Document Indexer")
        add("")
        add("Доступные команды:")
        add("  index --input <dir> --strategy <fixed|structured>")
        add("  index --input <dir> --all-strategies")
        add("  compare --input <dir>")
        add("  chat --strategy <fixed|structured> --top <N>")
        add("  ask --query <text> --mode plain")
        add("  ask --query <text> --mode rag --strategy <fixed|structured> --top <N> --post-mode <mode> --show-all-candidates")
        add("  search --query <text> --strategy <fixed|structured> --top <N> --post-mode <mode> --show-all-candidates")
        add("  set --post-mode <none|threshold-filter|heuristic-filter|heuristic-rerank|model-rerank|config>")
        add("  help")
        add("")
        add("Текущий конфиг:")
        add("  inputDir = ${result.inputDir}")
        add("  outputDir = ${result.outputDir}")
        add("  ollama.baseUrl = ${result.ollamaBaseUrl}")
        add("  ollama.embeddingModel = ${result.embeddingModel}")
        add("  chunking.fixedSize = ${result.fixedSize}")
        add("  chunking.overlap = ${result.overlap}")
        add("  search.postProcessingMode = ${result.postProcessingMode}")
        add("")
        add("Текущий статус: index сохраняет SQLite-индекс, compare строит comparison.md, ask поддерживает plain и rag, search показывает retrieval topK, chat запускает mini-chat с памятью задачи.")
    }.joinToString(separator = System.lineSeparator())

    private fun chatSessionStartedText(result: ChatSessionStartedResult): String = buildList {
        add("Mini-chat с RAG и памятью задачи.")
        add("История хранится только в текущей сессии.")
        add("Параметры запуска:")
        add("  strategy = ${result.strategyLabel}")
        add("  topK = ${result.topK}")
        add("Доступные команды: :memory, :history, :exit")
        add("Введите сообщение:")
    }.joinToString(separator = System.lineSeparator())

    private fun chatTurnText(result: ChatTurnCliResult): String = buildList {
        when (result.retrievalQuery.action) {
            ru.compadre.indexer.chat.retrieval.model.RetrievalAction.SKIPPED -> {
                add("Retrieval пропущен.")
                add("Причина: ${result.retrievalQuery.skipReason}")
                result.ragAnswer?.let { ragAnswer ->
                    add("")
                    add("Ответ:")
                    add(ragAnswer.answer)
                    if (ragAnswer.sources.isNotEmpty()) {
                        add("")
                        addAll(sourcesLines(ragAnswer))
                    }
                    quotesLines(ragAnswer)?.let { quoteLines ->
                        add("")
                        addAll(quoteLines)
                    }
                }
            }

            ru.compadre.indexer.chat.retrieval.model.RetrievalAction.PERFORMED -> {
                val ragAnswer = result.ragAnswer
                if (ragAnswer != null) {
                    add("Ответ:")
                    add(ragAnswer.answer)
                    if (ragAnswer.isRefusal) {
                        add("")
                        add("Статус: не удалось получить ответ с опорой на контекст.")
                    }
                    ragAnswer.warningMessage?.let { warning ->
                        add("")
                        add(warning)
                    }
                    if (ragAnswer.sources.isNotEmpty()) {
                        add("")
                        addAll(sourcesLines(ragAnswer))
                    }
                    quotesLines(ragAnswer)?.let { quoteLines ->
                        add("")
                        addAll(quoteLines)
                    }
                } else {
                    add("Ответ с опорой на контекст пока не получен.")
                }
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun chatMemoryText(result: ChatMemoryViewResult): String = buildList {
        add("Память задачи:")
        add("  goal = ${result.taskState.goal ?: "<пусто>"}")
        add("  constraints = ${result.taskState.constraints.ifEmpty { listOf("<пусто>") }.joinToString()}")
        add(
            "  fixedTerms = ${
                result.taskState.fixedTerms.ifEmpty { emptyList() }
                    .ifEmpty { listOf(null) }
                    .joinToString { term -> term?.let { "${it.term}: ${it.definition}" } ?: "<пусто>" }
            }",
        )
        add("  knownFacts = ${result.taskState.knownFacts.ifEmpty { listOf("<пусто>") }.joinToString()}")
        add("  openQuestions = ${result.taskState.openQuestions.ifEmpty { listOf("<пусто>") }.joinToString()}")
        add("  lastUserIntent = ${result.taskState.lastUserIntent ?: "<пусто>"}")
    }.joinToString(separator = System.lineSeparator())

    private fun chatHistoryText(result: ChatHistoryViewResult): String = buildList {
        add("История сессии:")
        if (result.messages.isEmpty()) {
            add("  <пусто>")
        } else {
            result.messages.forEach { message ->
                add("  [${message.turnId}] ${message.role.name.lowercase()} = ${previewText(message.text)}")
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun postModeUpdateText(result: PostModeUpdateResult): String = buildList {
        if (result.resetToConfig) {
            add("Runtime override post-mode сброшен.")
            add("Используется значение из конфига: ${result.effectivePostMode}")
        } else {
            add("Runtime override post-mode обновлён.")
            add("Новый режим: ${result.effectivePostMode}")
        }
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
        add(result.ragAnswer?.answer ?: result.answer)
        result.ragAnswer?.warningMessage?.let { warning ->
            add("")
            add(warning)
        }

        if (result.mode == "rag") {
            add("")
            addAll(sourcesLines(result.ragAnswer))
            quotesLines(result.ragAnswer)?.let { quoteLines ->
                add("")
                addAll(quoteLines)
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun sourcesLines(ragAnswer: RagAnswer?): List<String> = buildList {
        add("Источники:")

        val sources = ragAnswer?.sources.orEmpty()
        if (sources.isEmpty()) {
            add("  <источники пока не собраны>")
            return@buildList
        }

        sources.forEachIndexed { index, source ->
            add("  ${index + 1}. source = ${source.source}")
            add("     section = ${source.section}")
            add("     chunkId = ${source.chunkId}")
        }
    }

    private fun quotesLines(ragAnswer: RagAnswer?): List<String>? {
        val quotes = ragAnswer?.quotes.orEmpty()
        if (quotes.isEmpty()) {
            return null
        }

        return buildList {
            add("Цитаты:")
            quotes.forEachIndexed { index, quote ->
                add("  ${index + 1}. chunkId = ${quote.chunkId}")
                add("     quote = ${quote.quote}")
            }
        }
    }

    private fun searchText(result: SearchResult): String = buildList {
        add("Команда `search` выполнила semantic search.")
        add("")
        add("Параметры запуска:")
        add("  query = ${result.query}")
        add("  strategy = ${result.strategyLabel}")
        add("  topK = ${result.topK}")
        add("  database = ${result.databasePath}")
        add("")
        addAll(retrievalSummaryLines(result.retrievalResult, result.matches, result.showAllCandidates))
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
        matches: List<SearchMatch>,
        showAllCandidates: Boolean,
    ): List<String> = buildList {
        add("Retrieval-сводка:")

        if (retrievalResult == null) {
            if (matches.isEmpty()) {
                add("  Контекст не найден.")
            } else {
                add("  Режим pipeline недоступен, показаны только финальные совпадения.")
                matches.forEachIndexed { index, match ->
                    addAll(
                        selectedCandidateLines(
                            index + 1,
                            match.embeddedChunk.chunk.metadata.chunkId,
                            match.score,
                            null,
                            null,
                            match.embeddedChunk.chunk.metadata.title,
                            match.embeddedChunk.chunk.metadata.filePath,
                            match.embeddedChunk.chunk.metadata.section,
                            match.embeddedChunk.chunk.text,
                        ),
                    )
                }
            }
            return@buildList
        }

        add("  mode = ${retrievalResult.mode.configValue}")
        add("  initialTopK = ${retrievalResult.initialTopK}")
        add("  finalTopK = ${retrievalResult.finalTopK}")
        add("  candidatesBefore = ${retrievalResult.initialCandidates.size}")
        add("  candidatesSelected = ${retrievalResult.selectedCandidates.size}")
        if (retrievalResult.mode == PostRetrievalMode.MODEL_RERANK) {
            add("  rankingSource = external-llm-json-score")
        }

        if (retrievalResult.candidates.isEmpty()) {
            add("  Контекст не найден.")
            return@buildList
        }

        if (retrievalResult.selectedCandidates.isEmpty()) {
            add("  После ${retrievalResult.mode.configValue} не осталось кандидатов.")
            return@buildList
        }

        val candidatesToRender = if (showAllCandidates) {
            retrievalResult.finalCandidates
        } else {
            retrievalResult.selectedCandidates
        }
        add(if (showAllCandidates) "  Все кандидаты pipeline:" else "  Финальные кандидаты:")
        candidatesToRender.forEachIndexed { index, candidate ->
            addAll(candidateLines(index + 1, candidate, retrievalResult.mode))
        }
    }

    private fun candidateLines(
        index: Int,
        candidate: RetrievalCandidate,
        mode: PostRetrievalMode,
    ): List<String> {
        val chunk = candidate.match.embeddedChunk.chunk
        return buildList {
            add("  ${index}. selected = ${if (candidate.selected) "yes" else "no"}")
            add("     initialRank = ${candidate.initialRank}")
            add("     finalRank = ${candidate.finalRank ?: "-"}")
            add("     cosineScore = ${"%.4f".format(candidate.cosineScore)}")
            add("     finalScore = ${"%.4f".format(candidate.finalScore)}")
            candidate.heuristicScore?.let { add("     heuristicScore = ${"%.4f".format(it)}") }
            if (mode == PostRetrievalMode.MODEL_RERANK) {
                add(
                    "     modelScore = ${
                        candidate.modelScore?.let { score -> "%.4f".format(score) } ?: "n/a"
                    }",
                )
            } else {
                candidate.modelScore?.let { add("     modelScore = ${"%.4f".format(it)}") }
            }
            candidate.decisionReason?.let { reason ->
                add("     decisionMode = ${reason.mode.configValue}")
                add("     decisionReason = ${reason.label}")
            }
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
