package ru.compadre.indexer.workflow.command

/**
 * Команда запуска сравнения стратегий chunking.
 */
data class CompareCommand(
    val inputDir: String?,
) : WorkflowCommand
