package ru.compadre.indexer.config

/**
 * Корневая конфигурация приложения.
 */
data class AppConfig(
    val app: AppSection,
    val ollama: OllamaSection,
    val llm: LlmSection,
    val chunking: ChunkingSection,
    val search: SearchSection,
)

/**
 * Настройки путей приложения.
 */
data class AppSection(
    val inputDir: String,
    val outputDir: String,
)

/**
 * Настройки подключения к Ollama.
 */
data class OllamaSection(
    val baseUrl: String,
    val embeddingModel: String,
)

/**
 * Настройки внешнего LLM API для генерации ответа.
 */
data class LlmSection(
    val agentId: String,
    val userToken: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
)

/**
 * Настройки chunking.
 */
data class ChunkingSection(
    val fixedSize: Int,
    val overlap: Int,
)

/**
 * Настройки будущего поиска.
 */
data class SearchSection(
    val topK: Int,
)

