package ru.compadre.indexer.search.model

import ru.compadre.indexer.model.ChunkingStrategy

/**
 * Контекст запуска post-retrieval обработки.
 */
data class PostRetrievalRequest(
    val query: String,
    val strategy: ChunkingStrategy?,
    val initialTopK: Int,
    val finalTopK: Int,
    val mode: PostRetrievalMode,
)

