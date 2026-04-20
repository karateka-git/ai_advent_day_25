package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.qa.model.RagAnswer

/**
 * Контракт сервиса, который строит ответ по найденному контексту для chat-хода.
 */
interface GroundedChatAnswerService {
    /**
     * Строит ответ с опорой на найденный контекст по уже собранному chat-aware запросу.
     *
     * @param request входные данные текущего chat-хода.
     * @return ответ с retrieval-метаданными.
     */
    suspend fun answer(request: GroundedChatAnswerRequest): RagAnswer
}
