package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.memory.model.ChatTurnType
import ru.compadre.indexer.chat.memory.TaskStateUpdateService
import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.ChatSession
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.retrieval.RetrievalQueryBuilder
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason
import ru.compadre.indexer.chat.storage.InMemoryChatSessionStore
import ru.compadre.indexer.config.AnswerGuardSection
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.config.AppSection
import ru.compadre.indexer.config.ChunkingSection
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.config.OllamaSection
import ru.compadre.indexer.config.SearchHeuristicSection
import ru.compadre.indexer.config.SearchModelRerankSection
import ru.compadre.indexer.config.SearchSection
import ru.compadre.indexer.llm.ChatCompletionClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagSource
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.trace.TraceRecord
import ru.compadre.indexer.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class ChatSessionCoordinatorTest {
    @Test
    fun `handle user turn updates session and appends grounded answer`() {
        val answerService = FakeGroundedChatAnswerService(
            ragAnswer = RagAnswer(
                answer = "Историю лучше хранить в рамках сессии.",
                retrievalResult = emptyRetrievalResult(),
            ),
        )
        val coordinator = ChatSessionCoordinator(
            chatSessionStore = InMemoryChatSessionStore(nowProvider = { Instant.parse("2026-04-20T13:00:00Z") }),
            taskStateUpdateService = TaskStateUpdateService(
                llmClient = FakeChatCompletionClient(
                    """
                    {
                      "turnType": "knowledge_question",
                      "goal": "Сделать mini-chat с RAG",
                      "constraints": ["CLI-only"],
                      "fixedTerms": [
                        { "term": "task state", "definition": "рабочая память задачи" }
                      ],
                      "knownFacts": [],
                      "openQuestions": ["Где хранить историю?"],
                      "lastUserIntent": "Определить хранение истории"
                    }
                    """.trimIndent(),
                ),
            ),
            retrievalQueryBuilder = RetrievalQueryBuilder(),
            groundedChatAnswerService = answerService,
            answerRewriteService = FakeAnswerRewriteService(),
            traceSink = RecordingTraceSink(),
            nowProvider = { Instant.parse("2026-04-20T13:00:00Z") },
            sessionIdProvider = { "session-1" },
            requestIdProvider = { "request-1" },
        )
        val session = coordinator.startSession()

        val result = runSuspend {
            coordinator.handleUserTurn(
                sessionId = session.sessionId,
                userMessage = "Как хранить историю диалога?",
                config = testConfig(),
                strategy = ChunkingStrategy.STRUCTURED,
            )
        }

        assertEquals(RetrievalAction.PERFORMED, result.retrievalQuery.action)
        assertEquals(ChatTurnType.KNOWLEDGE_QUESTION, result.turnType)
        assertNotNull(result.assistantMessageRecord)
        assertNotNull(result.ragAnswer)
        assertEquals(2, result.session.messages.size)
        assertEquals("Как хранить историю диалога?", result.userMessageRecord.text)
        assertEquals("Историю лучше хранить в рамках сессии.", result.assistantMessageRecord.text)
        assertEquals("Сделать mini-chat с RAG", result.session.taskState.goal)
        assertEquals("request-1", answerService.lastRequest?.requestId)
        assertTrue(answerService.lastRequest?.retrievalQuery?.contains("CLI-only") == true)
        assertTrue(answerService.lastRequest?.retrievalQuery?.contains("Как хранить историю диалога?") == true)
    }

    @Test
    fun `handle user turn skips retrieval for short service turn`() {
        val answerService = FakeGroundedChatAnswerService(
            ragAnswer = RagAnswer(
                answer = "unused",
                retrievalResult = emptyRetrievalResult(),
            ),
        )
        val coordinator = ChatSessionCoordinator(
            chatSessionStore = InMemoryChatSessionStore(nowProvider = { Instant.parse("2026-04-20T13:10:00Z") }),
            taskStateUpdateService = TaskStateUpdateService(
                llmClient = FakeChatCompletionClient(
                    """
                    {
                      "turnType": "service_turn",
                      "goal": "Сделать mini-chat с RAG",
                      "constraints": [],
                      "fixedTerms": [],
                      "knownFacts": [],
                      "openQuestions": [],
                      "lastUserIntent": "Продолжить диалог"
                    }
                    """.trimIndent(),
                ),
            ),
            retrievalQueryBuilder = RetrievalQueryBuilder(),
            groundedChatAnswerService = answerService,
            answerRewriteService = FakeAnswerRewriteService(),
            traceSink = RecordingTraceSink(),
            nowProvider = { Instant.parse("2026-04-20T13:10:00Z") },
            sessionIdProvider = { "session-2" },
        )
        val session = coordinator.startSession()

        val result = runSuspend {
            coordinator.handleUserTurn(
                sessionId = session.sessionId,
                userMessage = "окей",
                config = testConfig(),
                strategy = ChunkingStrategy.STRUCTURED,
            )
        }

        assertEquals(RetrievalAction.SKIPPED, result.retrievalQuery.action)
        assertEquals(ChatTurnType.SERVICE_TURN, result.turnType)
        assertEquals(RetrievalSkipReason.SHORT_SERVICE_TURN, result.retrievalQuery.skipReason)
        assertNull(result.assistantMessageRecord)
        assertNull(result.ragAnswer)
        assertEquals(1, result.session.messages.size)
        assertEquals("окей", result.session.messages.single().text)
        assertEquals("Продолжить диалог", result.session.taskState.lastUserIntent)
        assertNull(answerService.lastRequest)
    }

    @Test
    fun `handle user turn emits chat trace records`() {
        val traceSink = RecordingTraceSink()
        val coordinator = ChatSessionCoordinator(
            chatSessionStore = InMemoryChatSessionStore(nowProvider = { Instant.parse("2026-04-20T13:20:00Z") }),
            taskStateUpdateService = TaskStateUpdateService(
                llmClient = FakeChatCompletionClient(
                    """
                    {
                      "turnType": "knowledge_question",
                      "goal": "Сделать mini-chat с RAG",
                      "constraints": ["CLI-only"],
                      "fixedTerms": [],
                      "knownFacts": [],
                      "openQuestions": [],
                      "lastUserIntent": "Проверить trace"
                    }
                    """.trimIndent(),
                ),
                traceSink = traceSink,
            ),
            retrievalQueryBuilder = RetrievalQueryBuilder(),
            groundedChatAnswerService = FakeGroundedChatAnswerService(
                ragAnswer = RagAnswer(
                    answer = "Тестовый ответ",
                    retrievalResult = emptyRetrievalResult(),
                ),
            ),
            answerRewriteService = FakeAnswerRewriteService(),
            traceSink = traceSink,
            nowProvider = { Instant.parse("2026-04-20T13:20:00Z") },
            sessionIdProvider = { "session-trace" },
            requestIdProvider = { "request-trace" },
        )
        val session = coordinator.startSession()

        runSuspend {
            coordinator.handleUserTurn(
                sessionId = session.sessionId,
                userMessage = "Как хранить историю?",
                config = testConfig(),
                strategy = ChunkingStrategy.STRUCTURED,
            )
        }

        assertEquals(
            listOf(
                "task_state_updated",
                "retrieval_query_built",
                "chat_turn_completed",
            ),
            traceSink.records.map(TraceRecord::kind),
        )
    }

    @Test
    fun `handle user turn skips retrieval when unified update marks task state update`() {
        val answerService = FakeGroundedChatAnswerService(
            ragAnswer = RagAnswer(
                answer = "unused",
                retrievalResult = emptyRetrievalResult(),
            ),
        )
        val coordinator = ChatSessionCoordinator(
            chatSessionStore = InMemoryChatSessionStore(nowProvider = { Instant.parse("2026-04-20T13:30:00Z") }),
            taskStateUpdateService = TaskStateUpdateService(
                llmClient = FakeChatCompletionClient(
                    """
                    {
                      "turnType": "task_state_update",
                      "goal": "Обсуждать текст «Реформа»",
                      "constraints": ["Обсуждать только текст «Реформа»"],
                      "fixedTerms": [],
                      "knownFacts": [],
                      "openQuestions": [],
                      "lastUserIntent": "Зафиксировать рамку диалога"
                    }
                    """.trimIndent(),
                ),
            ),
            retrievalQueryBuilder = RetrievalQueryBuilder(),
            groundedChatAnswerService = answerService,
            answerRewriteService = FakeAnswerRewriteService(),
            traceSink = RecordingTraceSink(),
            nowProvider = { Instant.parse("2026-04-20T13:30:00Z") },
            sessionIdProvider = { "session-routing" },
        )
        val session = coordinator.startSession()

        val result = runSuspend {
            coordinator.handleUserTurn(
                sessionId = session.sessionId,
                userMessage = "Теперь держимся только текста Реформа без отвлечений",
                config = testConfig(),
                strategy = ChunkingStrategy.STRUCTURED,
            )
        }

        assertEquals(ChatTurnType.TASK_STATE_UPDATE, result.turnType)
        assertEquals(RetrievalAction.SKIPPED, result.retrievalQuery.action)
        assertEquals(RetrievalSkipReason.TASK_STATE_UPDATE_ONLY, result.retrievalQuery.skipReason)
        assertNull(result.ragAnswer)
        assertNull(answerService.lastRequest)
    }

    @Test
    fun `handle user turn rewrites last grounded answer without retrieval`() {
        val store = InMemoryChatSessionStore(nowProvider = { Instant.parse("2026-04-20T13:40:00Z") })
        val existingSession = store.create("session-rewrite")
        store.save(
            existingSession.copy(
                taskState = TaskState(goal = "Обсуждать текст «Реформа»"),
                lastGroundedAnswer = RagAnswer(
                    answer = "Богиня Реформа родилась на берегах Ганга и показана как прекрасная и свободная.",
                    sources = listOf(
                        RagSource(
                            source = "reforma.txt",
                            section = "reforma",
                            chunkId = "reforma#2",
                        ),
                    ),
                    retrievalResult = emptyRetrievalResult(),
                ),
            ),
        )
        val answerService = FakeGroundedChatAnswerService(
            ragAnswer = RagAnswer(
                answer = "unused",
                retrievalResult = emptyRetrievalResult(),
            ),
        )
        val rewriteService = FakeAnswerRewriteService(
            rewrittenAnswer = "Богиня Реформа родилась на берегах Ганга и описана как прекрасная и свободная.",
        )
        val coordinator = ChatSessionCoordinator(
            chatSessionStore = store,
            taskStateUpdateService = TaskStateUpdateService(
                llmClient = FakeChatCompletionClient(
                    """
                    {
                      "turnType": "answer_rewrite",
                      "goal": "Обсуждать текст «Реформа»",
                      "constraints": ["Обсуждать только текст «Реформа»"],
                      "fixedTerms": [],
                      "knownFacts": [],
                      "openQuestions": [],
                      "lastUserIntent": "Сделать ответ короче"
                    }
                    """.trimIndent(),
                ),
            ),
            retrievalQueryBuilder = RetrievalQueryBuilder(),
            groundedChatAnswerService = answerService,
            answerRewriteService = rewriteService,
            traceSink = RecordingTraceSink(),
            nowProvider = { Instant.parse("2026-04-20T13:40:00Z") },
            requestIdProvider = { "request-rewrite" },
        )

        val result = runSuspend {
            coordinator.handleUserTurn(
                sessionId = "session-rewrite",
                userMessage = "Коротко, в 2-3 предложениях.",
                config = testConfig(),
                strategy = ChunkingStrategy.STRUCTURED,
            )
        }

        assertEquals(ChatTurnType.ANSWER_REWRITE, result.turnType)
        assertEquals(RetrievalAction.SKIPPED, result.retrievalQuery.action)
        assertEquals(RetrievalSkipReason.ANSWER_REWRITE_REUSE, result.retrievalQuery.skipReason)
        assertEquals(
            "Богиня Реформа родилась на берегах Ганга и описана как прекрасная и свободная.",
            result.ragAnswer?.answer,
        )
        assertEquals("reforma.txt", result.ragAnswer?.sources?.single()?.source)
        assertEquals("Коротко, в 2-3 предложениях.", rewriteService.lastRequest?.userMessage)
        assertNull(answerService.lastRequest)
        assertEquals(
            "Богиня Реформа родилась на берегах Ганга и описана как прекрасная и свободная.",
            result.session.lastGroundedAnswer?.answer,
        )
    }

    private fun testConfig(): AppConfig =
        AppConfig(
            app = AppSection(
                inputDir = "./docs",
                outputDir = "./data",
            ),
            ollama = OllamaSection(
                baseUrl = "http://localhost:11434",
                embeddingModel = "nomic-embed-text",
            ),
            llm = LlmSection(
                agentId = "agent-id",
                userToken = "user-token",
                model = "test-model",
                temperature = 0.0,
                maxTokens = 200,
            ),
            chunking = ChunkingSection(
                fixedSize = 1200,
                overlap = 200,
            ),
            search = SearchSection(
                topK = 5,
                initialTopK = 8,
                finalTopK = 3,
                minSimilarity = 0.15,
                postProcessingMode = "model-rerank",
                heuristic = SearchHeuristicSection(
                    minKeywordOverlap = 1,
                    cosineWeight = 0.7,
                    keywordOverlapWeight = 0.3,
                    exactMatchBonus = 0.15,
                    titleMatchBonus = 0.1,
                    sectionMatchBonus = 0.05,
                    duplicatePenalty = 0.2,
                ),
                modelRerank = SearchModelRerankSection(
                    enabled = true,
                    maxCandidates = 8,
                ),
            ),
            answerGuard = AnswerGuardSection(
                enabled = true,
                minTopScore = 0.2,
                minSelectedChunks = 1,
            ),
        )

    private fun emptyRetrievalResult(): RetrievalPipelineResult =
        RetrievalPipelineResult(
            mode = PostRetrievalMode.NONE,
            initialTopK = 0,
            finalTopK = 0,
            candidates = emptyList(),
        )

    private class FakeChatCompletionClient(
        private val completion: String,
    ) : ChatCompletionClient {
        override fun complete(
            config: LlmSection,
            messages: List<ChatMessage>,
        ): String = completion
    }

    private class FakeGroundedChatAnswerService(
        private val ragAnswer: RagAnswer,
    ) : GroundedChatAnswerService {
        var lastRequest: ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest? = null

        override suspend fun answer(request: ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest): RagAnswer {
            lastRequest = request
            return ragAnswer
        }
    }

    private class FakeAnswerRewriteService(
        private val rewrittenAnswer: String? = null,
    ) : AnswerRewriteService {
        var lastRequest: ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest? = null

        override suspend fun rewrite(request: ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest): String? {
            lastRequest = request
            return rewrittenAnswer
        }
    }

    private class RecordingTraceSink : TraceSink {
        val records = mutableListOf<TraceRecord>()

        override fun emit(record: TraceRecord) {
            records += record
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
