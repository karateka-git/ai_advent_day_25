package ru.compadre.indexer.trace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ReadableTraceSinkTest {
    @Test
    fun `writes readable markdown trace next to payload`() {
        val tempDir = createTempDirectory("readable-trace-test")
        val outputPath = tempDir.resolve("logs").resolve("rag-trace-readable.md")
        val sink = ReadableTraceSink(outputPath)

        sink.emit(
            TraceRecord(
                timestamp = "2026-04-20T12:00:00Z",
                requestId = "req-readable-1",
                kind = "answer_guard_checked",
                stage = "rag.answer_guard",
                payload = buildJsonObject {
                    put("decision", "allow")
                    put("selectedMatchesCount", 3)
                },
            ),
        )

        assertTrue(outputPath.exists(), "Readable trace file should be created")
        val content = outputPath.readText()
        assertTrue(content.contains("## answer_guard_checked"))
        assertTrue(content.contains("requestId: req-readable-1"))
        assertTrue(content.contains("\"decision\": \"allow\""))
        assertTrue(content.contains("\"selectedMatchesCount\": 3"))
    }
}
