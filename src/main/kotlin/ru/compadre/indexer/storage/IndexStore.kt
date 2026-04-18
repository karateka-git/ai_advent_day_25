package ru.compadre.indexer.storage

import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.model.EmbeddedChunk
import ru.compadre.indexer.model.RawDocument
import java.nio.file.Path

/**
 * Контракт локального хранилища индекса.
 */
interface IndexStore {
    fun save(
        databasePath: Path,
        documents: List<RawDocument>,
        embeddedChunks: List<EmbeddedChunk>,
    ): StoredIndexSummary

    /**
     * Читает сохранённые чанки вместе с embeddings из локального индекса.
     */
    fun readEmbeddedChunks(
        databasePath: Path,
        strategy: ChunkingStrategy? = null,
    ): List<EmbeddedChunk>
}
