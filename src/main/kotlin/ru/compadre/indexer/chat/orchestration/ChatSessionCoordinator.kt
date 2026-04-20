package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.memory.TaskStateUpdateService
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.model.ChatSession
import ru.compadre.indexer.chat.orchestration.model.ChatTurnResult
import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.chat.retrieval.RetrievalQueryBuilder
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.storage.ChatSessionStore
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.model.ChunkingStrategy
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
        val userTimestamp = nowProvider()
        val userMessageRecord = ChatMessageRecord(
            turnId = existingSession.messages.nextTurnId(),
            role = ChatRole.USER,
            text = userMessage,
            timestamp = userTimestamp,
        )
        val recentHistory = existingSession.messages.takeLast(recentHistorySize)
        val updatedTaskState = taskStateUpdateService.update(
            previousTaskState = existingSession.taskState,
            recentHistory = recentHistory,
            userMessage = userMessage,
            config = config.llm,
        )
        val sessionAfterUserTurn = existingSession.copy(
            messages = existingSession.messages + userMessageRecord,
            taskState = updatedTaskState,
            updatedAt = userTimestamp,
        )
        val retrievalQuery = retrievalQueryBuilder.build(
            userMessage = userMessage,
            taskState = updatedTaskState,
        )

        if (retrievalQuery.action == RetrievalAction.SKIPPED) {
            val storedSession = chatSessionStore.save(sessionAfterUserTurn)
            return ChatTurnResult(
                session = storedSession,
                userMessageRecord = userMessageRecord,
                retrievalQuery = retrievalQuery,
            )
        }

        val finalTopK = topK ?: config.search.finalTopK
        val initialTopK = maxOf(config.search.initialTopK, finalTopK)
        val ragAnswer = groundedChatAnswerService.answer(
            GroundedChatAnswerRequest(
                requestId = requestIdProvider(),
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
                updatedAt = assistantTimestamp,
            ),
        )

        return ChatTurnResult(
            session = finalSession,
            userMessageRecord = userMessageRecord,
            assistantMessageRecord = assistantMessageRecord,
            retrievalQuery = retrievalQuery,
            ragAnswer = ragAnswer,
        )
    }

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
