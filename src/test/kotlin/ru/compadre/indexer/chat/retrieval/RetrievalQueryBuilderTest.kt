package ru.compadre.indexer.chat.retrieval

import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetrievalQueryBuilderTest {
    private val builder = RetrievalQueryBuilder()

    @Test
    fun `build returns skipped result for short service turn`() {
        val result = builder.build(
            userMessage = "окей",
            taskState = TaskState(goal = "Сделать mini-chat"),
        )

        assertEquals(
            RetrievalQueryBuildResult(
                action = RetrievalAction.SKIPPED,
                skipReason = RetrievalSkipReason.SHORT_SERVICE_TURN,
            ),
            result,
        )
    }

    @Test
    fun `build returns performed query with task state context`() {
        val result = builder.build(
            userMessage = "Как хранить историю диалога?",
            taskState = TaskState(
                goal = "Сделать mini-chat с RAG",
                constraints = listOf("CLI-only", "Без веба"),
                fixedTerms = listOf(
                    FixedTerm(
                        term = "task state",
                        definition = "рабочая память задачи",
                    ),
                ),
                lastUserIntent = "Продолжить проектирование chat-слоя",
            ),
        )

        assertEquals(RetrievalAction.PERFORMED, result.action)
        assertEquals(null, result.skipReason)
        assertTrue(result.query!!.contains("Как хранить историю диалога?"))
        assertTrue(result.query.contains("Сделать mini-chat с RAG"))
        assertTrue(result.query.contains("CLI-only"))
        assertTrue(result.query.contains("task state: рабочая память задачи"))
        assertTrue(result.query.contains("Продолжить проектирование chat-слоя"))
    }

    @Test
    fun `build returns performed query for non service short message`() {
        val result = builder.build(
            userMessage = "И как тогда быть?",
            taskState = TaskState(goal = "Сделать mini-chat"),
        )

        assertEquals(RetrievalAction.PERFORMED, result.action)
        assertTrue(result.query!!.contains("И как тогда быть?"))
    }
}
