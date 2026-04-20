package ru.compadre.indexer.chat.orchestration

import ru.compadre.indexer.chat.orchestration.model.AnswerRewriteRequest

/**
 * Контракт сервиса, который переписывает уже найденный grounded-ответ
 * без нового retrieval, сохраняя смысл и опору на прежние источники.
 */
interface AnswerRewriteService {
    /**
     * Переписывает текст последнего grounded-ответа под новое пользовательское требование.
     *
     * @param request данные текущего rewrite-хода.
     * @return новый текст ответа или `null`, если rewrite не удался.
     */
    suspend fun rewrite(request: AnswerRewriteRequest): String?
}
