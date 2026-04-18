package ru.compadre.indexer.search.model

import ru.compadre.indexer.model.EmbeddedChunk

/**
 * Кандидат поисковой выдачи с вычисленным score.
 */
data class SearchMatch(
    val embeddedChunk: EmbeddedChunk,
    val score: Double,
)
