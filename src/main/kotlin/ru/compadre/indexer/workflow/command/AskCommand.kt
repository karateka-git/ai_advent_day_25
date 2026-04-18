package ru.compadre.indexer.workflow.command

import ru.compadre.indexer.model.ChunkingStrategy

/**
 * Команда запроса ответа у модели.
 */
data class AskCommand(
    val query: String,
    val mode: String,
    val strategy: ChunkingStrategy? = null,
    val topK: Int? = null,
) : WorkflowCommand
