package ru.compadre.indexer.config

/**
 * Корневая конфигурация приложения.
 */
data class AppConfig(
    val app: AppSection,
    val ollama: OllamaSection,
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

