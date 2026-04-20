package ru.compadre.indexer.chat.memory.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Семантический тип пользовательского хода внутри chat-сессии.
 */
@Serializable
enum class ChatTurnType {
    @SerialName("knowledge_question")
    KNOWLEDGE_QUESTION,

    @SerialName("answer_rewrite")
    ANSWER_REWRITE,

    @SerialName("task_state_update")
    TASK_STATE_UPDATE,

    @SerialName("topic_switch")
    TOPIC_SWITCH,

    @SerialName("service_turn")
    SERVICE_TURN,
}
