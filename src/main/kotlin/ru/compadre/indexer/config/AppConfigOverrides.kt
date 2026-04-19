package ru.compadre.indexer.config

/**
 * Возвращает копию конфига с новым режимом post-retrieval обработки.
 */
fun AppConfig.withPostProcessingMode(postProcessingMode: String): AppConfig =
    copy(
        search = search.copy(
            postProcessingMode = postProcessingMode,
        ),
    )

