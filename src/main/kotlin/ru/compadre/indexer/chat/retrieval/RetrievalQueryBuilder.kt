package ru.compadre.indexer.chat.retrieval

import ru.compadre.indexer.chat.model.ChatMessageRecord
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
        recentHistory: List<ChatMessageRecord> = emptyList(),
    ): RetrievalQueryBuildResult {
        val normalizedMessage = userMessage.trim()
        if (normalizedMessage.isBlank()) {
            return skippedShortServiceTurn()
        }

        if (isShortServiceTurn(normalizedMessage)) {
            return skippedShortServiceTurn()
        }

        if (isTaskStateOnlyTurn(normalizedMessage)) {
            return RetrievalQueryBuildResult(
                action = RetrievalAction.SKIPPED,
                skipReason = RetrievalSkipReason.TASK_STATE_UPDATE_ONLY,
            )
        }

        val referencedDocument = referencedDocumentTitle(normalizedMessage, taskState)
        val previousUserQuestion = previousUserQuestion(recentHistory)
        val effectiveIntent = effectiveIntent(normalizedMessage, taskState, previousUserQuestion)
        val answerStyleRequirements = answerStyleRequirements(normalizedMessage, effectiveIntent)
        val relevantFixedTerms = relevantFixedTerms(taskState, effectiveIntent)

        val query = buildString {
            referencedDocument?.let { documentTitle ->
                append("Текст «")
                append(documentTitle)
                appendLine("».")
            }

            appendLine(effectiveIntent)

            if (answerStyleRequirements != null) {
                appendLine()
                appendLine(answerStyleRequirements)
            }

            if (relevantFixedTerms.isNotEmpty()) {
                appendLine()
                appendLine("Термины:")
                relevantFixedTerms.forEach { term ->
                    appendLine("- ${term.term}: ${term.definition}")
                }
            }
        }.trim()

        return RetrievalQueryBuildResult(
            action = RetrievalAction.PERFORMED,
            query = query,
        )
    }

    private fun effectiveIntent(
        userMessage: String,
        taskState: TaskState,
        previousUserQuestion: String?,
    ): String =
        if (isAnswerStyleModifierTurn(userMessage)) {
            taskState.lastUserIntent ?: previousUserQuestion ?: userMessage
        } else {
            taskState.lastUserIntent ?: userMessage
        }

    private fun isShortServiceTurn(userMessage: String): Boolean {
        val normalized = userMessage.lowercase()

        return normalized in SHORT_SERVICE_TURNS
    }

    private fun isTaskStateOnlyTurn(userMessage: String): Boolean {
        val normalized = userMessage.lowercase()

        if (normalized.contains("?")) {
            return false
        }

        return TASK_STATE_ONLY_PREFIXES.any(normalized::startsWith)
    }

    private fun isAnswerStyleModifierTurn(userMessage: String): Boolean {
        val normalized = userMessage.lowercase()
        return ANSWER_STYLE_MARKERS.any(normalized::contains)
    }

    private fun previousUserQuestion(recentHistory: List<ChatMessageRecord>): String? =
        recentHistory
            .asReversed()
            .firstOrNull { message -> message.role.name == "USER" && message.text.isNotBlank() }
            ?.text
            ?.trim()

    private fun answerStyleRequirements(
        userMessage: String,
        effectiveIntent: String,
    ): String? =
        if (isAnswerStyleModifierTurn(userMessage) && userMessage != effectiveIntent) {
            "Формат ответа: $userMessage"
        } else {
            null
        }

    private fun relevantFixedTerms(
        taskState: TaskState,
        effectiveIntent: String,
    ) = taskState.fixedTerms.filter { term ->
        effectiveIntent.contains(term.term, ignoreCase = true) ||
            effectiveIntent.contains(term.definition, ignoreCase = true)
    }

    private fun referencedDocumentTitle(
        userMessage: String,
        taskState: TaskState,
    ): String? =
        extractDocumentTitle(userMessage)
            ?: taskState.constraints.firstNotNullOfOrNull(::extractDocumentTitle)
            ?: taskState.goal?.let(::extractDocumentTitle)

    private fun extractDocumentTitle(text: String): String? =
        DOCUMENT_TITLE_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)

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
        private val TASK_STATE_ONLY_PREFIXES = listOf(
            "будем обсуждать",
            "сначала будем обсуждать",
            "отвечай только",
            "теперь не добавляй",
            "теперь поменяем правило",
            "обсуждаем не",
            "под термином",
            "если данных мало",
        )
        private val ANSWER_STYLE_MARKERS = listOf(
            "коротко",
            "кратко",
            "в 2-3 предложениях",
            "в двух-трех предложениях",
            "по пунктам",
            "подробно",
        )
        private val DOCUMENT_TITLE_REGEX = Regex("""текст\s+[«"]([^»"]+)[»"]""", RegexOption.IGNORE_CASE)
    }
}
