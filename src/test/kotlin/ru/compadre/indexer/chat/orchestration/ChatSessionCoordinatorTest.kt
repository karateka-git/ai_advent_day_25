package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.memory.TaskStateUpdateService
import ru.compadre.indexer.chat.model.FixedTerm
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
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalPipelineResult
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
        assertEquals(RetrievalSkipReason.SHORT_SERVICE_TURN, result.retrievalQuery.skipReason)
        assertNull(result.assistantMessageRecord)
        assertNull(result.ragAnswer)
        assertEquals(1, result.session.messages.size)
        assertEquals("окей", result.session.messages.single().text)
        assertEquals("Продолжить диалог", result.session.taskState.lastUserIntent)
        assertNull(answerService.lastRequest)
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

    private fun <T> runSuspend(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
