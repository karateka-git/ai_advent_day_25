package ru.compadre.indexer.chat.orchestration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.compadre.indexer.chat.model.ChatRole
import ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest
import ru.compadre.indexer.llm.ChatCompletionClient
import ru.compadre.indexer.llm.ExternalLlmClient
import ru.compadre.indexer.llm.model.ChatMessage

/**
 * LLM-реализация переписывания уже найденного grounded-ответа.
 */
class LlmAnswerRewriteService(
    private val llmClient: ChatCompletionClient = ExternalLlmClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) : AnswerRewriteService {
    override suspend fun rewrite(request: AnswerRewriteRequest): String? {
        val completion = llmClient.complete(
            request.config,
            messages = buildMessages(request),
        )
        return parseCompletion(completion)
    }

    internal fun parseCompletion(rawCompletion: String): String? {
        val payload = extractJsonPayload(rawCompletion)
        return runCatching {
            json.decodeFromString<AnswerRewriteCompletion>(payload)
        }.getOrNull()
            ?.answer
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    internal fun buildMessages(request: AnswerRewriteRequest): List<ChatMessage> =
        listOf(
            ChatMessage(
                role = SYSTEM_ROLE,
                content = SYSTEM_PROMPT,
            ),
            ChatMessage(
                role = USER_ROLE,
                content = buildPrompt(request),
            ),
        )

    private fun buildPrompt(request: AnswerRewriteRequest): String {
        val historyBlock = if (request.recentHistory.isEmpty()) {
            "<empty>"
        } else {
            request.recentHistory.joinToString(separator = "\n") { message ->
                "${message.role.toPromptRole()}: ${message.text}"
            }
        }

        return buildString {
            appendLine("Последний grounded-ответ:")
            appendLine(request.lastGroundedAnswer.answer)
            appendLine()
            appendLine("Источники grounded-ответа:")
            request.lastGroundedAnswer.sources.forEach { source ->
                appendLine("- ${source.source} :: ${source.section} :: ${source.chunkId}")
            }
            appendLine()
            appendLine("Цитаты grounded-ответа:")
            if (request.lastGroundedAnswer.quotes.isEmpty()) {
                appendLine("<empty>")
            } else {
                request.lastGroundedAnswer.quotes.forEach { quote ->
                    appendLine("- ${quote.chunkId}: ${quote.quote}")
                }
            }
            appendLine()
            appendLine("Текущее сообщение пользователя:")
            appendLine(request.userMessage)
            appendLine()
            appendLine("Последняя история:")
            appendLine(historyBlock)
            appendLine()
            appendLine("TaskState:")
            appendLine("goal = ${request.taskState.goal ?: "<none>"}")
            appendLine("constraints = ${request.taskState.constraints.ifEmpty { listOf("<none>") }.joinToString()}")
            appendLine("lastUserIntent = ${request.taskState.lastUserIntent ?: "<none>"}")
        }.trimEnd()
    }

    private fun extractJsonPayload(rawCompletion: String): String {
        val trimmed = rawCompletion.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }

        val lines = trimmed.lines()
        val startIndex = lines.indexOfFirst { it.trim().startsWith("```") }
        val endIndex = lines.indexOfLast { it.trim() == "```" }
        if (startIndex >= 0 && endIndex > startIndex) {
            return lines.subList(startIndex + 1, endIndex).joinToString(separator = "\n").trim()
        }

        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun ChatRole.toPromptRole(): String =
        when (this) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
        }

    @Serializable
    private data class AnswerRewriteCompletion(
        val answer: String? = null,
    )

    private companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"
        private val SYSTEM_PROMPT = """
            Ты переписываешь уже найденный grounded-ответ без добавления новых фактов.
            Верни ровно один JSON-объект и ничего больше.

            Схема:
            {
              "answer": "string"
            }

            Правила:
            - Не добавляй новых фактов, которых нет в исходном grounded-ответе.
            - Не меняй смысл ответа.
            - Учитывай новое требование пользователя к форме ответа.
            - Не упоминай источники или цитаты в явном виде, если пользователь этого не просил.
            - Не добавляй markdown, комментарии или лишние поля.
        """.trimIndent()
    }
}
