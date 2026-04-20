package ru.compadre.indexer.chat.retrieval

import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason
import java.time.Instant
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
                lastUserIntent = "Найти способ хранения истории диалога для mini-chat с RAG",
            ),
        )

        assertEquals(RetrievalAction.PERFORMED, result.action)
        assertEquals(null, result.skipReason)
        assertTrue(result.query!!.contains("Найти способ хранения истории диалога для mini-chat с RAG"))
        assertTrue(!result.query.contains("Сделать mini-chat с RAG"))
        assertTrue(!result.query.contains("CLI-only"))
        assertTrue(!result.query.contains("task state: рабочая память задачи"))
    }

    @Test
    fun `build skips retrieval for task-state only turn`() {
        val result = builder.build(
            userMessage = "Будем обсуждать только текст «Реформа». Отвечай только по нему.",
            taskState = TaskState(),
        )

        assertEquals(
            RetrievalQueryBuildResult(
                action = RetrievalAction.SKIPPED,
                skipReason = RetrievalSkipReason.TASK_STATE_UPDATE_ONLY,
            ),
            result,
        )
    }

    @Test
    fun `build reuses last intent for answer style modifier`() {
        val result = builder.build(
            userMessage = "Коротко, в 2-3 предложениях.",
            taskState = TaskState(
                constraints = listOf("Обсуждать только текст «Реформа»"),
                lastUserIntent = "Найти в тексте «Реформа» описание образа богини Реформы",
            ),
            recentHistory = listOf(
                ChatMessageRecord(
                    turnId = 1,
                    role = ChatRole.USER,
                    text = "А как там показана сама Реформа?",
                    timestamp = Instant.parse("2026-04-20T10:00:00Z"),
                ),
            ),
        )

        assertEquals(RetrievalAction.PERFORMED, result.action)
        assertTrue(result.query!!.contains("Текст «Реформа»."))
        assertTrue(result.query.contains("Реформа"))
        assertTrue(result.query.contains("Найти в тексте «Реформа» описание образа богини Реформы"))
        assertTrue(result.query.contains("Формат ответа:"))
        assertTrue(result.query.contains("Коротко, в 2-3 предложениях."))
        assertTrue(!result.query.contains("Предыдущий пользовательский вопрос:"))
    }

    @Test
    fun `build uses retrieval-ready intent as semantic core for follow-up question`() {
        val result = builder.build(
            userMessage = "Что ещё в этом тексте связано с её происхождением?",
            taskState = TaskState(
                constraints = listOf("Обсуждать только текст «Реформа»"),
                lastUserIntent = "Найти в тексте «Реформа» дополнительные детали происхождения богини Реформы",
            ),
        )

        assertEquals(RetrievalAction.PERFORMED, result.action)
        assertTrue(result.query!!.contains("Текст «Реформа»."))
        assertTrue(result.query.contains("дополнительные детали происхождения богини Реформы"))
        assertTrue(!result.query.contains("Текущее намерение:"))
        assertTrue(!result.query.contains("Ограничения:"))
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
