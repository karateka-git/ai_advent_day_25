package ru.compadre.indexer.qa

import kotlinx.serialization.json.Json
import ru.compadre.indexer.config.AnswerGuardSection
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagQuote
import ru.compadre.indexer.qa.model.RagModelCompletion
import ru.compadre.indexer.qa.model.RagSource
import ru.compadre.indexer.search.RetrievalPipelineService
import ru.compadre.indexer.search.model.SearchMatch
import java.nio.file.Path

/**
 * Question-answering service that combines retrieval context with an LLM response.
 */
class RagQuestionAnsweringService(
    private val retrievalPipelineService: RetrievalPipelineService,
    private val llmClient: ExternalLlmClient = ExternalLlmClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    suspend fun answer(
        question: String,
        databasePath: Path,
        strategy: ChunkingStrategy,
        initialTopK: Int,
        finalTopK: Int,
        config: AppConfig,
    ): RagAnswer {
        val retrievalResult = retrievalPipelineService.retrieve(
            query = question,
            databasePath = databasePath,
            strategy = strategy,
            initialTopK = initialTopK,
            finalTopK = finalTopK,
            config = config,
        )
        val selectedMatches = retrievalResult.selectedMatches
        val guardDecision = evaluateAnswerGuard(selectedMatches, config.answerGuard)

        if (!guardDecision.allowed) {
            return RagAnswer(
                answer = REFUSAL_ANSWER,
                sources = emptyList(),
                quotes = emptyList(),
                isRefusal = true,
                refusalReason = guardDecision.reason,
                retrievalResult = retrievalResult,
            )
        }

        val completion = llmClient.complete(
            config = config.llm,
            messages = listOf(
                ChatMessage(
                    role = SYSTEM_ROLE,
                    content = SYSTEM_PROMPT,
                ),
                ChatMessage(
                    role = USER_ROLE,
                    content = buildUserPrompt(question, selectedMatches),
                ),
            ),
        )
        val parsedCompletion = parseModelCompletion(completion)

        if (parsedCompletion == null) {
            return RagAnswer(
                answer = parseFailureAnswer(),
                sources = buildSources(selectedMatches),
                quotes = emptyList(),
                isRefusal = true,
                refusalReason = "llm_response_invalid_json_or_missing_required_fields",
                retrievalResult = retrievalResult,
            )
        }

        return RagAnswer(
            answer = parsedCompletion.answer,
            sources = buildSources(selectedMatches),
            quotes = parsedCompletion.quotes,
            retrievalResult = retrievalResult,
        )
    }

    private fun evaluateAnswerGuard(
        matches: List<SearchMatch>,
        guardConfig: AnswerGuardSection,
    ): GuardDecision {
        if (!guardConfig.enabled) {
            return GuardDecision(allowed = true, reason = null)
        }

        if (matches.size < guardConfig.minSelectedChunks) {
            return GuardDecision(
                allowed = false,
                reason = "selectedMatches=${matches.size} < minSelectedChunks=${guardConfig.minSelectedChunks}",
            )
        }

        val topScore = matches.maxOfOrNull { match -> match.score } ?: 0.0
        if (topScore < guardConfig.minTopScore) {
            return GuardDecision(
                allowed = false,
                reason = "topScore=${"%.4f".format(topScore)} < minTopScore=${"%.4f".format(guardConfig.minTopScore)}",
            )
        }

        return GuardDecision(allowed = true, reason = null)
    }

    /**
     * Source metadata is derived directly from selected retrieval matches.
     */
    private fun buildSources(matches: List<SearchMatch>): List<RagSource> =
        matches.map { match ->
            val chunk = match.embeddedChunk.chunk
            RagSource(
                source = chunk.metadata.filePath,
                section = chunk.metadata.section,
                chunkId = chunk.metadata.chunkId,
            )
        }.distinctBy { source -> source.chunkId }

    /**
     * Parse the model response into a structured answer payload.
     */
    private fun parseModelCompletion(rawCompletion: String): ParsedModelCompletion? {
        val jsonPayload = extractJsonPayload(rawCompletion)
        val payload = runCatching {
            json.decodeFromString<RagModelCompletion>(jsonPayload)
        }.getOrNull() ?: return null

        val answer = payload.answer?.trim().orEmpty()
        if (answer.isBlank()) {
            return null
        }

        val quotes = payload.quotes
            .mapNotNull { quote ->
                val chunkId = quote.chunkId?.trim().orEmpty()
                val text = quote.quote?.trim().orEmpty().ifBlank { quote.text?.trim().orEmpty() }

                if (chunkId.isBlank() || text.isBlank()) {
                    null
                } else {
                    RagQuote(
                        chunkId = chunkId,
                        quote = text,
                    )
                }
            }
            .take(MAX_QUOTES)

        if (quotes.isEmpty()) {
            return null
        }

        return ParsedModelCompletion(
            answer = answer,
            quotes = quotes,
        )
    }

    private fun extractJsonPayload(rawCompletion: String): String {
        val trimmed = rawCompletion.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }

        val lines = trimmed.lines()
        val startIndex = lines.indexOfFirst { it.trim().startsWith("```") }
        val endIndex = lines.indexOfLast { it.trim() == "```" }
        if (startIndex >= 0 && endIndex > startIndex) {
            return lines.subList(startIndex + 1, endIndex).joinToString(separator = "\n").trim()
        }

        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun buildUserPrompt(
        question: String,
        matches: List<SearchMatch>,
    ): String {
        val contextBlock = if (matches.isEmpty()) {
            "No retrieval context was found."
        } else {
            matches.mapIndexed { index, match ->
                val chunk = match.embeddedChunk.chunk
                buildString {
                    appendLine("Chunk ${index + 1}:")
                    appendLine("chunkId = ${chunk.metadata.chunkId}")
                    appendLine("score = ${"%.4f".format(match.score)}")
                    appendLine("title = ${chunk.metadata.title}")
                    appendLine("filePath = ${chunk.metadata.filePath}")
                    appendLine("section = ${chunk.metadata.section}")
                    appendLine("text = ${chunk.text}")
                }.trimEnd()
            }.joinToString(separator = "\n\n")
        }

        return buildString {
            appendLine("Context:")
            appendLine(contextBlock)
            appendLine()
            appendLine("Question:")
            appendLine(question)
        }.trimEnd()
    }

    private fun parseFailureAnswer(): String =
        "не знаю. Уточните вопрос: модель вернула невалидный JSON или пустой обязательный ответ."

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val MAX_QUOTES = 3
        private const val REFUSAL_ANSWER =
            "не знаю. Уточните вопрос: в найденном контексте недостаточно данных для уверенного ответа."
        private val SYSTEM_PROMPT = """
            You are a retrieval-grounded assistant.
            Return exactly one JSON object and nothing else.

            Required schema:
            {
              "answer": "string",
              "quotes": [
                { "chunkId": "string", "quote": "string" }
              ]
            }

            Rules:
            - Use 1 to 3 quotes.
            - Each quote must be a direct excerpt from the provided context.
            - Every quote must reference an existing chunkId from the context.
            - Keep quotes short and relevant.
            - Answer in the same language as the question.
            - Do not add markdown, code fences, explanations, or extra keys.
            - If the context is insufficient, set answer to a refusal like "не знаю. Уточните вопрос: ..." and leave quotes empty.
        """.trimIndent()
    }

    private data class GuardDecision(
        val allowed: Boolean,
        val reason: String?,
    )

    private data class ParsedModelCompletion(
        val answer: String,
        val quotes: List<RagQuote>,
    )
}
