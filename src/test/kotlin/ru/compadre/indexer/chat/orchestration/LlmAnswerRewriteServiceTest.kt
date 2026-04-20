package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.ChatCompletionClient
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagQuote
import ru.compadre.indexer.qa.model.RagSource
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmAnswerRewriteServiceTest {
    @Test
    fun `parse completion reads json answer`() {
        val service = LlmAnswerRewriteService()

        val answer = service.parseCompletion(
            """
            {
              "answer": "Сжатый ответ"
            }
            """.trimIndent(),
        )

        assertEquals("Сжатый ответ", answer)
    }

    @Test
    fun `build messages includes original grounded answer and user instruction`() {
        val service = LlmAnswerRewriteService(
            llmClient = FakeChatCompletionClient("""{"answer":"ok"}"""),
        )

        val messages = service.buildMessages(
            AnswerRewriteRequest(
                requestId = "request-1",
                userMessage = "Коротко",
                recentHistory = emptyList(),
                taskState = TaskState(goal = "Обсуждать текст «Реформа»"),
                lastGroundedAnswer = RagAnswer(
                    answer = "Богиня Реформа родилась на берегах Ганга.",
                    sources = listOf(
                        RagSource(
                            source = "reforma.txt",
                            section = "reforma",
                            chunkId = "reforma#2",
                        ),
                    ),
                    quotes = listOf(
                        RagQuote(
                            chunkId = "reforma#2",
                            quote = "Она родилась на священных берегах многоводного Ганга.",
                        ),
                    ),
                    retrievalResult = emptyRetrievalResult(),
                ),
                config = testConfig(),
            ),
        )

        assertEquals("system", messages.first().role)
        assertTrue(messages.last().content.contains("Богиня Реформа родилась на берегах Ганга."))
        assertTrue(messages.last().content.contains("Коротко"))
        assertTrue(messages.last().content.contains("reforma.txt"))
        assertTrue(messages.last().content.contains("Она родилась на священных берегах многоводного Ганга."))
    }

    private class FakeChatCompletionClient(
        private val completion: String,
    ) : ChatCompletionClient {
        override fun complete(
            config: LlmSection,
            messages: List<ChatMessage>,
        ): String = completion
    }

    private fun testConfig(): LlmSection =
        LlmSection(
            agentId = "agent-id",
            userToken = "user-token",
            model = "test-model",
            temperature = 0.0,
            maxTokens = 200,
        )

    private fun emptyRetrievalResult(): RetrievalPipelineResult =
        RetrievalPipelineResult(
            mode = PostRetrievalMode.NONE,
            initialTopK = 0,
            finalTopK = 0,
            candidates = emptyList(),
        )
}
