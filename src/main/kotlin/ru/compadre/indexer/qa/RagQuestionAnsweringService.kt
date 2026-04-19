package ru.compadre.indexer.qa

import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagQuote
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
        val answer = llmClient.complete(
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

        return RagAnswer(
            answer = answer,
            sources = buildSources(selectedMatches),
            quotes = buildQuotes(selectedMatches),
            retrievalResult = retrievalResult,
        )
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
     * Quotes are short fragments from retrieved chunks, trimmed for CLI output.
     */
    private fun buildQuotes(matches: List<SearchMatch>): List<RagQuote> =
        matches.mapNotNull { match ->
            val chunk = match.embeddedChunk.chunk
            val quote = chunk.text
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_QUOTE_LENGTH)

            if (quote.isBlank()) {
                null
            } else {
                RagQuote(
                    chunkId = chunk.metadata.chunkId,
                    quote = quote,
                )
            }
        }.distinctBy { quote -> quote.chunkId to quote.quote }

    private fun buildUserPrompt(
        question: String,
        matches: List<SearchMatch>,
    ): String {
        val contextBlock = if (matches.isEmpty()) {
            "Контекст не найден."
        } else {
            matches.mapIndexed { index, match ->
                val chunk = match.embeddedChunk.chunk
                buildString {
                    appendLine("Источник ${index + 1}:")
                    appendLine("score = ${"%.4f".format(match.score)}")
                    appendLine("title = ${chunk.metadata.title}")
                    appendLine("filePath = ${chunk.metadata.filePath}")
                    appendLine("section = ${chunk.metadata.section}")
                    appendLine("text = ${chunk.text}")
                }.trimEnd()
            }.joinToString(separator = "\n\n")
        }

        return buildString {
            appendLine("Контекст:")
            appendLine(contextBlock)
            appendLine()
            appendLine("Вопрос:")
            appendLine(question)
        }.trimEnd()
    }

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val MAX_QUOTE_LENGTH = 180
        private const val SYSTEM_PROMPT =
            "Ты полезный ассистент. Отвечай по контексту из retrieval. Если данных недостаточно, прямо скажи об этом. Не выдумывай факты вне контекста."
    }
}
