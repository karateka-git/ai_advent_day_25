package ru.compadre.indexer.llm

import ru.compadre.indexer.config.LlmSection
import ru.compadre.indexer.llm.model.ChatMessage

/**
 * Контракт клиента, который умеет выполнять chat completion запросы.
 */
interface ChatCompletionClient {
    /**
     * Выполняет chat completion по переданным сообщениям.
     *
     * @param config настройки внешней LLM.
     * @param messages сообщения для completion-запроса.
     * @return текст ответа модели.
     */
    fun complete(
        config: LlmSection,
        messages: List<ChatMessage>,
    ): String
}
