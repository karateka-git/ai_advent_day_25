package ru.compadre.indexer.workflow.command

/**
 * Команда изменения post-retrieval режима для текущей CLI-сессии.
 *
 * Если `postMode == null`, runtime override сбрасывается к значению из конфига.
 */
data class SetPostModeCommand(
    val postMode: String?,
) : WorkflowCommand

