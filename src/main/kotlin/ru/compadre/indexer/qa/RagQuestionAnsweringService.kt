package ru.compadre.indexer.qa

import kotlinx.serialization.json.Json
import ru.compadre.indexer.config.AnswerGuardSection
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagModelCompletion
import ru.compadre.indexer.qa.model.RagQuote
import ru.compadre.indexer.qa.model.RagSource
import ru.compadre.indexer.search.RetrievalPipelineService
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.trace.NoOpTraceSink
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.emitRecord
import ru.compadre.indexer.trace.jsonArrayOfStrings
import ru.compadre.indexer.trace.llmRequestTracePayload
import ru.compadre.indexer.trace.putBoolean
import ru.compadre.indexer.trace.putDouble
import ru.compadre.indexer.trace.putInt
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.tracePayload
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Question-answering service that combines retrieval context with an LLM response.
 */
class RagQuestionAnsweringService(
    private val retrievalPipelineService: RetrievalPipelineService,
    private val llmClient: ExternalLlmClient = ExternalLlmClient(),
    private val traceSink: TraceSink = NoOpTraceSink,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    suspend fun answer(
        requestId: String,
        question: String,
        databasePath: Path,
        strategy: ChunkingStrategy,
        initialTopK: Int,
        finalTopK: Int,
        config: AppConfig,
    ): RagAnswer {
        traceSink.emitRecord(
            requestId = requestId,
            kind = "rag_request_started",
            stage = "rag.answer",
            payload = tracePayload {
                putString("question", question)
                putString("databasePath", databasePath.toAbsolutePath().toString())
                putString("strategy", strategy.id)
                putInt("initialTopK", initialTopK)
                putInt("finalTopK", finalTopK)
                putString("postProcessingMode", config.search.postProcessingMode)
            },
        )
        val retrievalResult = retrievalPipelineService.retrieve(
            requestId = requestId,
            query = question,
            databasePath = databasePath,
            strategy = strategy,
            initialTopK = initialTopK,
            finalTopK = finalTopK,
            config = config,
        )
        val selectedMatches = retrievalResult.selectedMatches
        val guardDecision = evaluateAnswerGuard(selectedMatches, config.answerGuard)
        traceSink.emitRecord(
            requestId = requestId,
            kind = "answer_guard_checked",
            stage = "rag.answer_guard",
            payload = tracePayload {
                putString("decision", if (guardDecision.allowed) "allow" else "refuse")
                putBoolean("allowed", guardDecision.allowed)
                putString("reason", guardDecision.reason)
                putInt("selectedMatchesCount", selectedMatches.size)
                putDouble("topScore", selectedMatches.maxOfOrNull(SearchMatch::score))
                putDouble("minTopScore", config.answerGuard.minTopScore)
                putInt("minSelectedChunks", config.answerGuard.minSelectedChunks)
                put("selectedChunkIds", jsonArrayOfStrings(selectedMatches.map { it.embeddedChunk.chunk.metadata.chunkId }))
            },
        )

        if (!guardDecision.allowed) {
            traceSink.emitRecord(
                requestId = requestId,
                kind = "answer_parse_result",
                stage = "rag.answer_parse",
                payload = tracePayload {
                    putString("status", "guard_refused")
                    putBoolean("hasAnswer", true)
                    putBoolean("hasQuotes", false)
                    putString("warningMessage", null)
                    putString("refusalReason", guardDecision.reason)
                    putString("outcome", "guard_refusal")
                },
            )
            return buildLoggedAnswer(
                requestId = requestId,
                ragAnswer = RagAnswer(
                    answer = REFUSAL_ANSWER,
                    sources = emptyList(),
                    quotes = emptyList(),
                    isRefusal = true,
                    refusalReason = guardDecision.reason,
                    retrievalResult = retrievalResult,
                ),
            )
        }

        val userPrompt = buildUserPrompt(question, selectedMatches)
        val messages = listOf(
            ChatMessage(
                role = SYSTEM_ROLE,
                content = SYSTEM_PROMPT,
            ),
            ChatMessage(
                role = USER_ROLE,
                content = userPrompt,
            ),
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "answer_llm_prompt_built",
            stage = "rag.answer_llm_prompt",
            payload = tracePayload {
                put("llmRequest", llmRequestTracePayload(config.llm, messages))
            },
        )
        val completion = llmClient.complete(
            config = config.llm,
            messages = messages,
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "answer_llm_completed",
            stage = "rag.answer_llm",
            payload = tracePayload {
                putString("llmResponse", completion)
            },
        )
        val parsedCompletion = parseModelCompletion(completion)

        if (parsedCompletion == null) {
            traceSink.emitRecord(
                requestId = requestId,
                kind = "answer_parse_result",
                stage = "rag.answer_parse",
                payload = tracePayload {
                    putString("status", "failure")
                    putBoolean("hasAnswer", false)
                    putBoolean("hasQuotes", false)
                    putString("warningMessage", null)
                    putString("refusalReason", "llm_response_invalid_json_or_missing_required_fields")
                    putString("outcome", "invalid_json_or_missing_required_fields")
                },
            )
            logInvalidModelCompletion(
                outputDir = config.app.outputDir,
                question = question,
                rawCompletion = completion,
                selectedMatches = selectedMatches,
                failureKind = "invalid_json_or_missing_required_fields",
            )
            return buildLoggedAnswer(
                requestId = requestId,
                ragAnswer = RagAnswer(
                    answer = parseFailureAnswer(),
                    sources = buildSources(selectedMatches),
                    quotes = emptyList(),
                    isRefusal = true,
                    refusalReason = "llm_response_invalid_json_or_missing_required_fields",
                    retrievalResult = retrievalResult,
                ),
            )
        }

        if (parsedCompletion.quotes.isEmpty()) {
            if (looksLikeRefusal(parsedCompletion.answer)) {
                traceSink.emitRecord(
                    requestId = requestId,
                    kind = "answer_parse_result",
                    stage = "rag.answer_parse",
                    payload = tracePayload {
                        putString("status", "success")
                        putBoolean("hasAnswer", true)
                        putBoolean("hasQuotes", false)
                        putString("warningMessage", null)
                        putString("refusalReason", "llm_refusal_due_to_missing_grounding_quote")
                        putString("outcome", "model_refusal_without_quotes")
                    },
                )
                return buildLoggedAnswer(
                    requestId = requestId,
                    ragAnswer = RagAnswer(
                        answer = parsedCompletion.answer,
                        sources = emptyList(),
                        quotes = emptyList(),
                        isRefusal = true,
                        refusalReason = "llm_refusal_due_to_missing_grounding_quote",
                        retrievalResult = retrievalResult,
                    ),
                )
            }

            traceSink.emitRecord(
                requestId = requestId,
                kind = "answer_parse_result",
                stage = "rag.answer_parse",
                payload = tracePayload {
                    putString("status", "success")
                    putBoolean("hasAnswer", true)
                    putBoolean("hasQuotes", false)
                    putString("warningMessage", MISSING_QUOTES_WARNING)
                    putString("refusalReason", null)
                    putString("outcome", "missing_quotes_fallback")
                },
            )
            logInvalidModelCompletion(
                outputDir = config.app.outputDir,
                question = question,
                rawCompletion = completion,
                selectedMatches = selectedMatches,
                failureKind = "missing_quotes",
            )
            return buildLoggedAnswer(
                requestId = requestId,
                ragAnswer = RagAnswer(
                    answer = MISSING_QUOTES_ANSWER,
                    sources = buildSources(selectedMatches),
                    quotes = emptyList(),
                    warningMessage = MISSING_QUOTES_WARNING,
                    retrievalResult = retrievalResult,
                ),
            )
        }

        traceSink.emitRecord(
            requestId = requestId,
            kind = "answer_parse_result",
            stage = "rag.answer_parse",
            payload = tracePayload {
                putString("status", "success")
                putBoolean("hasAnswer", true)
                putBoolean("hasQuotes", true)
                putString("warningMessage", null)
                putString("refusalReason", null)
                putString("outcome", "grounded_answer")
            },
        )
        return buildLoggedAnswer(
            requestId = requestId,
            ragAnswer = RagAnswer(
                answer = parsedCompletion.answer,
                sources = buildSources(selectedMatches),
                quotes = parsedCompletion.quotes,
                retrievalResult = retrievalResult,
            ),
        )
    }

    private fun buildLoggedAnswer(
        requestId: String,
        ragAnswer: RagAnswer,
    ): RagAnswer {
        traceSink.emitRecord(
            requestId = requestId,
            kind = "rag_answer_built",
            stage = "rag.answer_result",
            payload = tracePayload {
                putString("answer", ragAnswer.answer)
                putInt("sourcesCount", ragAnswer.sources.size)
                putInt("quotesCount", ragAnswer.quotes.size)
                putString("warningMessage", ragAnswer.warningMessage)
                putBoolean("isRefusal", ragAnswer.isRefusal)
                putString("refusalReason", ragAnswer.refusalReason)
                put("sourceChunkIds", jsonArrayOfStrings(ragAnswer.sources.map(RagSource::chunkId)))
                put("quoteChunkIds", jsonArrayOfStrings(ragAnswer.quotes.map(RagQuote::chunkId)))
            },
        )
        return ragAnswer
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
        val validChunkIds = matches.joinToString(separator = ", ") { match ->
            match.embeddedChunk.chunk.metadata.chunkId
        }.ifBlank { "<none>" }

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
            appendLine("Valid chunkIds:")
            appendLine(validChunkIds)
            appendLine()
            appendLine("Question:")
            appendLine(question)
        }.trimEnd()
    }

    private fun parseFailureAnswer(): String =
        "\u041d\u0435 \u0437\u043d\u0430\u044e. \u0423\u0442\u043e\u0447\u043d\u0438\u0442\u0435 \u0432\u043e\u043f\u0440\u043e\u0441: \u043c\u043e\u0434\u0435\u043b\u044c \u0432\u0435\u0440\u043d\u0443\u043b\u0430 \u043d\u0435\u0432\u0430\u043b\u0438\u0434\u043d\u044b\u0439 JSON \u0438\u043b\u0438 \u043f\u0443\u0441\u0442\u043e\u0439 \u043e\u0431\u044f\u0437\u0430\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u043e\u0442\u0432\u0435\u0442."

    private fun looksLikeRefusal(answer: String): Boolean {
        val normalized = answer.lowercase()
        return normalized.contains("\u043d\u0435 \u0437\u043d\u0430\u044e") ||
            normalized.contains("\u0443\u0442\u043e\u0447\u043d\u0438\u0442\u0435 \u0432\u043e\u043f\u0440\u043e\u0441") ||
            normalized.contains("\u043d\u0435\u0434\u043e\u0441\u0442\u0430\u0442\u043e\u0447\u043d\u043e \u0434\u0430\u043d\u043d\u044b\u0445") ||
            normalized.contains("\u043d\u0435 \u043c\u043e\u0433\u0443 \u043e\u0442\u0432\u0435\u0442\u0438\u0442\u044c") ||
            normalized.contains("insufficient context")
    }

    private fun logInvalidModelCompletion(
        outputDir: String,
        question: String,
        rawCompletion: String,
        selectedMatches: List<SearchMatch>,
        failureKind: String,
    ) {
        val logPath = Path.of(outputDir).resolve("logs").resolve("rag-llm-format.log")
        Files.createDirectories(logPath.parent)

        val selectedChunks = if (selectedMatches.isEmpty()) {
            "<no selected matches>"
        } else {
            selectedMatches.joinToString(separator = System.lineSeparator()) { match ->
                val chunk = match.embeddedChunk.chunk
                "chunkId=${chunk.metadata.chunkId}; score=${"%.4f".format(match.score)}; filePath=${chunk.metadata.filePath}; section=${chunk.metadata.section}"
            }
        }

        val logEntry = buildString {
            appendLine("timestamp = ${Instant.now()}")
            appendLine("failureKind = $failureKind")
            appendLine("question = $question")
            appendLine("selectedMatches:")
            appendLine(selectedChunks)
            appendLine("rawCompletion:")
            appendLine(rawCompletion)
            appendLine("---")
        }

        Files.writeString(
            logPath,
            logEntry,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val MAX_QUOTES = 1
        private const val REFUSAL_ANSWER =
            "\u041d\u0435 \u0437\u043d\u0430\u044e. \u0423\u0442\u043e\u0447\u043d\u0438\u0442\u0435 \u0432\u043e\u043f\u0440\u043e\u0441: \u0432 \u043d\u0430\u0439\u0434\u0435\u043d\u043d\u043e\u043c \u043a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u0435 \u043d\u0435\u0434\u043e\u0441\u0442\u0430\u0442\u043e\u0447\u043d\u043e \u0434\u0430\u043d\u043d\u044b\u0445 \u0434\u043b\u044f \u0443\u0432\u0435\u0440\u0435\u043d\u043d\u043e\u0433\u043e \u043e\u0442\u0432\u0435\u0442\u0430."
        private const val MISSING_QUOTES_ANSWER =
            "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043d\u0430\u0434\u0451\u0436\u043d\u043e \u043e\u0442\u0432\u0435\u0442\u0438\u0442\u044c \u043f\u043e \u043d\u0430\u0439\u0434\u0435\u043d\u043d\u043e\u043c\u0443 \u043a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u0443."
        private const val MISSING_QUOTES_WARNING =
            "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u044c \u044d\u0442\u043e\u0442 \u043e\u0442\u0432\u0435\u0442 \u0446\u0438\u0442\u0430\u0442\u043e\u0439 \u0438\u0437 \u043d\u0430\u0439\u0434\u0435\u043d\u043d\u043e\u0433\u043e \u043a\u043e\u043d\u0442\u0435\u043a\u0441\u0442\u0430."
        private val SYSTEM_PROMPT = """
            Ты ассистент, который отвечает только на основе найденного retrieval-контекста.
            Верни ровно один JSON-объект и ничего больше.

            Обязательная схема:
            {
              "answer": "string",
              "quotes": [
                { "chunkId": "string", "quote": "string" }
              ]
            }

            Правила:
            - Для grounded-ответа верни ровно одну цитату.
            - Цитата должна быть коротким дословным фрагментом из переданного контекста.
            - Цитата должна ссылаться на существующий chunkId из контекста.
            - Выбери одну лучшую цитату, которая наиболее прямо подтверждает ответ.
            - Отвечай на том же языке, что и вопрос пользователя.
            - Не добавляй markdown, code fences, объяснения или лишние поля.
            - Если не можешь подтвердить ответ одной прямой цитатой, верни отказ вида "\u041d\u0435 \u0437\u043d\u0430\u044e. \u0423\u0442\u043e\u0447\u043d\u0438\u0442\u0435 \u0432\u043e\u043f\u0440\u043e\u0441: ..." и оставь quotes пустым.
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
