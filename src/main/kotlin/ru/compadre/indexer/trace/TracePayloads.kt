package ru.compadre.indexer.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

fun tracePayload(builder: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(builder)

fun TraceSink.emitRecord(
    requestId: String,
    kind: String,
    stage: String,
    payload: JsonObject,
) {
    emit(
        TraceRecord(
            timestamp = Instant.now().toString(),
            requestId = requestId,
            kind = kind,
            stage = stage,
            payload = payload,
        ),
    )
}

fun jsonArrayOfStrings(values: Iterable<String>) = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

fun JsonObjectBuilder.putString(key: String, value: String?) {
    put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putInt(key: String, value: Int?) {
    put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putDouble(key: String, value: Double?) {
    put(key, JsonPrimitive(value))
}

fun JsonObjectBuilder.putBoolean(key: String, value: Boolean?) {
    put(key, JsonPrimitive(value))
}
