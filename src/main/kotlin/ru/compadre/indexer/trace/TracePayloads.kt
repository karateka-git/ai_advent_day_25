package ru.compadre.indexer.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import ru.compadre.indexer.chat.model.ChatMessageRecord
import ru.compadre.indexer.chat.model.FixedTerm
import ru.compadre.indexer.chat.model.TaskState
import ru.compadre.indexer.chat.retrieval.model.RetrievalAction
import ru.compadre.indexer.chat.retrieval.model.RetrievalSkipReason
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

fun fixedTermsTracePayload(terms: List<FixedTerm>) = buildJsonArray {
    terms.forEach { term ->
        add(
            buildJsonObject {
                putString("term", term.term)
                putString("definition", term.definition)
            },
        )
    }
}

fun taskStateTracePayload(taskState: TaskState): JsonObject = tracePayload {
    putString("goal", taskState.goal)
    put("constraints", jsonArrayOfStrings(taskState.constraints))
    put("fixedTerms", fixedTermsTracePayload(taskState.fixedTerms))
    put("knownFacts", jsonArrayOfStrings(taskState.knownFacts))
    put("openQuestions", jsonArrayOfStrings(taskState.openQuestions))
    putString("lastUserIntent", taskState.lastUserIntent)
}

fun chatHistoryTracePayload(messages: List<ChatMessageRecord>) = buildJsonArray {
    messages.forEach { message ->
        add(
            buildJsonObject {
                putInt("turnId", message.turnId)
                putString("role", message.role.name.lowercase())
                putString("text", message.text)
                putString("timestamp", message.timestamp.toString())
            },
        )
    }
}

fun retrievalActionTracePayload(
    action: RetrievalAction,
    skipReason: RetrievalSkipReason?,
    query: String?,
): JsonObject = tracePayload {
    putString("action", action.name.lowercase())
    putString("skipReason", skipReason?.name?.lowercase())
    putString("query", query)
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
