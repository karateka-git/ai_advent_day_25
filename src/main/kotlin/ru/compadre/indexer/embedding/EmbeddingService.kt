package ru.compadre.indexer.embedding

import kotlinx.coroutines.delay
import ru.compadre.indexer.config.OllamaSection
import ru.compadre.indexer.embedding.model.ChunkEmbedding
import java.util.logging.Logger

/**
 * Сервис генерации embeddings через локальный Ollama.
 */
class EmbeddingService(
    private val ollamaConfig: OllamaSection,
    private val embeddingClient: OllamaEmbeddingClient = OllamaEmbeddingClient(),
) {
    suspend fun generate(text: String): ChunkEmbedding? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = embeddingClient.embed(
                    baseUrl = ollamaConfig.baseUrl,
                    model = ollamaConfig.embeddingModel,
                    input = text,
                )

                val vector = response.embeddings.firstOrNull()
                    ?: error("Ollama вернул пустой список embeddings.")

                return ChunkEmbedding(
                    model = response.model,
                    vector = vector,
                )
            } catch (error: Exception) {
                val attemptNumber = attempt + 1
                logger.warning(
                    "Не удалось получить embedding через Ollama. " +
                        "Попытка $attemptNumber/$MAX_ATTEMPTS. Ошибка: ${error.message}",
                )

                if (attemptNumber < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        return null
    }

    suspend fun close() {
        embeddingClient.close()
    }

    private companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
        private val logger: Logger = Logger.getLogger(EmbeddingService::class.java.name)
    }
}
