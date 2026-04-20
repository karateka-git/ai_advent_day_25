package ru.compadre.indexer.chat.memory

import kotlinx.serialization.json.Json
import ru.compadre.indexer.chat.memory.model.TaskStateUpdateCompletion
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.ChatCompletionClient
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.trace.NoOpTraceSink
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.chatHistoryTracePayload
import ru.compadre.indexer.trace.emitRecord
import ru.compadre.indexer.trace.putBoolean
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.taskStateTracePayload
import ru.compadre.indexer.trace.tracePayload

/**
 * Обновляет компактную память задачи по предыдущему состоянию и новым сообщениям диалога.
 */
class TaskStateUpdateService(
    private val llmClient: ChatCompletionClient = ExternalLlmClient(),
    private val traceSink: TraceSink = NoOpTraceSink,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    /**
     * Пытается обновить `TaskState` через отдельный LLM-step.
     *
     * @param previousTaskState предыдущее состояние памяти задачи.
     * @param recentHistory последние релевантные сообщения диалога.
     * @param userMessage новое сообщение пользователя.
     * @param config настройки LLM.
     * @return новый `TaskState` или предыдущий state при невалидном update-результате.
     */
    fun update(
        requestId: String,
        previousTaskState: TaskState,
        recentHistory: List<ChatMessageRecord>,
        userMessage: String,
        config: LlmSection,
    ): TaskState {
        val messages = buildMessages(
            previousTaskState = previousTaskState,
            recentHistory = recentHistory,
            userMessage = userMessage,
        )
        val completion = llmClient.complete(config, messages)
        val updatedTaskState = parseModelCompletion(completion) ?: previousTaskState
        val appliedFallback = updatedTaskState == previousTaskState

        traceSink.emitRecord(
            requestId = requestId,
            kind = "task_state_updated",
            stage = "chat.memory_update",
            payload = tracePayload {
                putString("userMessage", userMessage)
                putBoolean("appliedFallback", appliedFallback)
                put("recentHistory", chatHistoryTracePayload(recentHistory))
                put("previousTaskState", taskStateTracePayload(previousTaskState))
                put("newTaskState", taskStateTracePayload(updatedTaskState))
                putString("fallbackReason", if (appliedFallback) "invalid_or_empty_memory_update" else null)
            },
        )

        return updatedTaskState
    }

    internal fun parseModelCompletion(rawCompletion: String): TaskState? {
        val jsonPayload = extractJsonPayload(rawCompletion)
        val payload = runCatching {
            json.decodeFromString<TaskStateUpdateCompletion>(jsonPayload)
        }.getOrNull() ?: return null

        val taskState = TaskState(
            goal = payload.goal.normalizeSingleValue(),
            constraints = payload.constraints.normalizeValues(MAX_CONSTRAINTS),
            fixedTerms = payload.fixedTerms.mapNotNull { term ->
                val normalizedTerm = term.term.normalizeSingleValue()
                val normalizedDefinition = term.definition.normalizeSingleValue()

                if (normalizedTerm == null || normalizedDefinition == null) {
                    null
                } else {
                    FixedTerm(
                        term = normalizedTerm,
                        definition = normalizedDefinition,
                    )
                }
            }.distinctBy { term -> term.term.lowercase() }
                .take(MAX_FIXED_TERMS),
            knownFacts = payload.knownFacts.normalizeValues(MAX_KNOWN_FACTS),
            openQuestions = payload.openQuestions.normalizeValues(MAX_OPEN_QUESTIONS),
            lastUserIntent = payload.lastUserIntent.normalizeSingleValue(),
        )

        return taskState.takeIf { state -> state.hasMeaningfulContent() }
    }

    internal fun buildMessages(
        previousTaskState: TaskState,
        recentHistory: List<ChatMessageRecord>,
        userMessage: String,
    ): List<ChatMessage> =
        listOf(
            ChatMessage(
                role = SYSTEM_ROLE,
                content = SYSTEM_PROMPT,
            ),
            ChatMessage(
                role = USER_ROLE,
                content = buildUserPrompt(
                    previousTaskState = previousTaskState,
                    recentHistory = recentHistory,
                    userMessage = userMessage,
                ),
            ),
        )

    private fun buildUserPrompt(
        previousTaskState: TaskState,
        recentHistory: List<ChatMessageRecord>,
        userMessage: String,
    ): String {
        val historyBlock = if (recentHistory.isEmpty()) {
            "<empty>"
        } else {
            recentHistory.joinToString(separator = "\n") { message ->
                "${message.role.toPromptRole()}: ${message.text}"
            }
        }

        return buildString {
            appendLine("Previous TaskState:")
            appendLine(previousTaskState.toPromptBlock())
            appendLine()
            appendLine("Recent history:")
            appendLine(historyBlock)
            appendLine()
            appendLine("New user message:")
            appendLine(userMessage)
        }.trimEnd()
    }

    private fun extractJsonPayload(rawCompletion: String): String {
        val trimmed = rawCompletion.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }

        val lines = trimmed.lines()
        val startIndex = lines.indexOfFirst { it.trim().startsWith("```") }
        val endIndex = lines.indexOfLast { it.trim() == "```" }
        if (startIndex >= 0 && endIndex > startIndex) {
            return lines.subList(startIndex + 1, endIndex).joinToString(separator = "\n").trim()
        }

        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun TaskState.toPromptBlock(): String = buildString {
        appendLine("goal = ${goal ?: "<none>"}")
        appendLine("constraints = ${constraints.ifEmpty { listOf("<none>") }.joinToString()}")
        appendLine(
            "fixedTerms = ${
                fixedTerms.ifEmpty { listOf(FixedTerm("<none>", "<none>")) }
                    .joinToString { term -> "${term.term}: ${term.definition}" }
            }",
        )
        appendLine("knownFacts = ${knownFacts.ifEmpty { listOf("<none>") }.joinToString()}")
        appendLine("openQuestions = ${openQuestions.ifEmpty { listOf("<none>") }.joinToString()}")
        append("lastUserIntent = ${lastUserIntent ?: "<none>"}")
    }

    private fun TaskState.hasMeaningfulContent(): Boolean =
        goal != null ||
            constraints.isNotEmpty() ||
            fixedTerms.isNotEmpty() ||
            knownFacts.isNotEmpty() ||
            openQuestions.isNotEmpty() ||
            lastUserIntent != null

    private fun List<String>.normalizeValues(limit: Int): List<String> =
        mapNotNull { value -> value.normalizeSingleValue() }
            .distinct()
            .take(limit)

    private fun String?.normalizeSingleValue(): String? =
        this?.trim()
            ?.takeIf(String::isNotBlank)

    private fun ChatRole.toPromptRole(): String =
        when (this) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
        }

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private const val MAX_CONSTRAINTS = 8
        private const val MAX_FIXED_TERMS = 8
        private const val MAX_KNOWN_FACTS = 8
        private const val MAX_OPEN_QUESTIONS = 8
        private val SYSTEM_PROMPT = """
            Ты обновляешь компактную память задачи для chat-сессии.
            Верни ровно один JSON-объект и ничего больше.

            Обязательная схема:
            {
              "goal": "string|null",
              "constraints": ["string"],
              "fixedTerms": [
                { "term": "string", "definition": "string" }
              ],
              "knownFacts": ["string"],
              "openQuestions": ["string"],
              "lastUserIntent": "string|null"
            }

            Правила:
            - Верни полный обновлённый snapshot TaskState, а не только diff.
            - Сохраняй актуальные элементы предыдущего состояния, если пользователь их не отменил.
            - Не выдумывай факты, которых не было в истории.
            - Удаляй явно устаревшие ограничения, если пользователь их заменил.
            - Делай поля короткими и полезными для следующего шага retrieval и answer generation.
            - Не добавляй markdown, комментарии, code fences и лишние поля.
            - Если данных для какого-то поля нет, оставь его пустым или null.
        """.trimIndent()
    }
}
