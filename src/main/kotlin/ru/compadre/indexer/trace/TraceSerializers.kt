package ru.compadre.indexer.trace

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.model.ChatCompletionRequest
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.DocumentChunk
import ru.compadre.indexer.search.model.RetrievalCandidate
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.workflow.command.AskCommand
import ru.compadre.indexer.workflow.command.ChatCommand
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
        is ChatCommand -> "chat"
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
        is ChatCommand -> {
            putString("strategy", command.strategy.id)
            putInt("topK", command.topK)
        }

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
    includeText: Boolean = false,
): JsonObject {
    val chunk = match.embeddedChunk.chunk
    return buildJsonObject {
        chunkTracePayload(chunk, includeText = includeText).forEach { (key, value) -> put(key, value) }
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

fun chatMessageTracePayload(message: ChatMessage): JsonObject = tracePayload {
    putString("role", message.role)
    putString("content", message.content)
}

fun chatMessagesTracePayload(messages: List<ChatMessage>) = buildJsonArray {
    messages.forEach { add(chatMessageTracePayload(it)) }
}

fun llmRequestTracePayload(
    config: LlmSection,
    messages: List<ChatMessage>,
): JsonObject =
    traceJson.encodeToJsonElement(
        ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
        ),
    ) as JsonObject

fun llmRequestTraceBody(
    config: LlmSection,
    messages: List<ChatMessage>,
): String =
    traceJson.encodeToString(
        ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
        ),
    )

fun searchMatchesTracePayload(
    matches: List<SearchMatch>,
    includeText: Boolean = false,
) = buildJsonArray {
    matches.forEachIndexed { index, match ->
        add(
            searchMatchTracePayload(
                match = match,
                initialRank = index + 1,
                includeText = includeText,
            ),
        )
    }
}

private val traceJson = Json { encodeDefaults = true }
