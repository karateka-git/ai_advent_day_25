package ru.compadre.indexer.chat.retrieval

import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason

/**
 * Собирает search-friendly retrieval query из текущего сообщения и памяти задачи.
 */
class RetrievalQueryBuilder {
    /**
     * Строит retrieval query для текущего пользовательского хода.
     *
     * @param userMessage новое сообщение пользователя.
     * @param taskState актуальная память задачи.
     * @return результат с query или явной причиной skip-retrieval.
     */
    fun build(
        userMessage: String,
        taskState: TaskState,
    ): RetrievalQueryBuildResult {
        val normalizedMessage = userMessage.trim()
        if (normalizedMessage.isBlank()) {
            return skippedShortServiceTurn()
        }

        if (isShortServiceTurn(normalizedMessage)) {
            return skippedShortServiceTurn()
        }

        val query = buildString {
            appendLine("Текущий вопрос:")
            appendLine(normalizedMessage)

            taskState.goal?.let { goal ->
                appendLine()
                appendLine("Цель диалога:")
                appendLine(goal)
            }

            if (taskState.constraints.isNotEmpty()) {
                appendLine()
                appendLine("Ограничения:")
                taskState.constraints.forEach { constraint ->
                    appendLine("- $constraint")
                }
            }

            if (taskState.fixedTerms.isNotEmpty()) {
                appendLine()
                appendLine("Термины:")
                taskState.fixedTerms.forEach { term ->
                    appendLine("- ${term.term}: ${term.definition}")
                }
            }

            taskState.lastUserIntent?.let { lastUserIntent ->
                appendLine()
                appendLine("Текущее намерение:")
                appendLine(lastUserIntent)
            }
        }.trim()

        return RetrievalQueryBuildResult(
            action = RetrievalAction.PERFORMED,
            query = query,
        )
    }

    private fun isShortServiceTurn(userMessage: String): Boolean {
        val normalized = userMessage.lowercase()

        return normalized in SHORT_SERVICE_TURNS
    }

    private fun skippedShortServiceTurn(): RetrievalQueryBuildResult =
        RetrievalQueryBuildResult(
            action = RetrievalAction.SKIPPED,
            skipReason = RetrievalSkipReason.SHORT_SERVICE_TURN,
        )

    private companion object {
        private val SHORT_SERVICE_TURNS = setOf(
            "да",
            "ага",
            "ок",
            "окей",
            "хорошо",
            "продолжай",
            "давай дальше",
            "угу",
        )
    }
}
