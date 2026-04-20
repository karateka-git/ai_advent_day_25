package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.memory.model.ChatTurnType
import ru.compadre.indexer.chat.memory.TaskStateUpdateService
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.model.ChatSession
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.orchestration.model.ChatTurnResult
import ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest
import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.chat.retrieval.RetrievalQueryBuilder
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason
import ru.compadre.indexer.chat.storage.ChatSessionStore
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.trace.NoOpTraceSink
import ru.compadre.indexer.trace.TraceSink
import ru.compadre.indexer.trace.emitRecord
import ru.compadre.indexer.trace.putBoolean
import ru.compadre.indexer.trace.putInt
import ru.compadre.indexer.trace.putString
import ru.compadre.indexer.trace.retrievalActionTracePayload
import ru.compadre.indexer.trace.taskStateTracePayload
import ru.compadre.indexer.trace.tracePayload
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Координирует один ход chat-сессии поверх истории сообщений, памяти задачи и ответа по найденному контексту.
 *
 * @param chatSessionStore хранилище текущих chat-сессий и их актуального состояния.
 * @param taskStateUpdateService сервис обновления компактной памяти задачи по истории и новому пользовательскому ходу.
 * @param retrievalQueryBuilder builder, который превращает пользовательское сообщение и `TaskState`
 * в search-friendly запрос для retrieval либо явно сообщает, что retrieval нужно пропустить.
 * @param groundedChatAnswerService сервис, который строит ответ с опорой на найденный retrieval-контекст.
 * @param nowProvider поставщик текущего времени для timestamp-полей и детерминированных тестов.
 * @param sessionIdProvider поставщик идентификаторов chat-сессий при создании новой сессии.
 * @param requestIdProvider поставщик request id для вызова grounded answer и trace-сценариев.
 * @param recentHistorySize количество последних сообщений, которое передаётся в update памяти
 * и в chat-aware answer flow как recent history.
 */
