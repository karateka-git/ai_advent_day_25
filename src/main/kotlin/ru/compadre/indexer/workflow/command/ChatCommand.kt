package ru.compadre.indexer.workflow.command

import ru.compadre.indexer.model.ChunkingStrategy

/**
 * Команда запуска mini-chat с RAG и памятью задачи.
 */
data class ChatCommand(
    val strategy: ChunkingStrategy,
    val topK: Int,
) : WorkflowCommand
