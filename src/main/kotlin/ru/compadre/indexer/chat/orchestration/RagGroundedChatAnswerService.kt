package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.orchestration.model.GroundedChatAnswerRequest
import ru.compadre.indexer.qa.RagQuestionAnsweringService
import ru.compadre.indexer.qa.model.RagAnswer

/**
 * Адаптер между chat orchestration и текущим single-turn RAG-сервисом.
 */
class RagGroundedChatAnswerService(
    private val ragQuestionAnsweringService: RagQuestionAnsweringService,
) : GroundedChatAnswerService {
    override suspend fun answer(request: GroundedChatAnswerRequest): RagAnswer =
        ragQuestionAnsweringService.answer(
            requestId = request.requestId,
            question = request.retrievalQuery,
            databasePath = request.databasePath,
            strategy = request.strategy,
            initialTopK = request.initialTopK,
            finalTopK = request.finalTopK,
            config = request.config,
        )
}