class ChatSessionCoordinator(
    private val chatSessionStore: ChatSessionStore,
    private val taskStateUpdateService: TaskStateUpdateService,
    private val retrievalQueryBuilder: RetrievalQueryBuilder,
    private val groundedChatAnswerService: GroundedChatAnswerService,
    private val answerRewriteService: AnswerRewriteService,
    private val traceSink: TraceSink = NoOpTraceSink,
    private val nowProvider: () -> Instant = Instant::now,
    private val sessionIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val requestIdProvider: () -> String = { "chat-${UUID.randomUUID()}" },
    private val recentHistorySize: Int = DEFAULT_RECENT_HISTORY_SIZE,
) {
    /**
     * Создаёт новую пустую chat-сессию.
     *
     * @return созданная chat-сессия.
     */
    fun startSession(): ChatSession = chatSessionStore.create(sessionIdProvider())

    /**
     * Обрабатывает один пользовательский ход внутри chat-сессии.
     *
     * @param sessionId идентификатор активной сессии.
     * @param userMessage новое сообщение пользователя.
     * @param config настройки приложения.
     * @param strategy стратегия индекса для retrieval.
     * @param topK желаемый размер финального контекста.
     * @return итог обработки текущего хода.
     */
    suspend fun handleUserTurn(
        sessionId: String,
        userMessage: String,
        config: AppConfig,
        strategy: ChunkingStrategy,
        topK: Int? = null,
    ): ChatTurnResult {
        val existingSession = chatSessionStore.findById(sessionId)
            ?: chatSessionStore.create(sessionId)
        val requestId = requestIdProvider()
        val userTimestamp = nowProvider()
        val userMessageRecord = ChatMessageRecord(
            turnId = existingSession.messages.nextTurnId(),
            role = ChatRole.USER,
            text = userMessage,
            timestamp = userTimestamp,
        )
        val recentHistory = existingSession.messages.takeLast(recentHistorySize)
        val updateResult = taskStateUpdateService.updateWithTurnType(
            requestId = requestId,
            previousTaskState = existingSession.taskState,
            recentHistory = recentHistory,
            userMessage = userMessage,
            config = config.llm,
        )
        val turnType = updateResult.turnType
        val updatedTaskState = updateResult.taskState
        val sessionAfterUserTurn = existingSession.copy(
            messages = existingSession.messages + userMessageRecord,
            taskState = updatedTaskState,
            lastGroundedAnswer = existingSession.lastGroundedAnswer,
            updatedAt = userTimestamp,
        )
        val retrievalQuery = retrievalQueryForTurnType(
            turnType = turnType,
            userMessage = userMessage,
            taskState = updatedTaskState,
            recentHistory = recentHistory,
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "retrieval_query_built",
            stage = "chat.retrieval_query",
            payload = tracePayload {
                putString("sessionId", sessionAfterUserTurn.sessionId)
                putInt("turnId", userMessageRecord.turnId)
                putString("turnType", turnType.name)
                put("retrieval", retrievalActionTracePayload(retrievalQuery.action, retrievalQuery.skipReason, retrievalQuery.query))
                put("taskState", taskStateTracePayload(updatedTaskState))
            },
        )

        if (turnType == ChatTurnType.ANSWER_REWRITE && sessionAfterUserTurn.lastGroundedAnswer != null) {
            val rewriteResult = handleAnswerRewrite(
                requestId = requestId,
                userMessageRecord = userMessageRecord,
                sessionAfterUserTurn = sessionAfterUserTurn,
                retrievalQuery = skippedRetrieval(RetrievalSkipReason.ANSWER_REWRITE_REUSE),
                userMessage = userMessage,
                config = config,
            )
            return rewriteResult
        }

        if (retrievalQuery.action == RetrievalAction.SKIPPED) {
            val storedSession = chatSessionStore.save(sessionAfterUserTurn)
            traceSink.emitRecord(
                requestId = requestId,
                kind = "retrieval_skipped",
                stage = "chat.retrieval",
                payload = tracePayload {
                    putString("sessionId", storedSession.sessionId)
                    putInt("turnId", userMessageRecord.turnId)
                    putString("turnType", turnType.name)
                    put("retrieval", retrievalActionTracePayload(retrievalQuery.action, retrievalQuery.skipReason, retrievalQuery.query))
                },
            )
            traceSink.emitRecord(
                requestId = requestId,
                kind = "chat_turn_completed",
                stage = "chat.turn_result",
                payload = tracePayload {
                    putString("sessionId", storedSession.sessionId)
                    putInt("turnId", userMessageRecord.turnId)
                    putString("turnType", turnType.name)
                    putBoolean("hasAssistantMessage", false)
                    putBoolean("hasRagAnswer", false)
                    put("taskState", taskStateTracePayload(storedSession.taskState))
                },
            )
            return ChatTurnResult(
                session = storedSession,
                turnType = turnType,
                userMessageRecord = userMessageRecord,
                retrievalQuery = retrievalQuery,
            )
        }

        val finalTopK = topK ?: config.search.finalTopK
        val initialTopK = maxOf(config.search.initialTopK, finalTopK)
        val ragAnswer = groundedChatAnswerService.answer(
            GroundedChatAnswerRequest(
                requestId = requestId,
                sessionId = sessionAfterUserTurn.sessionId,
                userMessage = userMessage,
                retrievalQuery = retrievalQuery.query.orEmpty(),
                recentHistory = sessionAfterUserTurn.messages.takeLast(recentHistorySize),
                taskState = updatedTaskState,
                databasePath = resolveDatabasePath(
                    outputDir = config.app.outputDir,
                    strategy = strategy,
                ),
                strategy = strategy,
                initialTopK = initialTopK,
                finalTopK = finalTopK,
                config = config,
            ),
        )
        val assistantTimestamp = nowProvider()
        val assistantMessageRecord = ChatMessageRecord(
            turnId = sessionAfterUserTurn.messages.nextTurnId(),
            role = ChatRole.ASSISTANT,
            text = ragAnswer.answer,
            timestamp = assistantTimestamp,
        )
        val finalSession = chatSessionStore.save(
            sessionAfterUserTurn.copy(
                messages = sessionAfterUserTurn.messages + assistantMessageRecord,
                lastGroundedAnswer = ragAnswer.takeUnless { it.isRefusal } ?: sessionAfterUserTurn.lastGroundedAnswer,
                updatedAt = assistantTimestamp,
            ),
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "chat_turn_completed",
            stage = "chat.turn_result",
            payload = tracePayload {
                putString("sessionId", finalSession.sessionId)
                putInt("turnId", userMessageRecord.turnId)
                putString("turnType", turnType.name)
                putBoolean("hasAssistantMessage", true)
                putBoolean("hasRagAnswer", true)
                putString("assistantAnswer", assistantMessageRecord.text)
                put("taskState", taskStateTracePayload(finalSession.taskState))
            },
        )

        return ChatTurnResult(
            session = finalSession,
            turnType = turnType,
            userMessageRecord = userMessageRecord,
            assistantMessageRecord = assistantMessageRecord,
            retrievalQuery = retrievalQuery,
            ragAnswer = ragAnswer,
        )
    }

    private fun retrievalQueryForTurnType(
        turnType: ChatTurnType,
        userMessage: String,
        taskState: TaskState,
        recentHistory: List<ChatMessageRecord>,
    ): ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult =
        when (turnType) {
            ChatTurnType.SERVICE_TURN -> skippedRetrieval(RetrievalSkipReason.SHORT_SERVICE_TURN)
            ChatTurnType.TASK_STATE_UPDATE,
            ChatTurnType.TOPIC_SWITCH,
                -> skippedRetrieval(RetrievalSkipReason.TASK_STATE_UPDATE_ONLY)

            ChatTurnType.KNOWLEDGE_QUESTION,
            ChatTurnType.ANSWER_REWRITE,
                -> retrievalQueryBuilder.build(
                    userMessage = userMessage,
                    taskState = taskState,
                    recentHistory = recentHistory,
                )
        }

    private suspend fun handleAnswerRewrite(
        requestId: String,
        userMessageRecord: ChatMessageRecord,
        sessionAfterUserTurn: ChatSession,
        retrievalQuery: ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult,
        userMessage: String,
        config: AppConfig,
    ): ChatTurnResult {
        val lastGroundedAnswer = checkNotNull(sessionAfterUserTurn.lastGroundedAnswer)
        val rewrittenAnswerText = answerRewriteService.rewrite(
            AnswerRewriteRequest(
                requestId = requestId,
                userMessage = userMessage,
                recentHistory = sessionAfterUserTurn.messages.takeLast(recentHistorySize),
                taskState = sessionAfterUserTurn.taskState,
                lastGroundedAnswer = lastGroundedAnswer,
                config = config.llm,
            ),
        ) ?: lastGroundedAnswer.answer
        val rewrittenRagAnswer = lastGroundedAnswer.copy(answer = rewrittenAnswerText)
        val assistantTimestamp = nowProvider()
        val assistantMessageRecord = ChatMessageRecord(
            turnId = sessionAfterUserTurn.messages.nextTurnId(),
            role = ChatRole.ASSISTANT,
            text = rewrittenAnswerText,
            timestamp = assistantTimestamp,
        )
        val finalSession = chatSessionStore.save(
            sessionAfterUserTurn.copy(
                messages = sessionAfterUserTurn.messages + assistantMessageRecord,
                lastGroundedAnswer = rewrittenRagAnswer,
                updatedAt = assistantTimestamp,
            ),
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "answer_rewrite_completed",
            stage = "chat.answer_rewrite",
            payload = tracePayload {
                putString("sessionId", finalSession.sessionId)
                putInt("turnId", userMessageRecord.turnId)
                putString("turnType", ChatTurnType.ANSWER_REWRITE.name)
                putString("assistantAnswer", assistantMessageRecord.text)
            },
        )
        traceSink.emitRecord(
            requestId = requestId,
            kind = "chat_turn_completed",
            stage = "chat.turn_result",
            payload = tracePayload {
                putString("sessionId", finalSession.sessionId)
                putInt("turnId", userMessageRecord.turnId)
                putString("turnType", ChatTurnType.ANSWER_REWRITE.name)
                putBoolean("hasAssistantMessage", true)
                putBoolean("hasRagAnswer", true)
                putString("assistantAnswer", assistantMessageRecord.text)
                put("taskState", taskStateTracePayload(finalSession.taskState))
            },
        )

        return ChatTurnResult(
            session = finalSession,
            turnType = ChatTurnType.ANSWER_REWRITE,
            userMessageRecord = userMessageRecord,
            assistantMessageRecord = assistantMessageRecord,
            retrievalQuery = retrievalQuery,
            ragAnswer = rewrittenRagAnswer,
        )
    }

    private fun skippedRetrieval(
        reason: RetrievalSkipReason,
    ): ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult =
        ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult(
            action = RetrievalAction.SKIPPED,
            skipReason = reason,
        )

    private fun resolveDatabasePath(
        outputDir: String,
        strategy: ChunkingStrategy,
    ): Path {
        val fileName = when (strategy) {
            ChunkingStrategy.STRUCTURED -> "index-structured.db"
            ChunkingStrategy.FIXED -> "index-fixed.db"
        }

        return Path.of(outputDir).resolve(fileName)
    }

    private fun List<ChatMessageRecord>.nextTurnId(): Int = size + 1

    private companion object {
        private const val DEFAULT_RECENT_HISTORY_SIZE = 6
    }
}
