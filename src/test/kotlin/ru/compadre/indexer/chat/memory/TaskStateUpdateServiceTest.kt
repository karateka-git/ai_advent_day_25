package ru.compadre.indexer.chat.memory

import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.ChatCompletionClient
import ru.compadre.indexer.llm.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant

class TaskStateUpdateServiceTest {
    @Test
    fun `parse model completion builds normalized task state`() {
        val service = TaskStateUpdateService()

        val parsed = service.parseModelCompletion(
            """
            {
              "goal": "  Сделать mini-chat с RAG  ",
              "constraints": ["CLI-only", "CLI-only", "  Без веба  "],
              "fixedTerms": [
                { "term": "task state", "definition": "краткая память задачи" },
                { "term": "task state", "definition": "дубликат" },
                { "term": " ", "definition": "ignored" }
              ],
              "knownFacts": ["Проект вырос из day-24", "Проект вырос из day-24"],
              "openQuestions": ["Где хранить историю?"],
              "lastUserIntent": "  Уточнить модель памяти "
            }
            """.trimIndent(),
        )

        assertEquals(
            TaskState(
                goal = "Сделать mini-chat с RAG",
                constraints = listOf("CLI-only", "Без веба"),
                fixedTerms = listOf(
                    FixedTerm(
                        term = "task state",
                        definition = "краткая память задачи",
                    ),
                ),
                knownFacts = listOf("Проект вырос из day-24"),
                openQuestions = listOf("Где хранить историю?"),
                lastUserIntent = "Уточнить модель памяти",
            ),
            parsed,
        )
    }

    @Test
    fun `parse model completion supports fenced json`() {
        val service = TaskStateUpdateService()

        val parsed = service.parseModelCompletion(
            """
            ```json
            {
              "goal": null,
              "constraints": [],
              "fixedTerms": [],
              "knownFacts": [],
              "openQuestions": [],
              "lastUserIntent": "Понять как обновлять TaskState"
            }
            ```
            """.trimIndent(),
        )

        assertEquals(
            TaskState(lastUserIntent = "Понять как обновлять TaskState"),
            parsed,
        )
    }

    @Test
    fun `update falls back to previous state on invalid json`() {
        val previousTaskState = TaskState(
            goal = "Сделать mini-chat",
            constraints = listOf("CLI-only"),
            lastUserIntent = "Продолжить обсуждение архитектуры",
        )
        val service = TaskStateUpdateService(
            llmClient = FakeChatCompletionClient("not a json payload"),
        )

        val updatedState = service.update(
            previousTaskState = previousTaskState,
            recentHistory = listOf(
                ChatMessageRecord(
                    turnId = 1,
                    role = ChatRole.USER,
                    text = "Давай без веба",
                    timestamp = Instant.parse("2026-04-20T12:00:00Z"),
                ),
            ),
            userMessage = "А как хранить историю?",
            config = testConfig(),
        )

        assertEquals(previousTaskState, updatedState)
    }

    @Test
    fun `update uses parsed snapshot when llm returns valid json`() {
        val service = TaskStateUpdateService(
            llmClient = FakeChatCompletionClient(
                """
                {
                  "goal": "Сделать mini-chat с RAG",
                  "constraints": ["CLI-only", "Без веба"],
                  "fixedTerms": [
                    { "term": "task state", "definition": "рабочая память задачи" }
                  ],
                  "knownFacts": ["История пока хранится в памяти процесса"],
                  "openQuestions": ["Нужен ли file-based store"],
                  "lastUserIntent": "Обновить память задачи"
                }
                """.trimIndent(),
            ),
        )

        val updatedState = service.update(
            previousTaskState = TaskState(),
            recentHistory = emptyList(),
            userMessage = "Давай хранить историю только в рамках сессии",
            config = testConfig(),
        )

        assertEquals(
            TaskState(
                goal = "Сделать mini-chat с RAG",
                constraints = listOf("CLI-only", "Без веба"),
                fixedTerms = listOf(
                    FixedTerm(
                        term = "task state",
                        definition = "рабочая память задачи",
                    ),
                ),
                knownFacts = listOf("История пока хранится в памяти процесса"),
                openQuestions = listOf("Нужен ли file-based store"),
                lastUserIntent = "Обновить память задачи",
            ),
            updatedState,
        )
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
}
