package ru.compadre.indexer.chat.memory.model

import ru.compadre.indexer.chat.model.TaskState

/**
 * Результат unified LLM-step: тип текущего хода плюс обновлённая память задачи.
 */
data class TaskStateUpdateResult(
    val turnType: ChatTurnType,
    val taskState: TaskState,
)
