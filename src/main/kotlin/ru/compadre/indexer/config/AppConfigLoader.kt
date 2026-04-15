package ru.compadre.indexer.config

import com.typesafe.config.ConfigFactory

/**
 * Загружает настройки приложения из `application.conf`.
 */
object AppConfigLoader {
    fun load(): AppConfig {
        val config = ConfigFactory.load()

        return AppConfig(
            app = AppSection(
                inputDir = config.getString("app.inputDir"),
                outputDir = config.getString("app.outputDir"),
            ),
            ollama = OllamaSection(
                baseUrl = config.getString("ollama.baseUrl"),
                embeddingModel = config.getString("ollama.embeddingModel"),
            ),
            chunking = ChunkingSection(
                fixedSize = config.getInt("chunking.fixedSize"),
                overlap = config.getInt("chunking.overlap"),
            ),
            search = SearchSection(
                topK = config.getInt("search.topK"),
            ),
        )
    }
}

