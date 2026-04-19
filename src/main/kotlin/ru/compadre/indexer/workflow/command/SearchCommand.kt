package ru.compadre.indexer.workflow.command

import ru.compadre.indexer.model.ChunkingStrategy

/**
 * Команда semantic search по локальному индексу.
 */
data class SearchCommand(
    val query: String,
    val strategy: ChunkingStrategy?,
    val topK: Int?,
    val postMode: String? = null,
    val showAllCandidates: Boolean = false,
) : WorkflowCommand
