package ru.compadre.indexer.chat.storage

import ru.compadre.indexer.chat.model.ChatSession

/**
 * Контракт хранения chat-сессий для mini-chat сценария.
 */
interface ChatSessionStore {
    /**
     * Создаёт новую пустую chat-сессию и сохраняет её в хранилище.
     *
     * @param sessionId идентификатор новой сессии.
     * @return созданная chat-сессия.
     */
    fun create(sessionId: String): ChatSession

    /**
     * Возвращает chat-сессию по идентификатору, если она есть в хранилище.
     *
     * @param sessionId идентификатор нужной сессии.
     * @return найденная chat-сессия или `null`, если она не существует.
     */
    fun findById(sessionId: String): ChatSession?

    /**
     * Сохраняет текущее состояние chat-сессии.
     *
     * @param session chat-сессия для сохранения.
     * @return сохранённая chat-сессия.
     */
    fun save(session: ChatSession): ChatSession
}
