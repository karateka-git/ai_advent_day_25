package ru.compadre.indexer.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * JSONL-friendly trace record that keeps a stable envelope and flexible payload.
 */
@Serializable
data class TraceRecord(
    val timestamp: String,
    val requestId: String,
    val kind: String,
    val stage: String,
    val payload: JsonObject,
)
