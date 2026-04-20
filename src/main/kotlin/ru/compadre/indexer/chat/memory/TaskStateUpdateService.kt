package ru.compadre.indexer.chat.memory

import kotlinx.serialization.json.Json
import ru.compadre.indexer.chat.memory.model.ChatTurnType
import ru.compadre.indexer.chat.memory.model.TaskStateUpdateCompletion
import ru.compadre.indexer.chat.memory.model.TaskStateUpdateResult
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
    ): TaskState =
        updateWithTurnType(
            requestId = requestId,
            previousTaskState = previousTaskState,
            recentHistory = recentHistory,
            userMessage = userMessage,
            config = config,
        ).taskState

    /**
     * Выполняет unified memory update: одновременно определяет семантический тип
     * текущего пользовательского хода и возвращает полный обновлённый snapshot `TaskState`.
     *
     * Это основной structured step для chat-orchestration:
     * - классифицирует ход как вопрос, смену темы, update памяти, rewrite или service-turn;
     * - обновляет память задачи с учётом истории;
     * - даёт coordinator-слою единый результат для дальнейшего routing.
     *
     * При невалидном или пустом ответе модели сервис делает безопасный fallback:
     * сохраняет предыдущее состояние памяти и определяет тип хода по минимальной
     * защитной эвристике.
     *
     * @param previousTaskState предыдущее состояние памяти задачи.
     * @param recentHistory последние релевантные сообщения диалога.
     * @param userMessage новое сообщение пользователя.
     * @param config настройки LLM.
     * @return unified результат с `turnType` и новым `TaskState`.
     */
    fun updateWithTurnType(
        requestId: String,
        previousTaskState: TaskState,
        recentHistory: List<ChatMessageRecord>,
        userMessage: String,
        config: LlmSection,
    ): TaskStateUpdateResult {
        val messages = buildMessages(
            previousTaskState = previousTaskState,
            recentHistory = recentHistory,
            userMessage = userMessage,
        )
        val completion = llmClient.complete(config, messages)
        val parsedResult = parseModelCompletion(completion, userMessage)
        val stabilizedTaskState = parsedResult?.taskState
            ?.stabilize(
                turnType = parsedResult.turnType,
                previousTaskState = previousTaskState,
                userMessage = userMessage,
            )
        val updateResult = if (parsedResult != null && stabilizedTaskState != null) {
            parsedResult.copy(taskState = stabilizedTaskState)
        } else {
            TaskStateUpdateResult(
                turnType = inferTurnType(userMessage),
                taskState = previousTaskState,
            )
        }
        val appliedFallback = updateResult.taskState == previousTaskState && parsedResult == null

        traceSink.emitRecord(
            requestId = requestId,
            kind = "task_state_updated",
            stage = "chat.memory_update",
            payload = tracePayload {
                putString("userMessage", userMessage)
                putString("turnType", updateResult.turnType.name)
                putBoolean("appliedFallback", appliedFallback)
                put("recentHistory", chatHistoryTracePayload(recentHistory))
                put("previousTaskState", taskStateTracePayload(previousTaskState))
                put("newTaskState", taskStateTracePayload(updateResult.taskState))
                putString("fallbackReason", if (appliedFallback) "invalid_or_empty_memory_update" else null)
            },
        )

        return updateResult
    }

    internal fun parseModelCompletion(
        rawCompletion: String,
        userMessage: String,
    ): TaskStateUpdateResult? {
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

        val normalizedTaskState = taskState.takeIf { state -> state.hasMeaningfulContent() } ?: TaskState()
        return TaskStateUpdateResult(
            turnType = payload.turnType ?: inferTurnType(userMessage),
            taskState = normalizedTaskState,
        )
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

    private fun TaskState.stabilize(
        turnType: ChatTurnType,
        previousTaskState: TaskState,
        userMessage: String,
    ): TaskState {
        if (turnType == ChatTurnType.TOPIC_SWITCH) {
            return stabilizeTopicSwitch(
                previousTaskState = previousTaskState,
                userMessage = userMessage,
            )
        }

        val inferredGoal = inferGoalFromUserMessage(userMessage)
        return copy(
            goal = inferredGoal ?: goal ?: previousTaskState.goal,
        )
    }

    private fun TaskState.stabilizeTopicSwitch(
        previousTaskState: TaskState,
        userMessage: String,
    ): TaskState {
        val newTopic = inferDocumentTitle(userMessage)
            ?: activeDocumentTitle()
            ?: previousTaskState.activeDocumentTitle()
        val previousTopic = previousTaskState.activeDocumentTitle()
        val preservedGlobalConstraints = previousTaskState.constraints
            .filter(::isGlobalConstraint)
        val currentGlobalConstraints = constraints.filter(::isGlobalConstraint)
        val topicConstraints = constraints.filter { constraint ->
            newTopic != null && constraint.referencesTopic(newTopic)
        }
        val normalizedConstraints = buildList {
            addAll(preservedGlobalConstraints)
            addAll(currentGlobalConstraints)
            addAll(topicConstraints)
            if (newTopic != null && none { it.referencesTopic(newTopic) }) {
                add("Обсуждать только текст «$newTopic»")
            }
        }.distinct()
            .take(MAX_CONSTRAINTS)
        val normalizedFixedTerms = fixedTerms.filter { term ->
            term.referencesTopic(newTopic)
        }.take(MAX_FIXED_TERMS)
        val normalizedGoal = newTopic?.let { "Обсуждать текст «$it»" }
            ?: goal
            ?: previousTaskState.goal

        return copy(
            goal = normalizedGoal,
            constraints = normalizedConstraints,
            fixedTerms = normalizedFixedTerms,
            knownFacts = knownFacts.filter { fact -> fact.referencesTopic(newTopic) }.take(MAX_KNOWN_FACTS),
            openQuestions = openQuestions.filter { question -> question.referencesTopic(newTopic) }.take(MAX_OPEN_QUESTIONS),
            lastUserIntent = lastUserIntent,
        )
    }

    private fun inferGoalFromUserMessage(userMessage: String): String? =
        GOAL_TEXT_REGEX.find(userMessage)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)?.let { title ->
            "Обсуждать текст «$title»"
        }

    private fun TaskState.activeDocumentTitle(): String? =
        constraints.firstNotNullOfOrNull(::inferDocumentTitle)
            ?: goal?.let(::inferDocumentTitle)
            ?: knownFacts.firstNotNullOfOrNull(::inferDocumentTitle)

    private fun inferDocumentTitle(text: String): String? =
        DOCUMENT_TITLE_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)

    private fun isGlobalConstraint(constraint: String): Boolean {
        val normalized = constraint.lowercase()
        return DOCUMENT_TITLE_REGEX.find(normalized) == null &&
            !normalized.contains("этот текст") &&
            !normalized.contains("по нему") &&
            !normalized.contains("текущий текст")
    }

    private fun String.referencesTopic(topic: String?): Boolean =
        topic != null && contains(topic, ignoreCase = true)

    private fun FixedTerm.referencesTopic(topic: String?): Boolean =
        term.referencesTopic(topic) || definition.referencesTopic(topic)

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
        private const val SHORT_SERVICE_TURN_MAX_LENGTH = 16
        private val SHORT_SERVICE_TURNS = setOf(
            "да",
            "ага",
            "ок",
            "окей",
            "угу",
            "хорошо",
            "продолжай",
            "давай дальше",
        )
        private val GOAL_TEXT_REGEX =
            Regex("""(?:обсуждать|обсуждаем)\s+(?:не\s+)?(?:только\s+)?текст\s+[«"]([^»"]+)[»"]""", RegexOption.IGNORE_CASE)
        private val DOCUMENT_TITLE_REGEX = Regex("""текст\s+[«"]([^»"]+)[»"]""", RegexOption.IGNORE_CASE)
        private val SYSTEM_PROMPT = """
            Ты обновляешь компактную память задачи для chat-сессии.
            Верни ровно один JSON-объект и ничего больше.

            Обязательная схема:
            {
              "turnType": "knowledge_question|answer_rewrite|task_state_update|topic_switch|service_turn",
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
            - Обязательно определи `turnType` для нового пользовательского хода.
            - `knowledge_question`: пользователь просит новые сведения из retrieval-контекста.
            - `answer_rewrite`: пользователь просит сократить, переформулировать или иначе переподать уже полученный ответ.
            - `task_state_update`: пользователь задаёт ограничения, правила, термины или рамку диалога без нового вопроса по знаниям.
            - `topic_switch`: пользователь меняет документ или основную тему обсуждения.
            - `service_turn`: короткая служебная реплика без нового смыслового запроса.
            - Верни полный обновлённый snapshot TaskState, а не только diff.
            - Сохраняй актуальные элементы предыдущего состояния, если пользователь их не отменил.
            - Не выдумывай факты, которых не было в истории.
            - Удаляй явно устаревшие ограничения, если пользователь их заменил.
            - Делай поля короткими и полезными для следующего шага retrieval и answer generation.
            - Для `lastUserIntent` возвращай retrieval-ready формулировку в одном коротком предложении.
            - Строй `lastUserIntent` по всему доступному контексту: новому сообщению, previous TaskState, recent history, knownFacts, openQuestions и ограничениям.
            - В `lastUserIntent` обязательно сохраняй ключевые сущности, текущий объект обсуждения и предмет запроса.
            - Для зависимых follow-up вопросов раскрывай местоимения, эллипсис и контекст, используя уже известные факты из истории, если они помогают точнее сформулировать поисковое намерение.
            - `lastUserIntent` должен быть конкретным и пригодным для retrieval: без расплывчатых формулировок вроде "уточнить детали" или "узнать больше" без объекта запроса.
            - Не подменяй намерение готовым ответом и не добавляй факты, которых нет в переданном контексте.
            - Не добавляй markdown, комментарии, code fences и лишние поля.
            - Если данных для какого-то поля нет, оставь его пустым или null.
        """.trimIndent()
    }

    private fun inferTurnType(userMessage: String): ChatTurnType {
        val normalized = userMessage.trim().lowercase()
        if (normalized.isBlank()) {
            return ChatTurnType.SERVICE_TURN
        }

        if (normalized.length <= SHORT_SERVICE_TURN_MAX_LENGTH && SHORT_SERVICE_TURNS.contains(normalized)) {
            return ChatTurnType.SERVICE_TURN
        }

        if ('?' in normalized) {
            return ChatTurnType.KNOWLEDGE_QUESTION
        }

        return ChatTurnType.TASK_STATE_UPDATE
    }
}
