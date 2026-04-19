package ru.compadre.indexer.cli

import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.search.model.PostRetrievalMode
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
import ru.compadre.indexer.workflow.result.PostModeUpdateResult
import ru.compadre.indexer.workflow.result.SearchResult

/**
 * Formatter for CLI output used in the project.
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
    }

    private fun helpText(result: HelpResult): String = buildList {
        add("Local Document Indexer")
        add("")
        add("Р”РѕСЃС‚СѓРїРЅС‹Рµ РєРѕРјР°РЅРґС‹:")
        add("  index --input <dir> --strategy <fixed|structured>")
        add("  index --input <dir> --all-strategies")
        add("  compare --input <dir>")
        add("  ask --query <text> --mode plain")
        add("  ask --query <text> --mode rag --strategy <fixed|structured> --top <N> --post-mode <mode> --show-all-candidates")
        add("  search --query <text> --strategy <fixed|structured> --top <N> --post-mode <mode> --show-all-candidates")
        add("  set --post-mode <none|threshold-filter|heuristic-filter|heuristic-rerank|model-rerank|config>")
        add("  help")
        add("")
        add("РўРµРєСѓС‰РёР№ РєРѕРЅС„РёРі:")
        add("  inputDir = ${result.inputDir}")
        add("  outputDir = ${result.outputDir}")
        add("  ollama.baseUrl = ${result.ollamaBaseUrl}")
        add("  ollama.embeddingModel = ${result.embeddingModel}")
        add("  chunking.fixedSize = ${result.fixedSize}")
        add("  chunking.overlap = ${result.overlap}")
        add("  search.postProcessingMode = ${result.postProcessingMode}")
        add("")
        add("РўРµРєСѓС‰РёР№ СЃС‚Р°С‚СѓСЃ: index СЃРѕС…СЂР°РЅСЏРµС‚ SQLite-РёРЅРґРµРєСЃ, compare СЃС‚СЂРѕРёС‚ comparison.md, ask РїРѕРґРґРµСЂР¶РёРІР°РµС‚ plain Рё rag, search РїРѕРєР°Р·С‹РІР°РµС‚ retrieval topK.")
    }.joinToString(separator = System.lineSeparator())

    private fun postModeUpdateText(result: PostModeUpdateResult): String = buildList {
        if (result.resetToConfig) {
            add("Runtime override post-mode СЃР±СЂРѕС€РµРЅ.")
            add("РСЃРїРѕР»СЊР·СѓРµС‚СЃСЏ Р·РЅР°С‡РµРЅРёРµ РёР· РєРѕРЅС„РёРіР°: ${result.effectivePostMode}")
        } else {
            add("Runtime override post-mode РѕР±РЅРѕРІР»С‘РЅ.")
            add("РќРѕРІС‹Р№ СЂРµР¶РёРј: ${result.effectivePostMode}")
        }
    }.joinToString(separator = System.lineSeparator())

    private fun askText(result: AskResult): String = buildList {
        add("РљРѕРјР°РЅРґР° `ask` РїРѕР»СѓС‡РёР»Р° РѕС‚РІРµС‚ РјРѕРґРµР»Рё.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  mode = ${result.mode}")
        add("  query = ${result.query}")

        if (result.mode == "rag") {
            add("  strategy = ${result.strategyLabel ?: "<РЅРµ СѓРєР°Р·Р°РЅР°>"}")
            add("  topK = ${result.topK ?: 0}")
            add("  database = ${result.databasePath ?: "<РЅРµ СѓРєР°Р·Р°РЅР°>"}")
        }

        add("")
        add("РћС‚РІРµС‚:")
        add(result.ragAnswer?.answer ?: result.answer)

        if (result.mode == "rag") {
            add("")
            addAll(sourcesLines(result.ragAnswer))
            add("")
            addAll(quotesLines(result.ragAnswer))
            add("")
            addAll(retrievalSummaryLines(result.retrievalResult, result.matches, result.showAllCandidates))
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

    private fun quotesLines(ragAnswer: RagAnswer?): List<String> = buildList {
        add("Цитаты:")

        val quotes = ragAnswer?.quotes.orEmpty()
        if (quotes.isEmpty()) {
            add("  <цитаты пока не собраны>")
            return@buildList
        }

        quotes.forEachIndexed { index, quote ->
            add("  ${index + 1}. chunkId = ${quote.chunkId}")
            add("     quote = ${quote.quote}")
        }
    }

    private fun searchText(result: SearchResult): String = buildList {
        add("РљРѕРјР°РЅРґР° `search` РІС‹РїРѕР»РЅРёР»Р° semantic search.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  query = ${result.query}")
        add("  strategy = ${result.strategyLabel}")
        add("  topK = ${result.topK}")
        add("  database = ${result.databasePath}")
        add("")

        addAll(retrievalSummaryLines(result.retrievalResult, result.matches, result.showAllCandidates))
    }.joinToString(separator = System.lineSeparator())

    private fun indexPersistText(result: IndexPersistResult): String = buildList {
        add("РљРѕРјР°РЅРґР° `index` Р·Р°РІРµСЂС€РёР»Р° Р»РѕРєР°Р»СЊРЅСѓСЋ РёРЅРґРµРєСЃР°С†РёСЋ.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("  database = ${result.databasePath}")
        add("")
        add("РЎРІРѕРґРєР° РёРЅРґРµРєСЃР°С†РёРё:")
        add("  РљРѕР»РёС‡РµСЃС‚РІРѕ РґРѕРєСѓРјРµРЅС‚РѕРІ = ${result.documentsCount}")
        add("  РџРѕРґРіРѕС‚РѕРІР»РµРЅРѕ С‡Р°РЅРєРѕРІ = ${result.chunksPrepared}")
        add("  РЎРѕС…СЂР°РЅРµРЅРѕ С‡Р°РЅРєРѕРІ = ${result.chunksStored}")
        add("  РЎРѕС…СЂР°РЅРµРЅРѕ embeddings = ${result.embeddingsStored}")
        add("  РЎС‚СЂР°С‚РµРіРёРё РІ РёРЅРґРµРєСЃРµ = ${result.strategiesStored.joinToString()}")

        if (result.skippedChunkIds.isEmpty()) {
            add("  РџСЂРѕРїСѓС‰РµРЅРѕ С‡Р°РЅРєРѕРІ = 0")
        } else {
            add("  РџСЂРѕРїСѓС‰РµРЅРѕ С‡Р°РЅРєРѕРІ = ${result.skippedChunkIds.size}")
            add("РџСЂРѕРїСѓС‰РµРЅРЅС‹Рµ С‡Р°РЅРєРё:")
            result.skippedChunkIds.take(10).forEach { chunkId ->
                add("  - $chunkId")
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun compareReportText(result: CompareReportResult): String = buildList {
        add("РљРѕРјР°РЅРґР° `compare` Р·Р°РІРµСЂС€РёР»Р° СЃСЂР°РІРЅРµРЅРёРµ СЃС‚СЂР°С‚РµРіРёР№ chunking.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  inputDir = ${result.inputDir}")
        add("  outputDir = ${result.outputDir}")
        add("  report = ${result.reportPath}")
        add("")
        add("РЎРІРѕРґРєР°:")
        add("  РљРѕР»РёС‡РµСЃС‚РІРѕ РґРѕРєСѓРјРµРЅС‚РѕРІ = ${result.report.documentsCount}")
        add("  Fixed: РєРѕР»РёС‡РµСЃС‚РІРѕ С‡Р°РЅРєРѕРІ = ${result.report.fixedMetrics.chunksCount}")
        add("  Fixed: СЃСЂРµРґРЅСЏСЏ РґР»РёРЅР° = ${result.report.fixedMetrics.averageLength.toInt()}")
        add("  Structured: РєРѕР»РёС‡РµСЃС‚РІРѕ С‡Р°РЅРєРѕРІ = ${result.report.structuredMetrics.chunksCount}")
        add("  Structured: СЃСЂРµРґРЅСЏСЏ РґР»РёРЅР° = ${result.report.structuredMetrics.averageLength.toInt()}")
        add("")
        add("Р Р°СЃРїСЂРµРґРµР»РµРЅРёРµ РґР»РёРЅ РґР»СЏ fixed:")
        if (result.report.fixedMetrics.lengthBuckets.isEmpty()) {
            add("  - РїСѓСЃС‚Рѕ")
        } else {
            result.report.fixedMetrics.lengthBuckets.forEach { bucket ->
                add("  - ${bucket.rangeLabel}: ${bucket.count}")
            }
        }
        add("Р Р°СЃРїСЂРµРґРµР»РµРЅРёРµ РґР»РёРЅ РґР»СЏ structured:")
        if (result.report.structuredMetrics.lengthBuckets.isEmpty()) {
            add("  - РїСѓСЃС‚Рѕ")
        } else {
            result.report.structuredMetrics.lengthBuckets.forEach { bucket ->
                add("  - ${bucket.rangeLabel}: ${bucket.count}")
            }
        }
    }.joinToString(separator = System.lineSeparator())

    private fun chunkPreviewText(result: ChunkPreviewResult): String = buildList {
        add("РљРѕРјР°РЅРґР° `${result.commandName}` РІС‹РїРѕР»РЅРёР»Р° preview chunking.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("")
        add("РќР°Р№РґРµРЅРѕ РґРѕРєСѓРјРµРЅС‚РѕРІ: ${result.documents.size}")
        add("РЎС„РѕСЂРјРёСЂРѕРІР°РЅРѕ С‡Р°РЅРєРѕРІ: ${result.chunks.size}")

        val byStrategy = result.chunks.groupBy { it.strategy }
        if (byStrategy.isNotEmpty()) {
            add("Р§Р°РЅРєРѕРІ РїРѕ СЃС‚СЂР°С‚РµРіРёСЏРј:")
            byStrategy.forEach { (strategy, chunks) ->
                add("  - ${strategy.id}: ${chunks.size}")
            }
        }

        if (result.chunks.isEmpty()) {
            add("Р§Р°РЅРєРё РЅРµ СЃС„РѕСЂРјРёСЂРѕРІР°РЅС‹.")
        } else {
            add("РџРµСЂРІС‹Рµ С‡Р°РЅРєРё:")
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
            add("Preview embeddings РЅРµ РїРѕР»СѓС‡РµРЅС‹.")
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
        add("РљРѕРјР°РЅРґР° `${result.commandName}` РІС‹РїРѕР»РЅРёР»Р° Р·Р°РіСЂСѓР·РєСѓ РґРѕРєСѓРјРµРЅС‚РѕРІ.")
        add("")
        add("РџР°СЂР°РјРµС‚СЂС‹ Р·Р°РїСѓСЃРєР°:")
        add("  inputDir = ${result.inputDir}")
        add("  strategy = ${result.strategyLabel}")
        add("  outputDir = ${result.outputDir}")
        add("")
        add("РќР°Р№РґРµРЅРѕ РґРѕРєСѓРјРµРЅС‚РѕРІ: ${result.documents.size}")
    }.joinToString(separator = System.lineSeparator())

    private fun retrievalSummaryLines(
        retrievalResult: RetrievalPipelineResult? ,
        matches: List<ru.compadre.indexer.search.model.SearchMatch>,
        showAllCandidates: Boolean,
    ): List<String> = buildList {
        add("Retrieval-СЃРІРѕРґРєР°:")

        if (retrievalResult == null) {
            if (matches.isEmpty()) {
                add("  РљРѕРЅС‚РµРєСЃС‚ РЅРµ РЅР°Р№РґРµРЅ.")
            } else {
                add("  Р РµР¶РёРј pipeline РЅРµРґРѕСЃС‚СѓРїРµРЅ, РїРѕРєР°Р·Р°РЅС‹ С‚РѕР»СЊРєРѕ С„РёРЅР°Р»СЊРЅС‹Рµ СЃРѕРІРїР°РґРµРЅРёСЏ.")
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
            add("  РљРѕРЅС‚РµРєСЃС‚ РЅРµ РЅР°Р№РґРµРЅ.")
            return@buildList
        }

        if (retrievalResult.selectedCandidates.isEmpty()) {
            add("  РџРѕСЃР»Рµ ${retrievalResult.mode.configValue} РЅРµ РѕСЃС‚Р°Р»РѕСЃСЊ РєР°РЅРґРёРґР°С‚РѕРІ.")
            return@buildList
        }

        val candidatesToRender = if (showAllCandidates) {
            retrievalResult.finalCandidates
        } else {
            retrievalResult.selectedCandidates
        }
        add(if (showAllCandidates) "  Р’СЃРµ РєР°РЅРґРёРґР°С‚С‹ pipeline:" else "  Р¤РёРЅР°Р»СЊРЅС‹Рµ РєР°РЅРґРёРґР°С‚С‹:")
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
            return "<РїСѓСЃС‚Рѕ>"
        }

        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 80) {
            singleLine
        } else {
            singleLine.take(77) + "..."
        }
    }
}
