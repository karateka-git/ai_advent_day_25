package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.qa.model.RagAnswer

/**
 * Контракт сервиса, который строит grounded answer для chat-хода.
 */
interface GroundedChatAnswerService {
    /**
     * Строит grounded answer по уже собранному chat-aware запросу.
     *
     * @param request входные данные текущего chat-хода.
     * @return grounded answer с retrieval-метаданными.
     */
    suspend fun answer(request: GroundedChatAnswerRequest): RagAnswer
}
