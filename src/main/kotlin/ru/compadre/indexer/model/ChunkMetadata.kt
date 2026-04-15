package ru.compadre.indexer.model

/**
 * Метаданные чанка документа для дальнейшей индексации и сравнения стратегий.
 */
data class ChunkMetadata(
    val chunkId: String,
    val documentId: String,
    val sourceType: SourceType,
    val filePath: String,
    val title: String,
    val section: String,
    val startOffset: Int,
    val endOffset: Int,
)
