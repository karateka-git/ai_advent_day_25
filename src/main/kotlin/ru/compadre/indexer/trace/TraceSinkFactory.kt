package ru.compadre.indexer.trace

import java.nio.file.Path

object TraceSinkFactory {
    fun create(outputDir: String): TraceSink =
        CompositeTraceSink(
            sinks = listOf(
                JsonlTraceSink(
                    outputPath = Path.of(outputDir).resolve("logs").resolve("rag-trace.jsonl"),
                ),
                ReadableTraceSink(
                    outputPath = Path.of(outputDir).resolve("logs").resolve("rag-trace-readable.md"),
                ),
            ),
        )
}
