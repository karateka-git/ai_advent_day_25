package ru.compadre.indexer.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.compadre.indexer.config.AnswerGuardSection
import ru.compadre.indexer.config.AppConfig
import ru.compadre.indexer.config.AppSection
import ru.compadre.indexer.config.ChunkingSection
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.config.OllamaSection
import ru.compadre.indexer.config.SearchHeuristicSection
import ru.compadre.indexer.config.SearchModelRerankSection
import ru.compadre.indexer.config.SearchSection
import ru.compadre.indexer.embedding.model.ChunkEmbedding
import ru.compadre.indexer.llm.model.ChatMessage
import ru.compadre.indexer.model.ChunkMetadata
import ru.compadre.indexer.model.ChunkingStrategy
import ru.compadre.indexer.model.DocumentChunk
import ru.compadre.indexer.model.EmbeddedChunk
import ru.compadre.indexer.model.SourceType
import ru.compadre.indexer.search.model.SearchMatch
import ru.compadre.indexer.search.postprocess.ModelRerankEvaluation
import ru.compadre.indexer.search.postprocess.ModelRerankPostRetrievalProcessor
import ru.compadre.indexer.search.postprocess.ModelRerankPrompt
import ru.compadre.indexer.search.postprocess.ModelRerankScorer
import ru.compadre.indexer.trace.TraceRecord
import ru.compadre.indexer.trace.TraceSink
import java.nio.file.Path

class ModelRerankTraceTest {
    @Test
    fun `model rerank emits prompt and score trace with timing`() = runBlocking {
        val traceSink = RecordingTraceSink()
        val service = RetrievalPipelineService(
            searchEngine = FixedSearchEngine(listOf(sampleMatch())),
            traceSink = traceSink,
            modelRerankPostRetrievalProcessor = ModelRerankPostRetrievalProcessor(
                modelRerankJudge = FakeModelRerankScorer(),
                traceSink = traceSink,
            ),
        )

        service.retrieve(
            requestId = "req-rerank",
            query = "who is there",
            databasePath = Path.of("data/index-fixed.db"),
            strategy = ChunkingStrategy.FIXED,
            initialTopK = 1,
            finalTopK = 1,
            config = testConfig(postProcessingMode = "model-rerank"),
        )

        val kinds = traceSink.records.map(TraceRecord::kind)
        assertTrue(kinds.contains("model_rerank_prompt_built"))
        assertTrue(kinds.contains("model_rerank_scored"))

        val promptRecord = traceSink.records.first { it.kind == "model_rerank_prompt_built" }
        assertEquals("req-rerank", promptRecord.requestId)
        assertTrue(promptRecord.payload.containsKey("llmRequest"))

        val scoreRecord = traceSink.records.first { it.kind == "model_rerank_scored" }
        assertEquals("req-rerank", scoreRecord.requestId)
        assertTrue(scoreRecord.payload.containsKey("llmResponse"))
        assertEquals("""{"score":8.8}""", (scoreRecord.payload["llmResponse"] as JsonPrimitive).content)
    }

    private fun sampleMatch(): SearchMatch {
        val chunk = DocumentChunk(
            metadata = ChunkMetadata(
                chunkId = "doc-2#chunk-1",
                documentId = "doc-2",
                sourceType = SourceType.TEXT,
                filePath = "docs/doc-2.txt",
                title = "Doc 2",
                section = "section-2",
                startOffset = 0,
                endOffset = 64,
            ),
            strategy = ChunkingStrategy.FIXED,
            text = "sample rerank text",
        )
        return SearchMatch(
            embeddedChunk = EmbeddedChunk(
                chunk = chunk,
                embedding = ChunkEmbedding(
                    model = "test-model",
                    vector = listOf(0.2f, 0.3f),
                ),
            ),
            score = 0.42,
        )
    }

    private fun testConfig(postProcessingMode: String) = AppConfig(
        app = AppSection("./docs", "./data"),
        ollama = OllamaSection("http://localhost:11434", "test-embed"),
        llm = LlmSection("agent", "token", "test-model", 0.0, 32),
        chunking = ChunkingSection(100, 10),
        search = SearchSection(
            topK = 5,
            initialTopK = 1,
            finalTopK = 1,
            minSimilarity = 0.0,
            postProcessingMode = postProcessingMode,
            heuristic = SearchHeuristicSection(1, 0.7, 0.3, 0.1, 0.1, 0.1, 0.1),
            modelRerank = SearchModelRerankSection(true, 1),
        ),
        answerGuard = AnswerGuardSection(true, 0.2, 1),
    )

    private class FixedSearchEngine(
        private val matches: List<SearchMatch>,
    ) : SearchEngine {
        override suspend fun search(
            query: String,
            databasePath: Path,
            strategy: ChunkingStrategy?,
            topK: Int,
            config: AppConfig,
        ): List<SearchMatch> = matches
    }

    private class FakeModelRerankScorer : ModelRerankScorer {
        override fun buildPrompt(
            query: String,
            chunk: DocumentChunk,
            config: LlmSection,
        ): ModelRerankPrompt =
            ModelRerankPrompt(
                config = config,
                messages = listOf(
                    ChatMessage("system", "score it"),
                    ChatMessage("user", "Q: $query\nChunk: ${chunk.text}"),
                ),
            )

        override fun score(
            prompt: ModelRerankPrompt,
            fallbackCosineScore: Double,
        ): ModelRerankEvaluation =
            ModelRerankEvaluation(
                score = 8.8,
                usedFallback = false,
                rawResponse = """{"score":8.8}""",
            )
    }

    private class RecordingTraceSink : TraceSink {
        val records = mutableListOf<TraceRecord>()

        override fun emit(record: TraceRecord) {
            records += record
        }
    }
}
