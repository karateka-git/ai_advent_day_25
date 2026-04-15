package ru.compadre.indexer.model

/**
 * Результат разбиения документа на отдельный чанк.
 */
data class DocumentChunk(
    val metadata: ChunkMetadata,
    val strategy: ChunkingStrategy,
    val text: String,
)
