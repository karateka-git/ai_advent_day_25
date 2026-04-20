package ru.compadre.indexer.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.model.ChatCompletionRequest
import ru.compadre.indexer.llm.model.ChatCompletionResponse
import ru.compadre.indexer.llm.model.ChatMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Минимальный HTTP-клиент внешнего LLM API, используемого для генерации ответов.
 */
class ExternalLlmClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ChatCompletionClient {
    override
    fun complete(
        config: LlmSection,
        messages: List<ChatMessage>,
    ): String {
        val requestBody = json.encodeToString(
            ChatCompletionRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
            ),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL_TEMPLATE.format(config.agentId)))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Authorization", "Bearer ${config.userToken}")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build()
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        if (response.statusCode() !in 200..299) {
            error("LLM API вернул статус ${response.statusCode()}: ${response.body()}")
        }

        val completion = json.decodeFromString<ChatCompletionResponse>(response.body())
        return completion.choices.firstOrNull()?.message?.content
            ?: error("Ответ LLM API не содержит choices[0].message.content")
    }

    private companion object {
        private const val API_URL_TEMPLATE =
            "https://agent.timeweb.cloud/api/v1/cloud-ai/agents/%s/v1/chat/completions"
    }
}
