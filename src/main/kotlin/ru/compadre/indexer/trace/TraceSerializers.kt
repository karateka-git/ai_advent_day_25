package ru.compadre.indexer.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.DocumentChunk
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.workflow.command.AskCommand
import ru.compadre.indexer.workflow.command.CompareCommand
import ru.compadre.indexer.workflow.command.HelpCommand
import ru.compadre.indexer.workflow.command.IndexCommand
import ru.compadre.indexer.workflow.command.SearchCommand
import ru.compadre.indexer.workflow.command.SetPostModeCommand
import ru.compadre.indexer.workflow.command.WorkflowCommand
import ru.compadre.indexer.workflow.result.CommandResult

fun commandTraceType(command: WorkflowCommand): String =
    when (command) {
        is AskCommand -> "ask"
        is SearchCommand -> "search"
        is IndexCommand -> "index"
        is CompareCommand -> "compare"
        HelpCommand -> "help"
        is SetPostModeCommand -> "set"
    }

fun commandTracePayload(
    command: WorkflowCommand,
    config: AppConfig,
): JsonObject = tracePayload {
    putString("commandType", commandTraceType(command))
    when (command) {
        is AskCommand -> {
            putString("query", command.query)
            putString("mode", command.mode)
            putString("strategy", command.strategy?.id)
            putInt("topK", command.topK)
            putString("postMode", command.postMode ?: config.search.postProcessingMode)
        }

        is SearchCommand -> {
            putString("query", command.query)
            putString("strategy", command.strategy?.id)
            putInt("topK", command.topK)
            putString("postMode", command.postMode ?: config.search.postProcessingMode)
        }

        is IndexCommand -> {
            putString("inputDir", command.inputDir ?: config.app.inputDir)
            putString("strategy", command.strategy?.id)
            putString("allStrategies", command.allStrategies.toString())
        }

        is CompareCommand -> putString("inputDir", command.inputDir ?: config.app.inputDir)
        HelpCommand -> putString("postMode", config.search.postProcessingMode)
        is SetPostModeCommand -> putString("postMode", command.postMode)
    }
}

fun commandResultTracePayload(
    command: WorkflowCommand,
    result: CommandResult,
): JsonObject = tracePayload {
    putString("commandType", commandTraceType(command))
    putString("resultKind", result::class.simpleName ?: "CommandResult")
    putString("status", "success")
}

fun chunkTracePayload(
    chunk: DocumentChunk,
    includeText: Boolean = false,
): JsonObject = tracePayload {
    putString("chunkId", chunk.metadata.chunkId)
    putString("documentId", chunk.metadata.documentId)
    putString("sourceType", chunk.metadata.sourceType.name)
    putString("filePath", chunk.metadata.filePath)
    putString("title", chunk.metadata.title)
    putString("section", chunk.metadata.section)
    putInt("startOffset", chunk.metadata.startOffset)
    putInt("endOffset", chunk.metadata.endOffset)
    putString("strategy", chunk.strategy.id)
    if (includeText) {
        putString("text", chunk.text)
    }
}

fun searchMatchTracePayload(
    match: SearchMatch,
    initialRank: Int? = null,
): JsonObject {
    val chunk = match.embeddedChunk.chunk
    return buildJsonObject {
        chunkTracePayload(chunk).forEach { (key, value) -> put(key, value) }
        putDouble("cosineScore", match.score)
        putInt("initialRank", initialRank)
    }
}

fun retrievalCandidateTracePayload(
    candidate: RetrievalCandidate,
    postProcessingMode: String? = null,
): JsonObject {
    val chunk = candidate.match.embeddedChunk.chunk
    return buildJsonObject {
        chunkTracePayload(chunk).forEach { (key, value) -> put(key, value) }
        putDouble("cosineScore", candidate.cosineScore)
        putDouble("finalScore", candidate.finalScore)
        putDouble("heuristicScore", candidate.heuristicScore)
        putDouble("modelScore", candidate.modelScore)
        putInt("initialRank", candidate.initialRank)
        putInt("finalRank", candidate.finalRank)
        putString("decisionReason", candidate.decisionReason?.javaClass?.simpleName ?: candidate.decisionReason?.toString())
        putString("postProcessingMode", postProcessingMode)
        putBoolean("selected", candidate.selected)
    }
}
