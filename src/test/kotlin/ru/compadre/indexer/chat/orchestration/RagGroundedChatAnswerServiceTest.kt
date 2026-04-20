package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.qa.model.RagAnswer
import ru.compadre.indexer.qa.model.RagQuote
import ru.compadre.indexer.qa.model.RagSource
import ru.compadre.indexer.search.model.PostRetrievalMode
import ru.compadre.indexer.search.model.RetrievalPipelineResult
import kotlin.test.Test
import kotlin.test.assertEquals

class RagGroundedChatAnswerServiceTest {
    @Test
    fun `filter keeps only sources from active document when they exist`() {
        val filtered = filterAnswerToActiveDocument(
            ragAnswer = RagAnswer(
                answer = "Ответ",
                sources = listOf(
                    RagSource(
                        source = "C:/docs/reforma.txt",
                        section = "reforma",
                        chunkId = "reforma#2",
                    ),
                    RagSource(
                        source = "C:/docs/agasfer.txt",
                        section = "agasfer",
                        chunkId = "agasfer#1",
                    ),
                ),
                quotes = listOf(
                    RagQuote(
                        chunkId = "reforma#2",
                        quote = "Цитата из Реформы",
                    ),
                    RagQuote(
                        chunkId = "agasfer#1",
                        quote = "Лишняя цитата",
                    ),
                ),
                retrievalResult = emptyRetrievalResult(),
            ),
            taskState = TaskState(
                goal = "Обсуждать текст «Реформа»",
                constraints = listOf("Обсуждать только текст «Реформа»"),
            ),
        )

        assertEquals(listOf("reforma#2"), filtered.sources.map(RagSource::chunkId))
        assertEquals(listOf("reforma#2"), filtered.quotes.map(RagQuote::chunkId))
    }

    @Test
    fun `filter leaves answer unchanged when active document has no matching sources`() {
        val answer = RagAnswer(
            answer = "Ответ",
            sources = listOf(
                RagSource(
                    source = "C:/docs/agasfer.txt",
                    section = "agasfer",
                    chunkId = "agasfer#1",
                ),
            ),
            quotes = listOf(
                RagQuote(
                    chunkId = "agasfer#1",
                    quote = "Цитата",
                ),
            ),
            retrievalResult = emptyRetrievalResult(),
        )

        val filtered = filterAnswerToActiveDocument(
            ragAnswer = answer,
            taskState = TaskState(goal = "Обсуждать текст «Реформа»"),
        )

        assertEquals(answer, filtered)
    }

    private fun emptyRetrievalResult(): RetrievalPipelineResult =
        RetrievalPipelineResult(
            mode = PostRetrievalMode.NONE,
            initialTopK = 0,
            finalTopK = 0,
            candidates = emptyList(),
        )
}
