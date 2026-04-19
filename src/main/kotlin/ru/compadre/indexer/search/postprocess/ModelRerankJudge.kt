package ru.compadre.indexer.search.postprocess

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.DocumentChunk

/**
 * Выполняет model-based оценку релевантности пары `query + chunk`.
 */
class ModelRerankJudge(
    private val llmClient: ExternalLlmClient = ExternalLlmClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Запрашивает у внешней модели числовой relevance score в диапазоне `0..100`.
     */
    fun score(
        query: String,
        chunk: DocumentChunk,
        config: LlmSection,
        fallbackCosineScore: Double,
    ): ModelRerankEvaluation {
        val responseText = llmClient.complete(
            config = config.copy(
                temperature = MODEL_RERANK_TEMPERATURE,
                maxTokens = minOf(config.maxTokens, MODEL_RERANK_MAX_TOKENS),
            ),
            messages = listOf(
                ChatMessage(role = SYSTEM_ROLE, content = SYSTEM_PROMPT),
                ChatMessage(
                    role = USER_ROLE,
                    content = buildUserPrompt(query = query, chunk = chunk),
                ),
            ),
        )

        val parsedScore = parseScore(responseText)
        return if (parsedScore != null) {
            ModelRerankEvaluation(
                score = parsedScore,
                usedFallback = false,
                rawResponse = responseText,
            )
        } else {
            ModelRerankEvaluation(
                score = normalizeFallbackScore(fallbackCosineScore),
                usedFallback = true,
                rawResponse = responseText,
            )
        }
    }

    private fun buildUserPrompt(
        query: String,
        chunk: DocumentChunk,
    ): String = buildString {
        appendLine("Оцени релевантность чанка пользовательскому вопросу.")
        appendLine()
        appendLine("Вопрос:")
        appendLine(query)
        appendLine()
        appendLine("Title:")
        appendLine(chunk.metadata.title)
        appendLine()
        appendLine("Section:")
        appendLine(chunk.metadata.section)
        appendLine()
        appendLine("Chunk text:")
        appendLine(chunk.text)
    }.trimEnd()

    private fun parseScore(responseText: String): Double? =
        runCatching {
            val payload = json.decodeFromString<ModelRerankScorePayload>(responseText.trim())
            payload.score.coerceIn(MIN_MODEL_SCORE, MAX_MODEL_SCORE)
        }.getOrNull()

    private fun normalizeFallbackScore(cosineScore: Double): Double =
        (cosineScore * MAX_MODEL_SCORE).coerceIn(MIN_MODEL_SCORE, MAX_MODEL_SCORE)

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val MODEL_RERANK_TEMPERATURE = 0.0
        private const val MODEL_RERANK_MAX_TOKENS = 32
        private const val MIN_MODEL_SCORE = 0.0
        private const val MAX_MODEL_SCORE = 100.0
        private const val SYSTEM_PROMPT =
            "Ты оцениваешь релевантность фрагмента текста вопросу пользователя. " +
                "Верни только JSON объекта вида {\"score\": число_от_0_до_100}. " +
                "Никакого дополнительного текста, markdown и комментариев."
    }
}

/**
 * Результат model-based оценки релевантности.
 */
data class ModelRerankEvaluation(
    val score: Double,
    val usedFallback: Boolean,
    val rawResponse: String,
)

/**
 * JSON-контракт ответа model-based reranker.
 */
@Serializable
data class ModelRerankScorePayload(
    val score: Double,
)

