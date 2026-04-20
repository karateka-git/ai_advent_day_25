package ru.compadre.indexer.cli

import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalQueryBuildResult
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import ru.compadre.indexer.workflow.result.ChatTurnCliResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultCliOutputFormatterTest {
    private val formatter = DefaultCliOutputFormatter()

    @Test
    fun `chat refusal does not print empty sources placeholder`() {
        val text = formatter.format(
            ChatTurnCliResult(
                userMessage = "Как хранить историю?",
                retrievalQuery = RetrievalQueryBuildResult(
                    action = RetrievalAction.PERFORMED,
                    query = "Как хранить историю?",
                ),
                ragAnswer = RagAnswer(
                    answer = "Не знаю. Уточните вопрос: в предоставленном контексте нет данных.",
                    isRefusal = true,
                    retrievalResult = emptyRetrievalResult(),
                ),
            ),
        )

        assertTrue(text.contains("Статус: не удалось получить ответ с опорой на контекст."))
        assertFalse(text.contains("<источники пока не собраны>"))
        assertFalse(text.contains("Ответ пока не получен."))
    }

    private fun emptyRetrievalResult(): RetrievalPipelineResult =
        RetrievalPipelineResult(
            mode = PostRetrievalMode.NONE,
            initialTopK = 0,
            finalTopK = 0,
            candidates = emptyList(),
        )
}
