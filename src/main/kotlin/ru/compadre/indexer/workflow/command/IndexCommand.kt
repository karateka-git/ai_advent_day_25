package ru.compadre.indexer.workflow.command

/**
 * Команда запуска индексации.
 */
data class IndexCommand(
    val inputDir: String?,
    val strategy: String?,
    val allStrategies: Boolean,
) : WorkflowCommand

