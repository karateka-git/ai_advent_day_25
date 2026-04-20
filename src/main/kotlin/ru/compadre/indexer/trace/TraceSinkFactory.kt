package ru.compadre.indexer.trace

import java.nio.file.Path

object TraceSinkFactory {
    fun create(outputDir: String): TraceSink =
        JsonlTraceSink(
            outputPath = Path.of(outputDir).resolve("logs").resolve("rag-trace.jsonl"),
        )
}
