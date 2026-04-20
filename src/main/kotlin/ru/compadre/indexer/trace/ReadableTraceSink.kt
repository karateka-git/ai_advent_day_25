package ru.compadre.indexer.trace

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ReadableTraceSink(
    private val outputPath: Path,
    private val json: Json = Json {
        prettyPrint = true
        explicitNulls = false
    },
) : TraceSink {
    private val writeLock = ReentrantLock()

    override fun emit(record: TraceRecord) {
        runCatching {
            writeLock.withLock {
                Files.createDirectories(outputPath.parent)
                Files.writeString(
                    outputPath,
                    formatRecord(record),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }

    private fun formatRecord(record: TraceRecord): String =
        buildString {
            appendLine("## ${record.kind}")
            appendLine("timestamp: ${record.timestamp}")
            appendLine("requestId: ${record.requestId}")
            appendLine("stage: ${record.stage}")
            appendLine("payload:")
            appendLine("```json")
            appendLine(json.encodeToString(record.payload))
            appendLine("```")
            appendLine()
        }
}
